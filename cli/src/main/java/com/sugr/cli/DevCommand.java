package com.sugr.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * `sugr dev` - starts the Vite dev server, waits for its URL, then runs the
 * Java app with SUGR_DEV_URL pointing at it (Application.java/Main.java pick
 * that env var up - see examples/sql-client). Also watches the Java sources
 * and rebuilds/restarts the app on change - the frontend gets HMR for free
 * from Vite, this is the Java-side equivalent. Automates what we did by hand
 * with two terminals throughout Giai đoạn 1-3.
 */
@Command(name = "dev", description = "Run the Vite dev server and the Java app together (HMR + Java restart-on-change)")
final class DevCommand implements Callable<Integer> {

    // Stops at whitespace OR an ANSI escape char - Vite colors its output, and the
    // escape codes land inside words (e.g. "Local" + ESC + "[22m:"), so a plain \S+ match
    // would swallow escape sequences into the "URL" and a naive "Local:" substring
    // check would never match at all.
    private static final Pattern DEV_URL_PATTERN = Pattern.compile("(https?://[^\\s\\x1B]+)");
    private static final long DEBOUNCE_MILLIS = 300;

    @Option(names = {"-f", "--frontend"}, description = "Path to the frontend/ dir", defaultValue = "frontend")
    String frontendDir;

    @Option(names = {"-j", "--java-src"}, description = "Path to watch for Java changes", defaultValue = "lib/src")
    String javaSrcDir;

    @Option(names = {"-t", "--task"}, description = "Gradle task that runs the Java app "
            + "(auto-detected by default - see GradleProjectLocator)")
    String gradleTask;

    @Option(names = {"-g", "--gradle-dir"}, description = "Directory to run gradle from "
            + "(auto-detected by default - see GradleProjectLocator)")
    String gradleDir;

    private GradleProjectLocator.Result located;
    private String devUrl;
    private final AtomicReference<Process> currentApp = new AtomicReference<>();

    @Override
    public Integer call() throws Exception {
        Path frontend = Path.of(frontendDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(frontend)) {
            System.err.println("[sugr] frontend directory not found: " + frontend);
            return 1;
        }

        located = GradleProjectLocator.resolve(gradleTask, gradleDir, "run");

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
        ProcessBuilder appPb = new ProcessBuilder(
                ProcessUtil.shellCommand("gradle", located.task(), "--console=plain", "--no-daemon"));
        appPb.directory(located.gradleDir().toFile());
        appPb.environment().put("SUGR_DEV_URL", devUrl);
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
