package com.sugr.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A system tray icon with a right-click context menu. Implemented by hosting
 * a small PowerShell + {@code System.Windows.Forms.NotifyIcon} script as a
 * persistent background process, rather than binding {@code shell32.dll}'s
 * {@code Shell_NotifyIconW} directly via FFM - its {@code NOTIFYICONDATAW}
 * struct is even larger and more failure-prone to hand-lay-out than
 * {@code OPENFILENAMEW} (see {@link Dialogs}' javadoc for why that one was
 * ruled out the same way). Windows only for now - {@link #create} returns
 * null elsewhere.
 *
 * <p>The context menu only supports flat items and separators, not nested
 * submenus (unlike {@link Menu} used for a window's menu bar) - a scoped-down
 * MVP, not a technical limit of the approach.
 *
 * <p>{@link #close} kills the hosting process outright rather than asking it
 * to dispose the icon cleanly first - Windows Explorer usually clears the
 * resulting "ghost" icon on its next redraw/hover, a minor known quirk (see
 * docs/guide/window.md).
 */
public final class Tray implements AutoCloseable {

    private final Process process;

    private Tray(Process process) {
        this.process = process;
    }

    /**
     * Creates a tray icon with the given tooltip and context menu, or returns null on
     * non-Windows platforms. {@code iconPath} may be null to use a stock system icon.
     */
    public static Tray create(String iconPath, String tooltip, Menu menu) {
        if (!Os.isWindows()) {
            return null;
        }
        try {
            Map<Integer, Runnable> itemActions = new ConcurrentHashMap<>();
            AtomicInteger nextId = new AtomicInteger(0);
            StringBuilder menuScript = new StringBuilder();
            for (Menu.Entry entry : menu.entries()) {
                switch (entry) {
                    case Menu.Item item -> {
                        int id = nextId.getAndIncrement();
                        itemActions.put(id, item.action());
                        menuScript.append("$i = $menu.Items.Add(").append(Os.psQuote(item.label())).append(")\n");
                        menuScript.append("$i.Tag = ").append(id).append("\n");
                        // [Console]::WriteLine writes directly to the raw stdout stream, unlike
                        // Write-Output - which goes through PowerShell's pipeline/formatting system
                        // and doesn't reliably reach a redirected pipe when called from inside a
                        // .NET event handler callback (as opposed to the script's main body).
                        menuScript.append("$i.add_Click({ [Console]::WriteLine(\"CLICK:$($this.Tag)\") })\n");
                    }
                    case Menu.Separator ignored ->
                            menuScript.append("$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator)) | Out-Null\n");
                    case Menu.Submenu ignored ->
                            System.err.println("[sugr] Tray context menus don't support nested submenus yet - skipping one");
                }
            }

            // null iconPath falls back to a stock system icon, so the tray API is usable
            // without requiring an app to ship its own .ico file.
            String iconLine = iconPath != null
                    ? "$icon.Icon = New-Object System.Drawing.Icon(%s)".formatted(Os.psQuote(iconPath))
                    : "$icon.Icon = [System.Drawing.SystemIcons]::Application";

            String script = """
                    Add-Type -AssemblyName System.Windows.Forms
                    Add-Type -AssemblyName System.Drawing
                    $ProgressPreference = 'SilentlyContinue'
                    $icon = New-Object System.Windows.Forms.NotifyIcon
                    %s
                    $icon.Text = %s
                    $menu = New-Object System.Windows.Forms.ContextMenuStrip
                    %s
                    $icon.ContextMenuStrip = $menu
                    $icon.Visible = $true
                    [System.Windows.Forms.Application]::Run()
                    """.formatted(iconLine, Os.psQuote(tooltip), menuScript);

            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            // -WindowStyle Hidden suppresses powershell.exe's own console window - see
            // Dialogs' identical comment.
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile",
                    "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start();

            startClickReader(process, itemActions);
            return new Tray(process);
        } catch (IOException e) {
            System.err.println("[sugr] failed to create system tray icon:");
            e.printStackTrace();
            return null;
        }
    }

    private static void startClickReader(Process process, Map<Integer, Runnable> itemActions) {
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("CLICK:")) {
                        try {
                            int id = Integer.parseInt(line.substring("CLICK:".length()).trim());
                            Runnable action = itemActions.get(id);
                            if (action != null) {
                                action.run();
                            }
                        } catch (RuntimeException e) {
                            System.err.println("[sugr] tray menu item action failed:");
                            e.printStackTrace();
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        });
    }

    /** Removes the tray icon. */
    @Override
    public void close() {
        process.destroy();
    }
}
