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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process app = currentApp.get();
            if (app != null) killTree(app);
            killTree(vite);
        }));

        restartApp();

        Path srcDir = Path.of(javaSrcDir).toAbsolutePath().normalize();
        if (Files.isDirectory(srcDir)) {
            watchAndRestartOnChange(srcDir);
        } else {
            log("[sugr] " + srcDir + " doesn't exist - skipping Java restart-on-change, "
                    + "just running the app once.");
            Process app = currentApp.get();
            int exit = app != null ? app.waitFor() : 1;
            killTree(vite);
            return exit;
        }
        return 0;
    }

    private static void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message);
    }

    /** Kills the currently running app and starts a fresh one, rebuilding first. */
    private void restartApp() throws IOException, InterruptedException {
        Process old = currentApp.getAndSet(null);
        if (old != null) {
            killTree(old);
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
        currentApp.set(appPb.start());
    }

    /** Blocks, restarting the app each time a .java file under srcDir changes, until interrupted (Ctrl+C). */
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
