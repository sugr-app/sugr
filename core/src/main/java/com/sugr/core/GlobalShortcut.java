package com.sugr.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * System-wide keyboard shortcuts - fire a {@link Runnable} even when the app window isn't
 * focused. Windows only for now (see {@link #register}) - like {@link Tray}, this hosts a
 * persistent hidden PowerShell process (a {@code System.Windows.Forms.Form} whose
 * {@code WndProc} catches {@code WM_HOTKEY}), but unlike {@link Tray} - which builds its
 * whole menu upfront and never talks to the process again - registrations happen anytime,
 * so this writes {@code REGISTER}/{@code UNREGISTER} commands to the child process's stdin
 * on demand. One shared process serves every registration in the JVM; each
 * {@link #register} call gets its own id and its own {@link AutoCloseable} handle that
 * only unregisters that one hotkey - the shared process itself lives until {@link #closeAll}
 * is called (or the JVM exits).
 */
public final class GlobalShortcut {

    private static final int MOD_ALT = 0x0001;
    private static final int MOD_CONTROL = 0x0002;
    private static final int MOD_SHIFT = 0x0004;
    private static final int MOD_WIN = 0x0008;

    private static final Map<String, Integer> NAMED_KEYS = Map.ofEntries(
            Map.entry("SPACE", 0x20),
            Map.entry("ENTER", 0x0D),
            Map.entry("RETURN", 0x0D),
            Map.entry("ESCAPE", 0x1B),
            Map.entry("ESC", 0x1B),
            Map.entry("TAB", 0x09),
            Map.entry("BACKSPACE", 0x08),
            Map.entry("DELETE", 0x2E),
            Map.entry("LEFT", 0x25),
            Map.entry("UP", 0x26),
            Map.entry("RIGHT", 0x27),
            Map.entry("DOWN", 0x28));

    private static volatile Shared shared;

    private GlobalShortcut() {
    }

    /**
     * Registers a system-wide keyboard shortcut, e.g. {@code "Ctrl+Shift+K"} - modifiers
     * ({@code Ctrl}/{@code Control}, {@code Alt}, {@code Shift}, {@code Win}/{@code Meta}/
     * {@code Cmd}) separated by {@code +}, ending in a single key (a letter, digit, {@code F1}-
     * {@code F24}, or one of a small set of named keys - {@code Space}, {@code Enter},
     * {@code Escape}, arrow keys, etc.). Returns {@code null} on non-Windows platforms.
     *
     * <p>The returned handle's {@code close()} unregisters just this one shortcut - it doesn't
     * shut down the shared background process (see {@link #closeAll}).
     */
    public static AutoCloseable register(String accelerator, Runnable action) {
        if (!Os.isWindows()) {
            return null;
        }
        int[] modsAndVk = parseAccelerator(accelerator);
        Shared s = sharedInstance();
        int id = s.nextId.getAndIncrement();
        s.actions.put(id, action);
        s.send("REGISTER:" + id + ":" + modsAndVk[0] + ":" + modsAndVk[1]);
        return () -> {
            s.actions.remove(id);
            s.send("UNREGISTER:" + id);
        };
    }

    /** Kills the shared background process, unregistering every shortcut at once. */
    public static void closeAll() {
        Shared s = shared;
        if (s != null) {
            s.process.destroy();
            shared = null;
        }
    }

    private static Shared sharedInstance() {
        Shared s = shared;
        if (s == null) {
            synchronized (GlobalShortcut.class) {
                s = shared;
                if (s == null) {
                    s = Shared.start();
                    shared = s;
                }
            }
        }
        return s;
    }

    /** Parses e.g. "Ctrl+Shift+K" into {modifiers, virtualKeyCode}. */
    private static int[] parseAccelerator(String accelerator) {
        String[] parts = accelerator.split("\\+");
        int mods = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].trim().toUpperCase(Locale.ROOT)) {
                case "CTRL", "CONTROL" -> mods |= MOD_CONTROL;
                case "ALT" -> mods |= MOD_ALT;
                case "SHIFT" -> mods |= MOD_SHIFT;
                case "WIN", "META", "CMD" -> mods |= MOD_WIN;
                default -> throw new IllegalArgumentException("Unknown modifier in accelerator: " + parts[i]);
            }
        }
        int vk = parseKey(parts[parts.length - 1].trim().toUpperCase(Locale.ROOT));
        return new int[] { mods, vk };
    }

    private static int parseKey(String key) {
        if (key.length() == 1) {
            char c = key.charAt(0);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                return c;
            }
        }
        if (key.matches("F([1-9]|1[0-9]|2[0-4])")) {
            return 0x70 + (Integer.parseInt(key.substring(1)) - 1);
        }
        Integer named = NAMED_KEYS.get(key);
        if (named != null) {
            return named;
        }
        throw new IllegalArgumentException("Unknown key in accelerator: " + key);
    }

    /** The one persistent hidden PowerShell process shared by every registered shortcut. */
    private static final class Shared {
        private final Process process;
        private final Map<Integer, Runnable> actions = new ConcurrentHashMap<>();
        private final AtomicInteger nextId = new AtomicInteger(0);
        private final Object stdinLock = new Object();

        private Shared(Process process) {
            this.process = process;
        }

        private static Shared start() {
            try {
                String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
                Process process = new ProcessBuilder("powershell.exe", "-NoProfile",
                        "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                        .redirectErrorStream(true)
                        .start();
                Shared s = new Shared(process);
                s.startHotkeyReader();
                return s;
            } catch (IOException e) {
                System.err.println("[sugr] failed to start global shortcut listener:");
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        private void send(String line) {
            synchronized (stdinLock) {
                try {
                    OutputStream stdin = process.getOutputStream();
                    stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                } catch (IOException ignored) {
                    // process may have exited
                }
            }
        }

        private void startHotkeyReader() {
            Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("HOTKEY:")) {
                            try {
                                int id = Integer.parseInt(line.substring("HOTKEY:".length()).trim());
                                Runnable action = actions.get(id);
                                if (action != null) {
                                    action.run();
                                }
                            } catch (RuntimeException e) {
                                System.err.println("[sugr] global shortcut action failed:");
                                e.printStackTrace();
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            });
        }
    }

    /**
     * A hidden message-only-ish form catches {@code WM_HOTKEY} via {@code WndProc} and prints
     * {@code "HOTKEY:<id>"} to stdout, same idiom {@link Tray} uses for menu clicks.
     *
     * <p>{@code REGISTER:<id>:<mods>:<vk>}/{@code UNREGISTER:<id>} commands are read off stdin
     * by a {@code System.Windows.Forms.Timer} ticking on the form's own UI thread - deliberately
     * <em>not</em> a separate {@code System.Threading.Thread} (an earlier version tried that and
     * silently never registered any hotkey): a PowerShell scriptblock is bound to the runspace
     * of the thread it was defined on, and invoking one from a genuinely different OS thread -
     * as opposed to {@code Tray}'s click handler, which the .NET event system already dispatches
     * back onto the UI thread - isn't reliable. Polling via a {@code Timer} keeps every callback
     * (the tick handler here, {@code WndProc} above) running on the one thread that's already
     * pumping the message loop, so {@code RegisterHotKey}/{@code UnregisterHotKey} can be called
     * directly with no cross-thread marshaling at all.
     */
    private static final String SCRIPT = """
            $ProgressPreference = 'SilentlyContinue'
            Add-Type -AssemblyName System.Windows.Forms

            Add-Type -ReferencedAssemblies System.Windows.Forms -TypeDefinition '
            using System;
            using System.Runtime.InteropServices;
            using System.Windows.Forms;

            public class SugrHotkeyForm : Form {
                [DllImport("user32.dll")]
                public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);
                [DllImport("user32.dll")]
                public static extern bool UnregisterHotKey(IntPtr hWnd, int id);

                protected override void WndProc(ref Message m) {
                    const int WM_HOTKEY = 0x0312;
                    if (m.Msg == WM_HOTKEY) {
                        Console.WriteLine("HOTKEY:" + m.WParam.ToInt32());
                    }
                    base.WndProc(ref m);
                }
            }
            '

            $form = New-Object SugrHotkeyForm
            $form.WindowState = [System.Windows.Forms.FormWindowState]::Minimized
            $form.ShowInTaskbar = $false
            $form.Opacity = 0
            $form.Add_Shown({ $form.Hide() })
            $form.Show()

            $stdinTimer = New-Object System.Windows.Forms.Timer
            $stdinTimer.Interval = 50
            $stdinTimer.Add_Tick({
                while ([Console]::In.Peek() -ne -1) {
                    $line = [Console]::In.ReadLine()
                    if ($line -eq $null) { break }
                    if ($line.StartsWith("REGISTER:")) {
                        $parts = $line.Substring(9).Split(':')
                        $id = [int]$parts[0]; $mods = [uint32]$parts[1]; $vk = [uint32]$parts[2]
                        [SugrHotkeyForm]::RegisterHotKey($form.Handle, $id, $mods, $vk) | Out-Null
                    } elseif ($line.StartsWith("UNREGISTER:")) {
                        $id = [int]$line.Substring(11)
                        [SugrHotkeyForm]::UnregisterHotKey($form.Handle, $id) | Out-Null
                    }
                }
            })
            $stdinTimer.Start()

            [System.Windows.Forms.Application]::Run($form)
            """;
}
