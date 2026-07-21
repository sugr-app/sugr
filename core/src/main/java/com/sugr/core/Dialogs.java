package com.sugr.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Native file/message dialogs. On Windows this shells out to a short
 * PowerShell + {@code System.Windows.Forms} script rather than binding
 * {@code comdlg32.dll}'s {@code GetOpenFileNameW}/{@code GetSaveFileNameW}
 * directly via FFM - those take a pointer to the ~150-byte {@code OPENFILENAMEW}
 * struct, and getting every field's offset/alignment exactly right isn't
 * something that can be verified without a way to test each byte, unlike
 * {@code core}'s other FFM bindings (webview, sqlite3) which were checked
 * against a running app end to end. A wrong struct layout would corrupt
 * memory rather than fail loudly, so the safer trade is a slower
 * process-spawn per call in exchange for correctness. macOS ({@code osascript})
 * and Linux ({@code zenity}) shell out for the same reason and follow the
 * same shape, but - like {@code PackageCommand}'s per-OS packaging code -
 * are unverified so far; only the Windows path has been exercised.
 */
public final class Dialogs {

    private Dialogs() {
    }

    /** Shows a native "open file" dialog; returns the picked path, or null if cancelled. */
    public static String openFile(String title, String... extensions) {
        try {
            if (Os.isWindows()) {
                return runPowerShell(openFileScript(title, extensions));
            } else if (Os.isMac()) {
                return runAndTrim("osascript", "-e", "POSIX path of (choose file with prompt "
                        + appleScriptQuote(title) + ")");
            } else {
                return runAndTrim("zenity", "--file-selection", "--title=" + title);
            }
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** Shows a native "save file" dialog; returns the picked path, or null if cancelled. */
    public static String saveFile(String title, String... extensions) {
        try {
            if (Os.isWindows()) {
                return runPowerShell(saveFileScript(title, extensions));
            } else if (Os.isMac()) {
                return runAndTrim("osascript", "-e", "POSIX path of (choose file name with prompt "
                        + appleScriptQuote(title) + ")");
            } else {
                return runAndTrim("zenity", "--file-selection", "--save", "--title=" + title);
            }
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** Shows a native message box/alert. */
    public static void showMessage(String title, String message) {
        try {
            if (Os.isWindows()) {
                runPowerShell("""
                        Add-Type -AssemblyName System.Windows.Forms
                        [System.Windows.Forms.MessageBox]::Show(%s, %s) | Out-Null
                        """.formatted(psQuote(message), psQuote(title)));
            } else if (Os.isMac()) {
                runAndTrim("osascript", "-e",
                        "display alert " + appleScriptQuote(title) + " message " + appleScriptQuote(message));
            } else {
                runAndTrim("zenity", "--info", "--title=" + title, "--text=" + message);
            }
        } catch (IOException | InterruptedException ignored) {
            // best-effort - a failed message box isn't worth surfacing as an exception
        }
    }

    private static String openFileScript(String title, String[] extensions) {
        return """
                Add-Type -AssemblyName System.Windows.Forms
                $dlg = New-Object System.Windows.Forms.OpenFileDialog
                $dlg.Title = %s
                $dlg.Filter = %s
                if ($dlg.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                    Write-Output $dlg.FileName
                }
                """.formatted(psQuote(title), psQuote(windowsFilter(extensions)));
    }

    private static String saveFileScript(String title, String[] extensions) {
        return """
                Add-Type -AssemblyName System.Windows.Forms
                $dlg = New-Object System.Windows.Forms.SaveFileDialog
                $dlg.Title = %s
                $dlg.Filter = %s
                if ($dlg.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                    Write-Output $dlg.FileName
                }
                """.formatted(psQuote(title), psQuote(windowsFilter(extensions)));
    }

    private static String windowsFilter(String[] extensions) {
        if (extensions == null || extensions.length == 0) {
            return "All files (*.*)|*.*";
        }
        StringBuilder patterns = new StringBuilder();
        for (int i = 0; i < extensions.length; i++) {
            if (i > 0) {
                patterns.append(';');
            }
            patterns.append("*.").append(extensions[i]);
        }
        return patterns + " files (" + patterns + ")|" + patterns;
    }

    /** Wraps a string as a single-quoted PowerShell literal (only ' needs escaping - doubled). */
    private static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** Wraps a string as a double-quoted AppleScript literal. */
    private static String appleScriptQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Runs a PowerShell script via -EncodedCommand (base64 UTF-16LE) - sidesteps all shell/argument quoting. */
    private static String runPowerShell(String script) throws IOException, InterruptedException {
        // See Clipboard.runPowerShell's javadoc comment - suppresses module-loading
        // progress records that otherwise leak into stdout as CLIXML noise.
        String withPreference = "$ProgressPreference = 'SilentlyContinue'\n" + script;
        String encoded = Base64.getEncoder().encodeToString(withPreference.getBytes(StandardCharsets.UTF_16LE));
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .start();
        String output = readAll(p);
        p.waitFor();
        String trimmed = output.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String runAndTrim(String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = readAll(p);
        int exit = p.waitFor();
        String trimmed = output.strip();
        return exit == 0 && !trimmed.isEmpty() ? trimmed : null;
    }

    private static String readAll(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
