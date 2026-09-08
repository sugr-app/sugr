package com.sugr.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shared dev-loop engine behind both `sugr dev` and `sugr debug`: starts the
 * Vite dev server (passed `--mode <env>` so it picks up the right
 * `.env.<env>` file), waits for its URL, then runs the Java app together
 * with it (SUGR_DEV_URL/SUGR_ENV - see examples/sql-client and
 * com.sugr.core.AppConfig), watching Java sources and rebuilding/restarting
 * the app on change. `sugr debug` reuses this exact loop, only adding extra
 * gradle args (e.g. `--debug-jvm`) and extra environment variables (e.g.
 * WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS to enable CDP remote debugging of
 * the webview) - see DebugCommand.
 */
final class DevRuntime {

    // Stops at whitespace OR an ANSI escape char - Vite colors its output, and the
    // escape codes land inside words (e.g. "Local" + ESC + "[22m:"), so a plain \S+ match
    // would swallow escape sequences into the "URL" and a naive "Local:" substring
    // check would never match at all.
    private static final Pattern DEV_URL_PATTERN = Pattern.compile("(https?://[^\\s\\x1B]+)");
    private static final long DEBOUNCE_MILLIS = 300;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String frontendDir;
    private final String javaSrcDir;
    private final GradleProjectLocator.Result located;
    private final List<String> extraGradleArgs;
    private final Map<String, String> extraEnv;
    private final String env;

    private String devUrl;
    private final AtomicReference<Process> currentApp = new AtomicReference<>();

    /** The thread blocked in {@link #watchAndRestartOnChange} - interrupted to end the dev loop. */
    private volatile Thread devLoopThread;
    /** True only while {@link #restartApp} is deliberately killing/replacing the app, so its exit isn't mistaken for the user closing the window. */
    private volatile boolean restarting = false;
    /** True once we're tearing down (Ctrl+C / app closed), so a late process exit doesn't re-trigger shutdown. */
    private volatile boolean shuttingDown = false;
    /** Vite's child processes captured at startup, before pnpm/cmd exit and orphan the node process - see {@link #stopVite}. */
    private final java.util.List<ProcessHandle> viteTree = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** JVMs already hosting a webview when the dev session started - left alone by {@link #ownAppWindows} so we only ever touch our own app. */
    private volatile Set<Long> preexistingAppJvms = Set.of();

    DevRuntime(String frontendDir, String javaSrcDir, GradleProjectLocator.Result located,
               List<String> extraGradleArgs, Map<String, String> extraEnv, String env) {
        this.frontendDir = frontendDir;
        this.javaSrcDir = javaSrcDir;
        this.located = located;
        this.extraGradleArgs = extraGradleArgs;
        this.extraEnv = extraEnv;
        this.env = env;
    }

    Integer run() throws Exception {
        Path frontend = Path.of(frontendDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(frontend)) {
            System.err.println("[sugr] frontend directory not found: " + frontend);
            return 1;
        }

        log("[sugr] starting Vite dev server in " + frontend + " ...");
        Process vite = new ProcessBuilder(ProcessUtil.shellCommand("pnpm", "dev", "--mode", env))
                .directory(frontend.toFile())
                .redirectErrorStream(true)
                .start();

        devUrl = waitForDevServerUrl(vite);
        if (devUrl == null) {
            System.err.println("[sugr] could not detect the Vite dev server URL - is `pnpm dev` printing a Local: line?");
            vite.destroy();
            return 1;
        }
        log("[sugr] frontend ready at " + devUrl);

        // Snapshot Vite's child tree now, while the cmd -> pnpm -> node chain is still intact.
        // pnpm/cmd exit almost immediately, orphaning the actual `node vite.js` process, and
        // once that happens vite.descendants() no longer lists it - so a kill at shutdown time
        // would leave it running. These handles stay valid across the reparenting.
        vite.descendants().forEach(viteTree::add);

        // Whatever webview-hosting JVMs are already up (another sugr app the user has open)
        // are recorded now so Ctrl+C only ever kills the app *this* dev session launched.
        preexistingAppJvms = webviewHostingJvms().map(ProcessHandle::pid).collect(Collectors.toSet());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shuttingDown = true;
            closeAppWindow();
            Process client = currentApp.get();
            if (client != null) killTree(client);
            stopVite(vite);
        }));

        restartApp();

        Path srcDir = Path.of(javaSrcDir).toAbsolutePath().normalize();
        if (Files.isDirectory(srcDir)) {
            devLoopThread = Thread.currentThread();
            watchAndRestartOnChange(srcDir);
            // Fell out of the watch loop because the app was closed (see watchForAppExit) or
            // Ctrl+C - either way we're done; tear everything down now rather than leaving it
            // to the shutdown hook.
            closeAppWindow();
            Process client = currentApp.get();
            if (client != null) killTree(client);
            stopVite(vite);
            return 0;
        } else {
            log("[sugr] " + srcDir + " doesn't exist - skipping Java restart-on-change, "
                    + "just running the app once.");
            Process app = currentApp.get();
            int exit = app != null ? app.waitFor() : 1;
            stopVite(vite);
            return exit;
        }
    }

    /** Force-kills the Vite process plus every child captured at startup and any still-live descendant. */
    private void stopVite(Process vite) {
        vite.descendants().forEach(ProcessHandle::destroyForcibly);
        viteTree.forEach(ProcessHandle::destroyForcibly);
        vite.destroyForcibly();
    }

    /** How long {@link #closeAppWindow} waits for the app to close on its own before force-killing it. */
    private static final long GRACEFUL_CLOSE_TIMEOUT_MILLIS = 5_000;

    /**
     * The app window(s) {@code sugr dev} launched. {@code killTree(currentApp)} can't reach
     * them: the Gradle daemon forks the app JVM as its own child, outside the {@code gradle
     * ... run} client tree. Scoped to JVMs that started hosting a webview *after* this dev
     * session began ({@link #preexistingAppJvms}), so another sugr app the user has open is
     * never touched.
     */
    private List<ProcessHandle> ownAppWindows() {
        return webviewHostingJvms()
                .filter(h -> !preexistingAppJvms.contains(h.pid()))
                .toList();
    }

    /**
     * Closes the app the same way its close button does: {@code taskkill} without {@code /F}
     * posts {@code WM_CLOSE} to the process's windows, so the app's {@code onCloseRequested}/
     * {@code onClosed} hooks and window-state persistence all run. Falls back to a forced
     * kill only for a window that hasn't gone within {@link #GRACEFUL_CLOSE_TIMEOUT_MILLIS}
     * (e.g. one that vetoed the close). Used for Ctrl+C and the end-of-session teardown.
     */
    private void closeAppWindow() {
        List<ProcessHandle> apps = ownAppWindows();
        if (apps.isEmpty()) {
            return;
        }
        for (ProcessHandle app : apps) {
            if (ProcessUtil.isWindows()) {
                // taskkill without /F posts WM_CLOSE to the process's windows - the close-button path.
                ProcessUtil.runCapture("taskkill", "/PID", Long.toString(app.pid()));
            } else {
                app.destroy(); // SIGTERM - the polite "please close" on macOS/Linux
            }
        }
        long deadline = System.currentTimeMillis() + GRACEFUL_CLOSE_TIMEOUT_MILLIS;
        for (ProcessHandle app : apps) {
            while (app.isAlive() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        forceKillAppWindow(); // anything that ignored WM_CLOSE
    }

    /** Force-kills any remaining app window + its descendants (webview, tray/shortcut helpers). */
    private void forceKillAppWindow() {
        ownAppWindows().forEach(h -> {
            h.descendants().forEach(ProcessHandle::destroyForcibly);
            h.destroyForcibly();
        });
    }

    /** Substrings identifying an OS webview helper process spawned as a child of a sugr app JVM. */
    private static final List<String> WEBVIEW_CHILD_MARKERS =
            List.of("msedgewebview2", "webkitwebprocess", "webkit.webcontent");

    /** Every live JVM whose direct children include an OS webview helper - i.e. a running sugr app window. */
    private static java.util.stream.Stream<ProcessHandle> webviewHostingJvms() {
        return ProcessHandle.allProcesses().filter(h -> {
            String cmd = h.info().command().orElse("").toLowerCase();
            boolean isJava = cmd.endsWith("java.exe") || cmd.endsWith("javaw.exe")
                    || cmd.endsWith("/java") || cmd.endsWith("/javaw");
            if (!isJava) {
                return false;
            }
            return h.children().anyMatch(c -> {
                String child = c.info().command().orElse("").toLowerCase();
                return WEBVIEW_CHILD_MARKERS.stream().anyMatch(child::contains);
            });
        });
    }

    private static void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message);
    }

    /** Kills the currently running app and starts a fresh one, rebuilding first. */
    private void restartApp() throws IOException, InterruptedException {
        restarting = true;
        Process old = currentApp.getAndSet(null);
        if (old != null) {
            killTree(old);
            forceKillAppWindow(); // the daemon-forked window isn't under `old`; a rebuild kills it outright, no graceful close
            old.waitFor();
        }

        // Using Gradle daemon (no --no-daemon) allows reusing existing idle daemons.
        // --rerun-tasks ensures the app actually restarts on each change.
        List<String> gradleArgs = new ArrayList<>(List.of(
                "gradle", located.task(), "--console=plain", "--rerun-tasks"));
        gradleArgs.addAll(extraGradleArgs);
        ProcessBuilder appPb = new ProcessBuilder(ProcessUtil.shellCommand(gradleArgs.toArray(new String[0])));
        appPb.directory(located.gradleDir().toFile());
        appPb.environment().put("JAVA_TOOL_OPTIONS", "-Xms32m -Xmx32m");
        appPb.environment().put("SUGR_DEV_URL", devUrl);
        appPb.environment().put("SUGR_ENV", env);
        appPb.environment().putAll(extraEnv);
        appPb.redirectErrorStream(true);
        appPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        log("[sugr] (re)building and starting the app (gradle " + located.task()
                + " from " + located.gradleDir() + ") ...");
        Process app = appPb.start();
        currentApp.set(app);
        restarting = false;
        watchForAppExit(app);
    }

    /**
     * Ends the dev loop when the app exits on its own - i.e. the user closed the window -
     * so `sugr dev` returns to the shell instead of sitting in the file watcher forever.
     * A non-zero exit (build failure, app crash) leaves the loop running so a fix + save
     * still rebuilds; a restart we triggered ourselves is ignored via {@link #restarting}.
     */
    private void watchForAppExit(Process app) {
        app.onExit().thenAccept(finished -> {
            if (restarting || shuttingDown) {
                return;
            }
            int code = finished.exitValue();
            if (code != 0) {
                log("[sugr] app exited (code " + code + ") - waiting for a change to rebuild ...");
                return;
            }
            shuttingDown = true;
            log("[sugr] app window closed - stopping dev server");
            Thread loop = devLoopThread;
            if (loop != null) {
                loop.interrupt();
            }
        });
    }

    /**
     * Blocks, restarting the app each time a .java file under srcDir changes. Returns when
     * the thread is interrupted - by Ctrl+C, or by {@link #watchForAppExit} once the app
     * window is closed.
     */
    private void watchAndRestartOnChange(Path srcDir) throws IOException {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            registerRecursive(srcDir, watcher);
            log("[sugr] watching " + srcDir + " for changes ...");

            while (true) {
                WatchKey key;
                try {
                    key = watcher.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object context = event.context();
                    if (context != null && context.toString().endsWith(".java")) {
                        relevant = true;
                    }
                }
                boolean stillValid = key.reset();
                if (!stillValid || !relevant) {
                    continue;
                }

                try {
                    // Debounce: editors/save-all often fire several events for one save.
                    Thread.sleep(DEBOUNCE_MILLIS);
                    drainPendingEvents(watcher);
                    restartApp();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void drainPendingEvents(WatchService watcher) {
        WatchKey extra;
        while ((extra = watcher.poll()) != null) {
            extra.pollEvents();
            extra.reset();
        }
    }

    private void registerRecursive(Path root, WatchService watcher) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path dir : (Iterable<Path>) stream.filter(Files::isDirectory)::iterator) {
                dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            }
        }
    }

    /**
     * Kills a process AND its descendants. A plain Process.destroyForcibly() only kills
     * the process we directly spawned (e.g. the "gradle"/"cmd" launcher) - any children
     * it forked (a JavaExec'd app JVM, a Gradle Daemon's worker) survive and become
     * orphaned windows/processes. See the --no-daemon comment in restartApp() for why
     * this matters more than usual here.
     */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** Reads vite's output, echoing every line, until a line contains an http(s) URL. */
    private String waitForDevServerUrl(Process vite) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(vite.getInputStream(), StandardCharsets.UTF_8));
        String foundUrl = null;
        String line;
        while ((line = reader.readLine()) != null) {
            log("[vite] " + line);
            if (foundUrl == null) {
                // Vite wraps each styled segment (including the port number) in its own
                // ANSI escape codes, so the URL isn't contiguous in the raw line - strip
                // escape codes first, then match.
                String plain = line.replaceAll("\\u001B\\[[0-9;]*[a-zA-Z]", "");
                Matcher m = DEV_URL_PATTERN.matcher(plain);
                if (m.find()) {
                    foundUrl = m.group(1);
                    // Keep forwarding vite's remaining output on a background thread instead
                    // of blocking here, so its stdout pipe never fills up and stalls it.
                    Thread.ofVirtual().start(() -> forwardRemaining(reader));
                    break;
                }
            }
        }
        return foundUrl;
    }

    private void forwardRemaining(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                log("[vite] " + line);
            }
        } catch (IOException ignored) {
            // vite process ended
        }
    }
}
