package com.sugr.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The `sugr` CLI (Milestone 3.2-3.3, plus Phase 4a's `package` command).
 */
@Command(
        name = "sugr",
        version = "sugr 0.1.0-dev",
        description = "Run and build sugr apps.",
        subcommands = {DoctorCommand.class, DevCommand.class, DebugCommand.class, BuildCommand.class, InitCommand.class, PackageCommand.class}
)
public final class Main implements Runnable {

    // Not mixinStandardHelpOptions - that hardcodes -V/--version, and -v reads more
    // naturally (lowercase, matches most other CLIs) than shouting -V.
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean helpRequested;

    @Option(names = {"-v", "--version"}, versionHelp = true, description = "Print version information and exit.")
    boolean versionRequested;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
