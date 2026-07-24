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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared dev-loop engine behind both `sugr dev` and `sugr debug`: starts the
 * Vite dev server, waits for its URL, then runs the Java app together with
 * it (SUGR_DEV_URL - see examples/sql-client), watching Java sources and
 * rebuilding/restarting the app on change. `sugr debug` reuses this exact
 * loop, only adding extra gradle args (e.g. `--debug-jvm`) and extra
 * environment variables (e.g. WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS to
 * enable CDP remote debugging of the webview) - see DebugCommand.
 */
final class DevRuntime {

    // Stops at whitespace OR an ANSI escape char - Vite colors its output, and the
    // escape codes land inside words (e.g. "Local" + ESC + "[22m:"), so a plain \S+ match
    // would swallow escape sequences into the "URL" and a naive "Local:" substring
    // check would never match at all.
    private static final Pattern DEV_URL_PATTERN = Pattern.compile("(https?://[^\\s\\x1B]+)");
    private static final long DEBOUNCE_MILLIS = 300;

    private final String frontendDir;
    private final String javaSrcDir;
    private final GradleProjectLocator.Result located;
    private final List<String> extraGradleArgs;
    private final Map<String, String> extraEnv;

    private String devUrl;
    private final AtomicReference<Process> currentApp = new AtomicReference<>();

    DevRuntime(String frontendDir, String javaSrcDir, GradleProjectLocator.Result located,
               List<String> extraGradleArgs, Map<String, String> extraEnv) {
        this.frontendDir = frontendDir;
        this.javaSrcDir = javaSrcDir;
        this.located = located;
        this.extraGradleArgs = extraGradleArgs;
        this.extraEnv = extraEnv;
    }

    Integer run() throws Exception {
        Path frontend = Path.of(frontendDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(frontend)) {
            System.err.println("[sugr] frontend directory not found: " + frontend);
            return 1;
        }

        System.out.println("[sugr] starting Vite dev server in " + frontend + " ...");
        Process vite = new ProcessBuilder(ProcessUtil.shellCommand("pnpm", "dev"))
                .directory(frontend.toFile())
                .redirectErrorStream(true)
                .start();

        devUrl = waitForDevServerUrl(vite);
        if (devUrl == null) {
            System.err.println("[sugr] could not detect the Vite dev server URL - is `pnpm dev` printing a Local: line?");
            vite.destroy();
            return 1;
        }
        System.out.println("[sugr] frontend ready at " + devUrl);

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
            System.out.println("[sugr] " + srcDir + " doesn't exist - skipping Java restart-on-change, "
                    + "just running the app once.");
            Process app = currentApp.get();
            int exit = app != null ? app.waitFor() : 1;
            killTree(vite);
            return exit;
        }
        return 0;
    }

    /** Kills the currently running app (if any) and starts a fresh one, rebuilding first. */
    private void restartApp() throws IOException, InterruptedException {
        Process old = currentApp.getAndSet(null);
        if (old != null) {
            killTree(old);
            old.waitFor();
        }

        // --no-daemon: the app's JVM is a genuine child of this process (killable via
        // killTree above) instead of being forked by the long-lived Gradle Daemon, which
        // isn't part of our process tree and survives a plain destroyForcibly() - that's
        // what left orphaned app windows behind before this fix.
        // --rerun-tasks: a plain JavaExec `run` task has no declared outputs, so it should
        // never be considered up-to-date - but empirically, re-invoking the exact same
        // task/args/classpath across separate `gradle` process launches (as every restart
        // here does) sometimes gets treated as up-to-date anyway and silently skips
        // actually launching the app. Forcing a rerun is the only way to guarantee restart
        // always really restarts, which matters a lot more here than the extra checks cost.
        List<String> gradleArgs = new ArrayList<>(List.of(
                "gradle", located.task(), "--console=plain", "--no-daemon", "--rerun-tasks"));
        gradleArgs.addAll(extraGradleArgs);
        ProcessBuilder appPb = new ProcessBuilder(ProcessUtil.shellCommand(gradleArgs.toArray(new String[0])));
        appPb.directory(located.gradleDir().toFile());
        appPb.environment().put("SUGR_DEV_URL", devUrl);
        appPb.environment().putAll(extraEnv);
        appPb.redirectErrorStream(true);
        appPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        System.out.println("[sugr] (re)building and starting the app (gradle " + located.task()
                + " from " + located.gradleDir() + ") ...");
        currentApp.set(appPb.start());
    }

    /** Blocks, restarting the app each time a .java file under srcDir changes, until interrupted (Ctrl+C). */
    private void watchAndRestartOnChange(Path srcDir) throws IOException {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            registerRecursive(srcDir, watcher);
            System.out.println("[sugr] watching " + srcDir + " for changes ...");

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
            System.out.println("[vite] " + line);
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
                System.out.println("[vite] " + line);
            }
        } catch (IOException ignored) {
            // vite process ended
        }
    }
}
