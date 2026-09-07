package com.sugr.examples.sqlclient;

import com.sugr.core.Menu;
import com.sugr.core.Tray;
import com.sugr.core.Window;

/**
 * The app's system tray icon. Created once at startup and kept for the whole
 * app run - "Window &gt; Minimize to tray" just hides the window
 * ({@link Window#minimizeToTray()}), so without a tray icon already sitting
 * there the window would vanish with no way back. The tray's "Show" item is
 * that way back.
 */
final class AppTray {

    private AppTray() {
    }

    /**
     * Creates the tray icon for {@code window}, or returns {@code null} on
     * non-Windows platforms (where {@link Tray#create} is a no-op). The caller
     * should close the returned tray on shutdown (a {@code Runtime} shutdown
     * hook) so its hosting process doesn't outlive the app.
     *
     * <p>Passes a {@code null} icon path for a stock system icon: the example
     * only ships an {@code icon.png}, and {@code Tray}'s {@code System.Drawing.Icon}
     * needs a {@code .ico}.
     */
    static Tray create(Window window) {
        Menu trayMenu = new Menu()
                .item("Show sugr - SQL client", window::show)
                .separator()
                .item("Quit", () -> System.exit(0));
        return Tray.create(null, "sugr - SQL client", trayMenu);
    }
}
