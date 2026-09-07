package com.sugr.examples.sqlclient;

import com.sugr.bridge.Bind;
import com.sugr.core.Application;
import com.sugr.core.Dialogs;
import com.sugr.core.Window;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin @Bind facade over {@link Window}'s window-control API (min/max/close, fullscreen,
 * always-on-top, dark title bar) so frontend code can trigger these directly instead of
 * only through the native menu - min/max/close/drag themselves are always fully native
 * (see {@link Window#setDarkTitleBar}), this is just a convenience surface for the rest.
 * The {@link Window} handle is only available after the app builder finishes, so it's
 * stashed here by {@code Main}'s onReady callback - menu actions already do the same
 * dance with {@code AppMenu}'s appRef.
 */
final class WindowControls {

    private final AtomicReference<Window> windowRef = new AtomicReference<>();
    private final AtomicReference<Application> appRef = new AtomicReference<>();

    /** Called by {@code Main} once the {@link Window} exists. */
    void setWindow(Window window) {
        windowRef.set(window);
    }

    /** Called by {@code Main} once the {@link Application} exists (for menu-like actions). */
    void setApplication(Application app) {
        appRef.set(app);
    }

    private Window window() {
        Window window = windowRef.get();
        if (window == null) {
            throw new IllegalStateException("Window not ready yet");
        }
        return window;
    }

    @Bind
    void minimize() {
        window().minimize();
    }

    @Bind
    void maximize() {
        window().maximize();
    }

    @Bind
    void restore() {
        window().restore();
    }

    @Bind
    void close() {
        try {
            window().close();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Bind
    void reload() {
        window().reload();
    }

    @Bind
    void quit() {
        System.exit(0);
    }

    @Bind
    void loadDbFile() {
        Application app = appRef.get();
        if (app == null) {
            throw new IllegalStateException("Application not ready yet");
        }
        AppMenu.loadDbFile(app);
    }

    @Bind
    void about() {
        Dialogs.showMessage(window().nativeHandle(), "About",
                "sugr - SQL client\n\nA small SQLite client demonstrating the sugr framework.");
    }

    @Bind
    void toggleFullscreen() {
        window().setFullscreen(!window().isMaximized());
    }

    @Bind
    void toggleAlwaysOnTop() {
        window().setAlwaysOnTop(true);
    }

    @Bind
    void setDarkTitleBar(boolean dark) {
        window().setDarkTitleBar(dark);
    }

    @Bind
    boolean isDarkTitleBar() {
        return window().isDarkTitleBar();
    }

    @Bind
    boolean isMaximized() {
        return window().isMaximized();
    }

    /** Called by a custom HTML title bar's {@code mousedown} handler to forward the drag natively. */
    @Bind
    void startDrag() {
        window().startDrag();
    }

    /** Called whenever the HTML maximize button's rect changes, to keep its snap overlay in sync. */
    @Bind
    void setMaxButtonBounds(int x, int y, int width, int height) {
        window().reportMaxButtonBounds(x, y, width, height);
    }

    /** Queried on mount so the frontend's own title bar starts in sync with the native menu's toggle state. */
    @Bind
    boolean isCustomTitleBar() {
        return window().isCustomTitleBar();
    }
}
