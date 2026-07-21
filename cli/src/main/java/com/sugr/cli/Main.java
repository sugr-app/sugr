package com.sugr.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The `sugr` CLI (Milestone 3.2-3.3). `package` (OS installers) is still
 * deferred - the rest (doctor/dev/build/init) automates what we did by hand
 * throughout Giai đoạn 1-3.
 */
@Command(
        name = "sugr",
        mixinStandardHelpOptions = true,
        version = "sugr 0.0.1",
        description = "Run and build sugr apps.",
        subcommands = {DoctorCommand.class, DevCommand.class, BuildCommand.class, InitCommand.class}
)
public final class Main implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
