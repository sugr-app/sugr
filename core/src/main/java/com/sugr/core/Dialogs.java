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
        return openFile(0, title, extensions);
    }

    /**
     * Like {@link #openFile(String, String...)}, owned by the given native window handle
     * (see {@link Window#nativeHandle}) - an owned dialog is grouped with its owner in the
     * taskbar/Alt-Tab instead of appearing as an unrelated window of its own. Ignored on
     * non-Windows platforms (falls back to {@link #openFile(String, String...)}'s behavior).
     */
    public static String openFile(long ownerHwnd, String title, String... extensions) {
        try {
            if (Os.isWindows()) {
                return runPowerShell(fileDialogScript("OpenFileDialog", ownerHwnd, title, extensions));
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
        return saveFile(0, title, extensions);
    }

    /** Like {@link #saveFile(String, String...)}, owned by the given native window handle - see {@link #openFile(long, String, String...)}. */
    public static String saveFile(long ownerHwnd, String title, String... extensions) {
        try {
            if (Os.isWindows()) {
                return runPowerShell(fileDialogScript("SaveFileDialog", ownerHwnd, title, extensions));
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
        showMessage(0, title, message);
    }

    /** Like {@link #showMessage(String, String)}, owned by the given native window handle - see {@link #openFile(long, String, String...)}. */
    public static void showMessage(long ownerHwnd, String title, String message) {
        try {
            if (Os.isWindows()) {
                String show = ownerHwnd == 0
                        // MessageBoxOptions.DefaultDesktopOnly (native MB_DEFAULT_DESKTOP_ONLY) is what
                        // keeps an unowned message box from getting its own taskbar button (it can't be
                        // combined with an owner - .NET throws if both are given, hence the branch).
                        ? "[System.Windows.Forms.MessageBox]::Show(%1$s, %2$s, [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::None, [System.Windows.Forms.MessageBoxDefaultButton]::Button1, [System.Windows.Forms.MessageBoxOptions]::DefaultDesktopOnly) | Out-Null"
                        : "[System.Windows.Forms.MessageBox]::Show($owner, %1$s, %2$s, [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::None) | Out-Null";
                runPowerShell(win32WindowHelper(ownerHwnd) + """
                        Add-Type -AssemblyName System.Windows.Forms
                        %s
                        """.formatted(show.formatted(psQuote(message), psQuote(title))));
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

    /** Shows a native Yes/No confirm dialog; returns true if the user picked Yes. */
    public static boolean confirm(String title, String message) {
        return confirm(0, title, message);
    }

    /** Like {@link #confirm(String, String)}, owned by the given native window handle - see {@link #openFile(long, String, String...)}. */
    public static boolean confirm(long ownerHwnd, String title, String message) {
        try {
            if (Os.isWindows()) {
                // See showMessage's comment on MessageBoxOptions.DefaultDesktopOnly vs. an owner.
                String show = ownerHwnd == 0
                        ? "$result = [System.Windows.Forms.MessageBox]::Show(%1$s, %2$s, [System.Windows.Forms.MessageBoxButtons]::YesNo, [System.Windows.Forms.MessageBoxIcon]::None, [System.Windows.Forms.MessageBoxDefaultButton]::Button1, [System.Windows.Forms.MessageBoxOptions]::DefaultDesktopOnly)"
                        : "$result = [System.Windows.Forms.MessageBox]::Show($owner, %1$s, %2$s, [System.Windows.Forms.MessageBoxButtons]::YesNo, [System.Windows.Forms.MessageBoxIcon]::None)";
                String result = runPowerShell(win32WindowHelper(ownerHwnd) + """
                        Add-Type -AssemblyName System.Windows.Forms
                        %s
                        Write-Output $result
                        """.formatted(show.formatted(psQuote(message), psQuote(title))));
                return "Yes".equals(result);
            } else if (Os.isMac()) {
                String result = runAndTrim("osascript", "-e", "button returned of (display dialog "
                        + appleScriptQuote(message) + " with title " + appleScriptQuote(title)
                        + " buttons {\"No\", \"Yes\"} default button \"Yes\")");
                return "Yes".equals(result);
            } else {
                try {
                    Process p = new ProcessBuilder("zenity", "--question", "--title=" + title, "--text=" + message)
                            .redirectErrorStream(true)
                            .start();
                    p.getInputStream().readAllBytes();
                    return p.waitFor() == 0;
                } catch (IOException e) {
                    return true; // zenity missing - don't block the user on an unanswerable dialog
                }
            }
        } catch (IOException | InterruptedException e) {
            return true; // best-effort - don't block the user on a failed dialog
        }
    }

    private static String fileDialogScript(String dialogType, long ownerHwnd, String title, String[] extensions) {
        String showDialog = ownerHwnd == 0 ? "$dlg.ShowDialog()" : "$dlg.ShowDialog($owner)";
        return win32WindowHelper(ownerHwnd) + """
                Add-Type -AssemblyName System.Windows.Forms
                $dlg = New-Object System.Windows.Forms.%s
                $dlg.Title = %s
                $dlg.Filter = %s
                if (%s -eq [System.Windows.Forms.DialogResult]::OK) {
                    Write-Output $dlg.FileName
                }
                """.formatted(dialogType, psQuote(title), psQuote(windowsFilter(extensions)), showDialog);
    }

    /**
     * Wraps a raw HWND as an {@code IWin32Window} (via a minimal C# type - the built-in
     * {@code NativeWindow} isn't a good fit here since {@code AssignHandle}/{@code ReleaseHandle}
     * assume ownership of the handle's lifecycle, which this script doesn't have), so it can be
     * passed as a dialog's owner. Empty when ownerHwnd is 0 (no owner - the caller's script
     * shouldn't reference {@code $owner} in that case).
     */
    private static String win32WindowHelper(long ownerHwnd) {
        if (ownerHwnd == 0) {
            return "";
        }
        return """
                Add-Type -AssemblyName System.Windows.Forms
                Add-Type -TypeDefinition '
                using System;
                using System.Windows.Forms;
                public class SugrOwnerWindow : IWin32Window {
                    public IntPtr Handle { get; private set; }
                    public SugrOwnerWindow(IntPtr handle) { Handle = handle; }
                }
                ' -ReferencedAssemblies System.Windows.Forms.dll
                $owner = New-Object SugrOwnerWindow([IntPtr]%d)
                """.formatted(ownerHwnd);
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
        // -WindowStyle Hidden suppresses powershell.exe's own console window (which would
        // otherwise flash/appear in the taskbar alongside the app) - it has no effect on
        // the actual dialog windows the script itself creates (System.Windows.Forms owns
        // those as separate top-level windows).
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
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
