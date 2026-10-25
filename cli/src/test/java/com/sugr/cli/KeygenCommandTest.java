package com.sugr.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeygenCommandTest {

    private static final Path KEY = Path.of("keys", "app.key");

    @Test
    void windowsInstructionsUsePowerShellSyntax() {
        String text = KeygenCommand.publishInstructions(true, KEY, true);

        assertTrue(text.contains("$env:" + SigningKeys.KEY_ENV + " = \"" + KEY + "\""), text);
        assertTrue(text.contains("$env:" + SigningKeys.KEY_PASSWORD_ENV + " = "), text);
        assertFalse(text.contains("\\\n"), "no POSIX line continuations in PowerShell");
    }

    @Test
    void posixInstructionsUseInlineAssignments() {
        String text = KeygenCommand.publishInstructions(false, KEY, false);

        assertTrue(text.startsWith("  " + SigningKeys.KEY_ENV + "=" + KEY + " \\\n"), text);
        assertFalse(text.contains(SigningKeys.KEY_PASSWORD_ENV), "an unencrypted key needs no passphrase");
        assertFalse(text.contains("$env:"));
    }

    @Test
    void thePassphraseIsNoLongerACommandLineOption() {
        // Passing it as an argument would leave it in shell history and process listings.
        int exit = new CommandLine(new KeygenCommand()).setErr(new java.io.PrintWriter(java.io.Writer.nullWriter()))
                .execute("--password", "hunter2");
        assertNotEquals(0, exit);
        assertEquals(2, exit, "picocli rejects the unknown option before anything runs");
    }
}
