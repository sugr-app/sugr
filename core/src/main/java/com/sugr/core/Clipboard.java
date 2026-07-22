package com.sugr.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Text clipboard read/write. Windows uses PowerShell's built-in
 * {@code Get-Clipboard}/{@code Set-Clipboard} cmdlets (verified working);
 * macOS uses {@code pbcopy}/{@code pbpaste}; Linux uses {@code xclip}
 * (falling back to {@code xsel} if it's not installed) - see {@link Dialogs}'
 * javadoc for why this module shells out here instead of binding each OS's
 * native clipboard API via FFM. Only the Windows path has been exercised.
 */
public final class Clipboard {

    private Clipboard() {
    }

    /** Reads the current clipboard text, or null if it's empty/unavailable/not text. */
    public static String read() {
        try {
            if (Os.isWindows()) {
                return runPowerShell("Get-Clipboard -Raw");
            } else if (Os.isMac()) {
                return runCapture("pbpaste");
            } else {
                String out = runCapture("xclip", "-selection", "clipboard", "-o");
                return out != null ? out : runCapture("xsel", "--clipboard", "--output");
            }
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** Writes text to the clipboard. */
    public static void write(String text) {
        try {
            if (Os.isWindows()) {
                runPowerShell("Set-Clipboard -Value " + psQuote(text));
            } else if (Os.isMac()) {
                runWithStdin(text, "pbcopy");
            } else if (!runWithStdin(text, "xclip", "-selection", "clipboard")) {
                runWithStdin(text, "xsel", "--clipboard", "--input");
            }
        } catch (IOException | InterruptedException ignored) {
            // best-effort - a failed clipboard write isn't worth surfacing as an exception
        }
    }

    private static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String runPowerShell(String script) throws IOException, InterruptedException {
        // Without this, module auto-loading (Get-Clipboard/Set-Clipboard live in a
        // module, not a built-in cmdlet) emits a "Preparing modules for first use"
        // progress record - serialized as CLIXML text mixed straight into stdout
        // when there's no interactive host, corrupting whatever we're trying to read.
        String withPreference = "$ProgressPreference = 'SilentlyContinue'\n" + script;
        String encoded = Base64.getEncoder().encodeToString(withPreference.getBytes(StandardCharsets.UTF_16LE));
        // -WindowStyle Hidden suppresses powershell.exe's own console window - see Dialogs'
        // identical comment.
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .start();
        String output = readAll(p);
        p.waitFor();
        return output.isEmpty() ? null : stripTrailingNewline(output);
    }

    private static String runCapture(String... command) throws IOException, InterruptedException {
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            return null; // tool not installed
        }
        String output = readAll(p);
        int exit = p.waitFor();
        return exit == 0 && !output.isEmpty() ? stripTrailingNewline(output) : null;
    }

    /** Returns false if the tool couldn't even be started (e.g. not installed), true otherwise. */
    private static boolean runWithStdin(String text, String... command) throws InterruptedException {
        Process p;
        try {
            p = new ProcessBuilder(command).start();
        } catch (IOException e) {
            return false;
        }
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // process may have already exited
        }
        p.waitFor();
        return true;
    }

    private static String readAll(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            int c;
            while ((c = reader.read()) != -1) {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    private static String stripTrailingNewline(String s) {
        String out = s;
        while (out.endsWith("\n") || out.endsWith("\r")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
