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
        // A shutdown hook, not a plain call after execute(), so it prints on Ctrl+C too
        // (which never lets execute() return) - not just on a clean finish.
        Runtime.getRuntime().addShutdownHook(new Thread(Main::printSponsorNote));
        System.exit(new CommandLine(new Main()).execute(args));
    }

    /**
     * Printed once when the CLI exits, however it exits - a command finishing (dev, build,
     * package, doctor, ...) or Ctrl+C during a long-running one like {@code sugr dev}. The
     * same ask as the README's Sponsor section. Goes to stderr so it never mixes into
     * stdout that a script might be parsing (e.g. {@code sugr -v}).
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
