package com.sugr.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Registers/unregisters this app to launch automatically when the user logs into the OS.
 * Windows uses the HKCU {@code Run} registry key (via PowerShell - no COM interop needed,
 * unlike {@link Notification#registerApp}'s Start Menu shortcut, since a startup entry
 * doesn't need an AppUserModelID). macOS uses a {@code LaunchAgents} plist plus
 * {@code launchctl}; Linux uses an XDG autostart {@code .desktop} file - both written
 * directly via {@code java.nio.file} rather than shelling out, since they're plain file
 * I/O in a well-known location, not an OS API call. Only the Windows path has been
 * exercised so far, same caveat as {@link Dialogs}/{@link Clipboard}/{@link Notification}.
 */
public final class AutoStart {

    private static final String WINDOWS_RUN_KEY = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private AutoStart() {
    }

    /**
     * Enables autostart for this app - idempotent, safe to call every app startup.
     *
     * @param appId a stable, unique identifier (e.g. {@code "sugr.examples.sqlclient"}) - used
     *              as the registry value name (Windows), plist filename/{@code Label} (macOS),
     *              and {@code .desktop} filename (Linux)
     * @param displayName shown as the Linux {@code .desktop} entry's name (unused elsewhere)
     * @param exePath the executable to launch at login
     */
    public static void enable(String appId, String displayName, String exePath) {
        try {
            if (Os.isWindows()) {
                runPowerShell("Set-ItemProperty -Path '" + WINDOWS_RUN_KEY + "' -Name " + Os.psQuote(appId)
                        + " -Value " + Os.psQuote("\"" + exePath + "\""));
            } else if (Os.isMac()) {
                Path plist = macLaunchAgentPath(appId);
                Files.createDirectories(plist.getParent());
                Files.writeString(plist, macPlist(appId, exePath));
                // best-effort unload first so a repeat enable() picks up a changed exePath
                runAndIgnore("launchctl", "unload", plist.toString());
                runAndIgnore("launchctl", "load", "-w", plist.toString());
            } else {
                Path desktopFile = linuxAutostartPath(appId);
                Files.createDirectories(desktopFile.getParent());
                Files.writeString(desktopFile, linuxDesktopEntry(displayName, exePath));
            }
        } catch (IOException e) {
            System.err.println("[sugr] AutoStart.enable failed:");
            e.printStackTrace();
        }
    }

    /** Disables autostart for this app - idempotent, safe to call even if never enabled. */
    public static void disable(String appId) {
        try {
            if (Os.isWindows()) {
                runPowerShell("Remove-ItemProperty -Path '" + WINDOWS_RUN_KEY + "' -Name " + Os.psQuote(appId)
                        + " -ErrorAction SilentlyContinue");
            } else if (Os.isMac()) {
                Path plist = macLaunchAgentPath(appId);
                runAndIgnore("launchctl", "unload", "-w", plist.toString());
                Files.deleteIfExists(plist);
            } else {
                Files.deleteIfExists(linuxAutostartPath(appId));
            }
        } catch (IOException e) {
            System.err.println("[sugr] AutoStart.disable failed:");
            e.printStackTrace();
        }
    }

    /**
     * Returns whether autostart is currently enabled for this app. On macOS/Linux this only
     * checks whether the plist/{@code .desktop} file exists - it doesn't parse
     * {@code launchctl list} or a session manager's state, but {@link #enable}/{@link #disable}
     * always keep the file and the registered state in sync.
     */
    public static boolean isEnabled(String appId) {
        try {
            if (Os.isWindows()) {
                String result = runPowerShell("if (Get-ItemProperty -Path '" + WINDOWS_RUN_KEY + "' -Name "
                        + Os.psQuote(appId) + " -ErrorAction SilentlyContinue) { 'TRUE' } else { 'FALSE' }");
                return "TRUE".equals(result);
            } else if (Os.isMac()) {
                return Files.exists(macLaunchAgentPath(appId));
            } else {
                return Files.exists(linuxAutostartPath(appId));
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static Path macLaunchAgentPath(String appId) {
        return Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", appId + ".plist");
    }

    private static Path linuxAutostartPath(String appId) {
        return Path.of(System.getProperty("user.home"), ".config", "autostart", appId + ".desktop");
    }

    private static String macPlist(String appId, String exePath) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>%s</string>
                    </array>
                    <key>RunAtLoad</key>
                    <true/>
                </dict>
                </plist>
                """.formatted(escapeXml(appId), escapeXml(exePath));
    }

    private static String linuxDesktopEntry(String displayName, String exePath) {
        return """
                [Desktop Entry]
                Type=Application
                Name=%s
                Exec=%s
                X-GNOME-Autostart-enabled=true
                """.formatted(displayName, exePath);
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void runAndIgnore(String... command) {
        try {
            new ProcessBuilder(command).redirectErrorStream(true).start().waitFor();
        } catch (IOException | InterruptedException ignored) {
            // best-effort
        }
    }

    /** Runs a PowerShell script via -EncodedCommand (base64 UTF-16LE) - sidesteps all shell/argument quoting. */
    private static String runPowerShell(String script) throws IOException {
        String withPreference = "$ProgressPreference = 'SilentlyContinue'\n" + script;
        String encoded = Base64.getEncoder().encodeToString(withPreference.getBytes(StandardCharsets.UTF_16LE));
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .start();
        return drain(p);
    }

    private static String drain(Process p) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        String trimmed = out.toString().strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
