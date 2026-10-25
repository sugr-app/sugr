package com.sugr.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * `sugr keygen` - creates the Ed25519 release keypair that auto-updates are
 * built on.
 *
 * <p>The private key signs each release; the public key is pasted into
 * {@code sugr.config.json} and compiled into every build, where it's the only
 * thing that decides whether a downloaded update is allowed to replace the
 * running application. Run this once per app and keep the key file safe: there
 * is deliberately no way to recover or re-issue it, so losing it means users on
 * old versions can never be updated again (they'd have to reinstall).
 *
 * <p>The passphrase is never a command-line argument - that would leave it in
 * shell history and in every process listing. It comes from
 * {@link #PASSWORD_ENV} for scripted use, or an interactive prompt.
 */
@Command(name = "keygen", description = "Generate the Ed25519 signing key used to publish app updates")
final class KeygenCommand implements Callable<Integer> {

    /** Env var a script can set instead of answering the passphrase prompt (empty means unencrypted). */
    static final String PASSWORD_ENV = "SUGR_KEY_PASSWORD";

    @Option(names = {"-o", "--out"}, description = "Where to write the key file "
            + "(default: ~/.sugr/<app name>.key)")
    String outPath;

    @Option(names = {"-f", "--force"}, description = "Overwrite an existing key file")
    boolean force;

    @Override
    public Integer call() throws Exception {
        SugrConfig config = SugrConfig.load(Path.of(".").toAbsolutePath().normalize());
        Path target = outPath != null
                ? Path.of(outPath).toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.home"), ".sugr", config.nameOrDefault() + ".key");

        if (Files.exists(target) && !force) {
            // Overwriting a release key silently would orphan every user already
            // running a build that trusts the old public key.
            System.err.println("[sugr] " + target + " already exists.");
            System.err.println("[sugr] Overwriting it means existing installs can never be updated again - "
                    + "pass --force if that's really what you want.");
            return 1;
        }

        String fromEnv = System.getenv(PASSWORD_ENV);
        String passphrase = fromEnv != null ? fromEnv : promptForPassphrase();
        if (passphrase == null) {
            return 1;
        }

        SigningKeys.Generated generated = SigningKeys.generate(passphrase);
        Files.createDirectories(target.getParent());
        Files.writeString(target, generated.keyFileContent());
        restrictToOwner(target);

        System.out.println();
        System.out.println("[sugr] private key written to " + target);
        if (passphrase.isEmpty()) {
            System.out.println("[sugr] note: the key file is unencrypted - keep it out of version control.");
        }
        System.out.println();
        System.out.println("Add this to sugr.config.json:");
        System.out.println();
        System.out.println("  \"updater\": {");
        System.out.println("    \"endpoints\": [\"https://your-host/latest.json\"],");
        System.out.println("    \"pubkey\": \"" + generated.publicKeyBase64() + "\"");
        System.out.println("  }");
        System.out.println();
        System.out.println("Then, to publish a release:");
        System.out.println();
        System.out.print(publishInstructions(ProcessUtil.isWindows(), target, !passphrase.isEmpty()));
        System.out.println();
        System.out.println("Back the key file up somewhere safe. If you lose it you cannot ship updates");
        System.out.println("to anyone already running this app - they would have to reinstall by hand.");
        return 0;
    }

    /**
     * Reads a passphrase without echoing it, twice, to catch typos - a mistyped
     * passphrase on a key that's only used weeks later at release time is an
     * unpleasant way to find out.
     */
    private static String promptForPassphrase() {
        Console console = System.console();
        // Since JDK 22 System.console() can be non-null with redirected
        // streams, so ask whether there's really a terminal to prompt on.
        if (console == null || !console.isTerminal()) {
            System.err.println("[sugr] no interactive console to ask for a passphrase.");
            System.err.println("[sugr] set " + PASSWORD_ENV + " to the passphrase instead (an empty value "
                    + "writes an unencrypted key), or run sugr keygen from a terminal.");
            return null;
        }
        char[] first = console.readPassword("Passphrase for the key file (empty for none): ");
        char[] second = console.readPassword("Repeat passphrase: ");
        String a = first == null ? "" : new String(first);
        String b = second == null ? "" : new String(second);
        java.util.Arrays.fill(first == null ? new char[0] : first, '\0');
        java.util.Arrays.fill(second == null ? new char[0] : second, '\0');
        if (!a.equals(b)) {
            System.err.println("[sugr] the passphrases don't match.");
            return null;
        }
        return a;
    }

    /**
     * The commands to run {@code sugr package} with this key, in the syntax of
     * the shell the user most likely has: PowerShell on Windows (where
     * {@code VAR=value cmd} doesn't parse at all), POSIX elsewhere.
     */
    static String publishInstructions(boolean windows, Path keyFile, boolean encrypted) {
        StringBuilder out = new StringBuilder();
        if (windows) {
            out.append("  $env:").append(SigningKeys.KEY_ENV).append(" = \"").append(keyFile).append("\"\n");
            if (encrypted) {
                out.append("  $env:").append(SigningKeys.KEY_PASSWORD_ENV).append(" = \"...\"\n");
            }
            out.append("  sugr package --base-url https://your-host\n");
        } else {
            out.append("  ").append(SigningKeys.KEY_ENV).append("=").append(keyFile).append(" \\\n");
            if (encrypted) {
                out.append("  ").append(SigningKeys.KEY_PASSWORD_ENV).append("=... \\\n");
            }
            out.append("  sugr package --base-url https://your-host\n");
        }
        return out.toString();
    }

    /**
     * Best-effort lock-down to the current user: {@code chmod 600} on POSIX;
     * on Windows, drop inherited ACEs and grant only this user, since a key
     * written outside the profile (via {@code --out}) would otherwise inherit
     * whatever the folder allows.
     */
    private static void restrictToOwner(Path file) {
        if (ProcessUtil.isWindows()) {
            restrictWindowsAcl(file);
            return;
        }
        try {
            Files.setPosixFilePermissions(file, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // Not a POSIX filesystem - nothing to tighten.
        }
    }

    private static void restrictWindowsAcl(Path file) {
        String user = System.getenv("USERNAME");
        if (user == null || user.isBlank()) {
            System.err.println("[sugr] warning: couldn't tighten the key file's permissions (USERNAME is unset).");
            return;
        }
        try {
            Process process = new ProcessBuilder("icacls", file.toString(), "/inheritance:r",
                    "/grant:r", user + ":F")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (process.waitFor() != 0) {
                System.err.println("[sugr] warning: icacls couldn't restrict " + file
                        + " to " + user + " - check its permissions by hand.");
            }
        } catch (java.io.IOException e) {
            System.err.println("[sugr] warning: couldn't run icacls to restrict " + file + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
