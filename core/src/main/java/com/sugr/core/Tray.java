package com.sugr.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
 * <p>{@link #close} asks the host to dispose the {@code NotifyIcon} cleanly
 * (so the icon disappears immediately, no leftover "ghost" until Explorer
 * repaints) and only force-kills it as a fallback. The host also disposes
 * itself if its parent process (the app) exits without calling close.
 */
public final class Tray implements AutoCloseable {

    private final Process process;
    private final Path aliveMarker;

    private Tray(Process process, Path aliveMarker) {
        this.process = process;
        this.aliveMarker = aliveMarker;
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

            // The icon must be Dispose()d for it to vanish at once instead of ghosting until
            // Explorer repaints - a force-kill never does that. So the host watches a marker
            // file (close() deletes it) and the parent PID (gone => the app crashed or was
            // force-killed without calling close), and on either signal disposes the icon and
            // exits. Polled by a Timer on the UI thread - the thread the NotifyIcon lives on -
            // so nothing blocks Application.Run()'s message loop.
            Path marker = Files.createTempFile("sugr-tray-", ".alive");
            marker.toFile().deleteOnExit();
            String script = """
                    Add-Type -AssemblyName System.Windows.Forms
                    Add-Type -AssemblyName System.Drawing
                    $ProgressPreference = 'SilentlyContinue'
                    $parentPid = %d
                    $marker = %s
                    $icon = New-Object System.Windows.Forms.NotifyIcon
                    %s
                    $icon.Text = %s
                    $menu = New-Object System.Windows.Forms.ContextMenuStrip
                    %s
                    $icon.ContextMenuStrip = $menu
                    $icon.Visible = $true

                    $timer = New-Object System.Windows.Forms.Timer
                    $timer.Interval = 150
                    $timer.Add_Tick({
                        $done = -not (Test-Path -LiteralPath $marker)
                        if (-not $done -and $parentPid -gt 0) {
                            $done = -not (Get-Process -Id $parentPid -ErrorAction SilentlyContinue)
                        }
                        if ($done) {
                            $timer.Stop()
                            $icon.Visible = $false
                            $icon.Dispose()
                            [System.Windows.Forms.Application]::Exit()
                        }
                    })
                    $timer.Start()
                    [System.Windows.Forms.Application]::Run()
                    """.formatted(ProcessHandle.current().pid(), Os.psQuote(marker.toString()),
                            iconLine, Os.psQuote(tooltip), menuScript);

            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            // -WindowStyle Hidden suppresses powershell.exe's own console window - see
            // Dialogs' identical comment.
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile",
                    "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start();

            startClickReader(process, itemActions);
            return new Tray(process, marker);
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

    /**
     * Removes the tray icon: deletes the marker file the host watches so it disposes the
     * {@code NotifyIcon} cleanly (the icon disappears at once), waits briefly for it to
     * exit, and force-kills it only as a fallback.
     */
    @Override
    public void close() {
        try {
            Files.deleteIfExists(aliveMarker);
        } catch (IOException ignored) {
            // fall through - the parent-exit check in the host still fires eventually
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
