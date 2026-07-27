package com.sugr.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * `sugr debug` - same dev loop as `sugr dev` (see DevRuntime), with backend
 * and frontend debugging independently toggleable, both landing you in a real
 * IDE breakpoint (VS Code for now - auto-writes {@code .vscode/launch.json},
 * see VsCodeLaunch):
 * - backend: appends gradle's built-in `--debug-jvm` to every app launch, so
 *   the JVM suspends and waits for a debugger on port {@link #BACKEND_PORT}
 *   each time it (re)starts.
 * - frontend: sets WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS (see
 *   ProcessUtil#cdpDebugEnv) so the app's own webview - the real window, not
 *   a separate browser tab - exposes a Chrome DevTools Protocol endpoint on
 *   {@link #FRONTEND_PORT} that VS Code's built-in Edge/Chrome debugger can
 *   attach to directly.
 */
@Command(name = "debug", description = "Like `dev`, but with backend (JDWP) and/or frontend (CDP) debugging enabled")
final class DebugCommand implements Callable<Integer> {

    private static final int BACKEND_PORT = 5005;
    private static final int FRONTEND_PORT = 9222;

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

    @Option(names = "--no-backend",
            description = "Don't debug the backend - it just watches/restarts like `sugr dev`, no JDWP agent")
    boolean noBackend;

    @Option(names = "--no-frontend",
            description = "Don't debug the frontend - no CDP remote debugging port on the webview")
    boolean noFrontend;

    @Option(names = {"-e", "--env"}, description = "Environment (dev, staging, prod)", defaultValue = "dev")
    String env;

    @Override
    public Integer call() throws Exception {
        GradleProjectLocator.Result located = GradleProjectLocator.resolve(gradleTask, gradleDir, "run");

        boolean backend = !noBackend;
        boolean frontendDebug = !noFrontend;

        List<String> extraGradleArgs = backend ? List.of("--debug-jvm") : List.of();
        Map<String, String> extraEnv = frontendDebug ? ProcessUtil.cdpDebugEnv(FRONTEND_PORT) : Map.of();

        if (backend) {
            System.out.println("[sugr] backend debug: JVM will suspend and wait for a debugger on port "
                    + BACKEND_PORT + " each time it (re)starts");
            if (frontendDebug) {
                // The webview is created from inside main(), which never runs until something
                // attaches on BACKEND_PORT - so with both sides on, frontend attach will fail
                // ("could not find any debuggable target") until backend is attached first.
                System.out.println("[sugr] both sides are on: attach \"" + VsCodeLaunch.BACKEND_CONFIG_NAME
                        + "\" FIRST to let the JVM past its suspend point and actually open the window - "
                        + "only then will port " + FRONTEND_PORT + " (frontend) be listening");
            }
        }
        if (frontendDebug) {
            System.out.println("[sugr] frontend debug: the app's webview exposes a CDP debugger on port "
                    + FRONTEND_PORT + " - breakpoints in the real app window, not a browser tab");
        }

        Path appRoot = Path.of(".").toAbsolutePath().normalize();
        VsCodeLaunch.ensure(appRoot, BACKEND_PORT, FRONTEND_PORT);

        return new DevRuntime(frontendDir, javaSrcDir, located, extraGradleArgs, extraEnv, env).run();
    }
}
