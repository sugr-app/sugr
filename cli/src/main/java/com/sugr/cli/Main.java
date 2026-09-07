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
        printSponsorNote();
        System.exit(exitCode);
    }

    /**
     * Printed once when a command finishes (dev, build, package, doctor, ...) - the same
     * ask as the README's Sponsor section. For a long-running command like {@code sugr
     * dev} that means it shows when the app is closed. Goes to stderr so it never mixes
     * into stdout that a script might be parsing (e.g. {@code sugr -v}).
     */
    private static void printSponsorNote() {
        System.err.println();
        System.err.println("If Sugr is useful to you or your company, please consider sponsoring the project:");
        System.err.println("  https://github.com/sugr-app/sugr");
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
