package com.sugr.examples.sqlclient;

import com.sugr.bridge.Json;
import com.sugr.core.Application;
import com.sugr.core.Dialogs;
import com.sugr.core.Menu;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The app's native menu bar - File (load a DB file, reload, quit) and Help
 * (about). Split out of Main so its wiring doesn't crowd the app's startup
 * sequence.
 */
final class AppMenu {

    private AppMenu() {
    }

    /**
     * appRef is read lazily (menu item actions run long after the menu itself
     * is built, once the user actually clicks something) - see Main, which
     * fills it in via onReady() once the Application instance exists.
     */
    static Menu build(AtomicReference<Application> appRef) {
        return new Menu()
                .submenu("File", new Menu()
                        .item("Load DB file...", () -> loadDbFile(appRef.get()))
                        .separator()
                        .item("Reload app", () -> appRef.get().mainWindow().reload())
                        .separator()
                        .item("Quit", () -> System.exit(0)))
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
     */
    private static void loadDbFile(Application app) {
        // Owned by the main window (see Dialogs' javadoc) - otherwise this and the About
        // dialog above would each get their own unrelated taskbar entry instead of being
        // grouped with the app's.
        String path = Dialogs.openFile(app.mainWindow().nativeHandle(), "Choose a SQLite database", "db", "sqlite");
        if (path != null) {
            app.mainWindow().emit("menu-load-db", Json.quote(path));
        }
    }
}
