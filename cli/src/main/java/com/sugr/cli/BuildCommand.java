package com.sugr.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * `sugr build` - production build: `pnpm build` the frontend, copy dist/ into
 * the Java module's embedded resources, then run the Gradle build. Automates
 * the manual "pnpm build && rm -rf resources/frontend && cp -r dist/* ..."
 * sequence used throughout Giai đoạn 1-3. Does not invoke native-image - that
 * still needs a Developer Command Prompt on Windows (see giai-doan-0's notes
 * on why), run it separately for now.
 */
@Command(name = "build", description = "Build the frontend, embed it, and build the Java app")
final class BuildCommand implements Callable<Integer> {

    @Option(names = {"-f", "--frontend"}, description = "Path to the frontend/ dir", defaultValue = "frontend")
    String frontendDir;

    @Option(names = {"-r", "--resources"}, description = "Where to copy built frontend assets",
            defaultValue = "lib/src/main/resources/frontend")
    String resourcesDir;

    @Option(names = {"-t", "--task"}, description = "Gradle task to run after embedding assets "
            + "(auto-detected by default - see GradleProjectLocator)")
    String gradleTask;

    @Option(names = {"-g", "--gradle-dir"}, description = "Directory to run gradle from "
            + "(auto-detected by default - see GradleProjectLocator)")
    String gradleDir;

    @Override
    public Integer call() throws Exception {
        Path frontend = Path.of(frontendDir).toAbsolutePath().normalize();
        Path dist = frontend.resolve("dist");
        Path resources = Path.of(resourcesDir).toAbsolutePath().normalize();

        if (!Files.isDirectory(frontend)) {
            System.err.println("[sugr] frontend directory not found: " + frontend);
            return 1;
        }

        System.out.println("[sugr] building frontend in " + frontend + " ...");
        int viteExit = runInherited(frontend, ProcessUtil.shellCommand("pnpm", "build"));
        if (viteExit != 0) {
            System.err.println("[sugr] frontend build failed (exit " + viteExit + ")");
            return viteExit;
        }
        if (!Files.isDirectory(dist)) {
            System.err.println("[sugr] expected build output at " + dist + " but it doesn't exist");
            return 1;
        }

        System.out.println("[sugr] embedding frontend assets into " + resources + " ...");
        FileTrees.deleteRecursively(resources);
        Files.createDirectories(resources);
        FileTrees.copyRecursively(dist, resources, s -> s);

        GradleProjectLocator.Result located = GradleProjectLocator.resolve(gradleTask, gradleDir, "build");
        System.out.println("[sugr] running gradle " + located.task() + " (from " + located.gradleDir() + ") ...");
        int gradleExit = runInherited(located.gradleDir(), ProcessUtil.shellCommand("gradle", located.task(), "--console=plain"));
        if (gradleExit != 0) {
            System.err.println("[sugr] gradle build failed (exit " + gradleExit + ")");
            return gradleExit;
        }

        System.out.println("[sugr] build complete.");
        return 0;
    }

    private static int runInherited(Path dir, java.util.List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        return p.waitFor();
    }
}
