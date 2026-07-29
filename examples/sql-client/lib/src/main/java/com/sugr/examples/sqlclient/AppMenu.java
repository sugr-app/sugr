package com.sugr.examples.sqlclient;

import com.sugr.core.Application;
import com.sugr.core.AutoStart;
import com.sugr.core.Dialogs;
import com.sugr.core.Menu;
import com.sugr.core.Notification;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The app's native menu bar - File (load a DB file, reload, quit), Settings
 * (launch-at-login toggle), and Help (about). Split out of Main so its
 * wiring doesn't crowd the app's startup sequence.
 */
final class AppMenu {

    private AppMenu() {
    }

    /**
     * appRef is read lazily (menu item actions run long after the menu itself
     * is built, once the user actually clicks something) - see Main, which
     * fills it in via onReady() once the Application instance exists.
     *
     * @param appId stable id passed straight through to {@link AutoStart} - same one
     *              {@code Main} already uses for {@code Notification.registerApp}
     * @param exePath the executable to launch at login - see {@link AutoStart#enable}
     */
    static Menu build(AtomicReference<Application> appRef, String appId, String displayName, String exePath) {
        return new Menu()
                .submenu("File", new Menu()
                        .item("Load DB file...", () -> loadDbFile(appRef.get()))
                        .separator()
                        .item("Reload app", () -> appRef.get().mainWindow().reload())
                        .separator()
                        .item("Quit", () -> System.exit(0)))
                .submenu("Settings", new Menu()
                        .item("Toggle launch at login", () -> toggleAutoStart(appId, displayName, exePath)))
                .submenu("Help", new Menu()
                        .item("About", () -> {
                            Application app = appRef.get();
                            Dialogs.showMessage(app.mainWindow().nativeHandle(), "About",
                                    "sugr - SQL client\n\nA small SQLite client demonstrating the sugr framework.");
                        }));
    }

    /**
     * Shows a native "open file" dialog and, if a file was picked, tells the
     * frontend about it via an event - the frontend owns actually connecting
     * (it needs to update its own state either way), this just supplies the path.
     *
     * <p>Package-private (not {@code private}) so {@code Main} can wire the same
     * action up to a {@code GlobalShortcut} - see its javadoc.
     */
    static void loadDbFile(Application app) {
        // Owned by the main window (see Dialogs' javadoc) - otherwise this and the About
        // dialog above would each get their own unrelated taskbar entry instead of being
        // grouped with the app's.
        String path = Dialogs.openFile(app.mainWindow().nativeHandle(), "Choose a SQLite database", "db", "sqlite");
        if (path != null) {
            new AppEventsEmitter(app.mainWindow()).menuLoadDb(path);
        }
    }

    /** Flips launch-at-login on/off (see {@link AutoStart}) and confirms the new state via a toast. */
    private static void toggleAutoStart(String appId, String displayName, String exePath) {
        boolean wasEnabled = AutoStart.isEnabled(appId);
        if (wasEnabled) {
            AutoStart.disable(appId);
        } else {
            AutoStart.enable(appId, displayName, exePath);
        }
        Notification.show("sugr - SQL client",
                "Launch at login: " + (wasEnabled ? "off" : "on"));
    }
}
