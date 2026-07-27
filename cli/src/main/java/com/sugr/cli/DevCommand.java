package com.sugr.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * `sugr dev` - runs the Vite dev server and the Java app together (HMR +
 * Java restart-on-change). See DevRuntime for the actual loop - `sugr debug`
 * reuses it with JDWP/browser wiring added.
 */
@Command(name = "dev", description = "Run the Vite dev server and the Java app together (HMR + Java restart-on-change)")
final class DevCommand implements Callable<Integer> {

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

    @Option(names = {"-e", "--env"}, description = "Environment (dev, staging, prod)", defaultValue = "dev")
    String env;

    @Override
    public Integer call() throws Exception {
        GradleProjectLocator.Result located = GradleProjectLocator.resolve(gradleTask, gradleDir, "run");
        return new DevRuntime(frontendDir, javaSrcDir, located, List.of(), Map.of(), env).run();
    }
}
