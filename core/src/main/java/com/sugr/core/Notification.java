package com.sugr.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A native, transient notification/toast - fire-and-forget, unlike {@link Tray} which hosts
 * a persistent process. On Windows this shells out to PowerShell + WinRT's
 * {@code Windows.UI.Notifications.ToastNotificationManager} (a real Action Center toast, not
 * the legacy {@code NotifyIcon.ShowBalloonTip}) - see {@link #show(String, String)}'s javadoc
 * for the trade-off that comes with it. macOS ({@code osascript}) and Linux
 * ({@code notify-send}) follow the same shell-out shape as {@link Dialogs}/{@link Clipboard},
 * but are unverified so far - only the Windows path has been exercised.
 */
public final class Notification {

    private static final String POWERSHELL_AUMID = "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe";

    /** Set by {@link #registerApp} once a Start Menu shortcut carrying this AUMID exists - see its javadoc. */
    private static volatile String registeredAppId;

    private Notification() {
    }

    /** Like {@link #show(String, String, String)} with no icon. */
    public static void show(String title, String message) {
        show(title, message, null);
    }

    /**
     * Shows a native, transient notification/toast with the given title and message, and
     * optionally a custom icon (a local file path to a PNG/JPG - not an {@code .ico}; on
     * Windows this maps to the toast's {@code appLogoOverride} image, which doesn't need any
     * AUMID/shortcut registration).
     *
     * <p>On Windows, showing a toast at all via {@code ToastNotificationManager} requires an
     * AppUserModelID that's registered with the OS - normally via a Start Menu shortcut
     * carrying a matching {@code System.AppUserModel.ID} property, which only exists for an
     * app installed via {@code sugr package}. Call {@link #registerApp} once at startup to get
     * one for {@code sugr dev}/a directly-run binary too; without it, this falls back to the
     * well-known, already-registered AUMID for Windows PowerShell itself, so toasts still work
     * everywhere - just showing "Windows PowerShell" as the sender name instead of the app's
     * own (the icon can still be overridden per-call via {@code iconPath} regardless).
     */
    public static void show(String title, String message, String iconPath) {
        try {
            if (Os.isWindows()) {
                runPowerShell(toastScript(title, message, iconPath));
            } else if (Os.isMac()) {
                runAndForget("osascript", "-e", "display notification " + appleScriptQuote(message)
                        + " with title " + appleScriptQuote(title));
            } else {
                runAndForget("notify-send", title, message);
            }
        } catch (IOException ignored) {
            // best-effort - a failed notification isn't worth surfacing as an exception
        }
    }

    /**
     * Registers this app's own AppUserModelID (Windows only, no-op elsewhere) so subsequent
     * {@link #show} calls brand toasts with {@code displayName}/{@code iconPath} instead of
     * "Windows PowerShell" - call this once at startup, before the first {@link #show}.
     *
     * <p>Creates a Start Menu shortcut (skipped if one already exists at that path) named
     * {@code displayName} - that's also literally where the toast's sender name comes from,
     * since Windows resolves an unpackaged app's toast branding by looking up a Start Menu
     * shortcut whose {@code System.AppUserModel.ID} property matches the AUMID passed to
     * {@code CreateToastNotifier}, and reads its display name/icon off of that same shortcut.
     * Setting that property requires COM interop ({@code IShellLinkW}/{@code IPropertyStore})
     * that a plain {@code New-Object -ComObject WScript.Shell} shortcut can't do - there's no
     * built-in cmdlet for it.
     *
     * @param appId a stable, unique identifier for this app (e.g. {@code "sugr.examples.sqlclient"})
     * @param displayName shown as the toast's sender name, and as the Start Menu shortcut's name
     * @param exePath the shortcut's target - informational (what running/pinning the Start
     *                Menu entry would launch), not required for toast branding to work
     * @param iconPath the shortcut's own icon (nullable) - separate from a per-{@link #show}
     *                  {@code iconPath}, which is an in-toast image, not the shortcut's icon
     */
    public static void registerApp(String appId, String displayName, String exePath, String iconPath) {
        if (!Os.isWindows()) {
            return;
        }
        try {
            String result = runPowerShell(registerAppScript(appId, displayName, exePath, iconPath));
            if ("OK".equals(result)) {
                registeredAppId = appId;
            } else {
                System.err.println("[sugr] Notification.registerApp failed, falling back to the default "
                        + "AUMID (toasts will show \"Windows PowerShell\" as the sender): " + result);
            }
        } catch (IOException e) {
            System.err.println("[sugr] Notification.registerApp failed to launch PowerShell:");
            e.printStackTrace();
        }
    }

    /**
     * Sets each {@code <text>} node's content (and, if given, the {@code appLogoOverride}
     * image's {@code src}) via the DOM API rather than interpolating title/message directly
     * into the XML template string: doing that would need manual XML-escaping ({@code &}/
     * {@code <}/{@code >}) done by hand, and risks the message text containing a {@code "@}
     * sequence that would truncate the surrounding PowerShell here-string. Setting content via
     * the DOM API handles escaping correctly regardless of content, and keeps title/message/
     * iconPath as plain {@code psQuote()}'d PowerShell string literals - the same escaping
     * every other {@code core} script already uses, no new quoting scheme.
     */
    private static String toastScript(String title, String message, String iconPath) {
        String iconStep = iconPath == null ? "" : """

                $imageUri = 'file:///' + %s.Replace('\\', '/')
                $image = $xml.CreateElement("image")
                $image.SetAttribute("placement", "appLogoOverride") | Out-Null
                $image.SetAttribute("src", $imageUri) | Out-Null
                $xml.GetElementsByTagName("binding").Item(0).AppendChild($image) | Out-Null
                """.formatted(psQuote(iconPath));

        String appId = registeredAppId != null ? psQuote(registeredAppId) : "'" + POWERSHELL_AUMID + "'";

        return """
                [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
                [Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
                [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null

                $titleText = %s
                $messageText = %s

                $xml = New-Object Windows.Data.Xml.Dom.XmlDocument
                $xml.LoadXml('<toast><visual><binding template="ToastGeneric"><text></text><text></text></binding></visual></toast>')
                $nodes = $xml.GetElementsByTagName("text")
                $nodes.Item(0).AppendChild($xml.CreateTextNode($titleText)) | Out-Null
                $nodes.Item(1).AppendChild($xml.CreateTextNode($messageText)) | Out-Null
                %s
                $toast = New-Object Windows.UI.Notifications.ToastNotification($xml)
                [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier(%s).Show($toast)
                """.formatted(psQuote(title), psQuote(message), iconStep, appId);
    }

    /**
     * COM interop is the only way to set a shortcut's {@code System.AppUserModel.ID} property -
     * {@code IShellLinkW}'s CLSID/IID and {@code IPropertyStore}'s IID below are long-stable
     * Windows Shell constants (unchanged since Windows 2000/XP-era shell32), and
     * {@code PKEY_AppUserModel_ID}'s {fmtid, pid} pair is the standard documented constant for
     * that property - all four appear identically across Microsoft's own toast-notification
     * samples and are not sugr-specific. {@code PropVariant} only implements the one variant
     * type (VT_LPWSTR = 31) actually needed here to set a string value.
     */
    private static String registerAppScript(String appId, String displayName, String exePath, String iconPath) {
        return """
                try {
                    $shortcutDir = Join-Path $env:APPDATA 'Microsoft\\Windows\\Start Menu\\Programs'
                    $shortcutPath = Join-Path $shortcutDir (%s + '.lnk')
                    if (-not (Test-Path $shortcutPath)) {
                        Add-Type -TypeDefinition '
                        using System;
                        using System.Runtime.InteropServices;
                        using System.Runtime.InteropServices.ComTypes;

                        [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
                        internal class CShellLink { }

                        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
                        internal interface IShellLinkW {
                            void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszFile, int cchMaxPath, IntPtr pfd, uint fFlags);
                            void GetIDList(out IntPtr ppidl);
                            void SetIDList(IntPtr pidl);
                            void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszName, int cchMaxName);
                            void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
                            void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszDir, int cchMaxPath);
                            void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
                            void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszArgs, int cchMaxPath);
                            void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
                            void GetHotkey(out short pwHotkey);
                            void SetHotkey(short wHotkey);
                            void GetShowCmd(out int piShowCmd);
                            void SetShowCmd(int iShowCmd);
                            void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder pszIconPath, int cchIconPath, out int piIcon);
                            void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
                            void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, uint dwReserved);
                            void Resolve(IntPtr hwnd, uint fFlags);
                            void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
                        }

                        [StructLayout(LayoutKind.Sequential, Pack = 4)]
                        internal struct PropertyKey {
                            public Guid fmtid;
                            public int pid;
                            public PropertyKey(Guid fmtid, int pid) { this.fmtid = fmtid; this.pid = pid; }
                        }

                        [StructLayout(LayoutKind.Sequential)]
                        internal struct PropVariant : IDisposable {
                            public ushort vt;
                            public ushort wReserved1, wReserved2, wReserved3;
                            public IntPtr p;
                            public static PropVariant FromString(string value) {
                                PropVariant pv = new PropVariant();
                                pv.vt = 31; // VT_LPWSTR
                                pv.p = Marshal.StringToCoTaskMemUni(value);
                                return pv;
                            }
                            public void Dispose() {
                                if (p != IntPtr.Zero) { Marshal.FreeCoTaskMem(p); p = IntPtr.Zero; }
                            }
                        }

                        [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
                        internal interface IPropertyStore {
                            void GetCount(out uint propertyCount);
                            void GetAt(uint index, out PropertyKey key);
                            void GetValue(ref PropertyKey key, out PropVariant value);
                            void SetValue(ref PropertyKey key, ref PropVariant value);
                            void Commit();
                        }

                        public static class SugrToastShortcut {
                            public static void CreateOrUpdate(string shortcutPath, string targetPath, string iconPath, string appId) {
                                var link = (IShellLinkW)new CShellLink();
                                link.SetPath(targetPath);
                                if (!string.IsNullOrEmpty(iconPath)) link.SetIconLocation(iconPath, 0);

                                var propStore = (IPropertyStore)link;
                                var appIdKey = new PropertyKey(new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), 5);
                                using (var pv = PropVariant.FromString(appId)) {
                                    var pvCopy = pv;
                                    propStore.SetValue(ref appIdKey, ref pvCopy);
                                }
                                propStore.Commit();

                                var persistFile = (System.Runtime.InteropServices.ComTypes.IPersistFile)link;
                                persistFile.Save(shortcutPath, true);
                            }
                        }
                        '
                        [SugrToastShortcut]::CreateOrUpdate($shortcutPath, %s, %s, %s)
                    }
                    Write-Output "OK"
                } catch {
                    Write-Output "ERROR: $_"
                }
                """.formatted(psQuote(displayName), psQuote(exePath == null ? "" : exePath),
                psQuote(iconPath == null ? "" : iconPath), psQuote(appId));
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
    private static String runPowerShell(String script) throws IOException {
        // See Clipboard.runPowerShell's javadoc comment - suppresses module-loading
        // progress records that otherwise leak into stdout as CLIXML noise.
        String withPreference = "$ProgressPreference = 'SilentlyContinue'\n" + script;
        String encoded = Base64.getEncoder().encodeToString(withPreference.getBytes(StandardCharsets.UTF_16LE));
        // -WindowStyle Hidden suppresses powershell.exe's own console window - see Dialogs'
        // identical comment.
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .start();
        return drain(p);
    }

    private static void runAndForget(String... command) throws IOException {
        Process p = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        drain(p);
    }

    /**
     * Reads a process's output to completion and returns it trimmed (or null if empty) -
     * whether or not the caller cares about the result, the stream still has to be drained so
     * the child process's stdout pipe never fills up and blocks it from exiting.
     */
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
