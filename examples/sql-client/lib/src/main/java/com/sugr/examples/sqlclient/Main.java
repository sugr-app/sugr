package com.sugr.examples.sqlclient;

import com.sugr.core.AppConfig;
import com.sugr.core.Application;
import com.sugr.core.Frontend;
import com.sugr.core.GlobalShortcut;
import com.sugr.core.Notification;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Example app for the sugr core/bridge library: a small SQLite client.
 * Everything below is app-specific wiring - the library itself only knows
 * about Application/Frontend/Bridge. All actual logic (connect/query/list
 * tables/pick file/clipboard) lives in SqlService's @Bind methods, generated
 * into SqlServiceBridge by the annotation processor; the menu bar (and its
 * launch-at-login toggle) lives in AppMenu.
 */
public final class Main {

    private static final String APP_ID = "sugr.examples.sqlclient";
    private static final String APP_NAME = "sugr - SQL client";

    public static void main(String[] args) throws Throwable {
        // application.properties + application-<env>.properties (env from SUGR_ENV,
        // defaulting to "prod") - see docs/guide/environments.md.
        AppConfig config = AppConfig.load("SUGR_ENV", "prod");

        SqlService sqlService = new SqlService();
        SqlServiceBridge generatedBridge = new SqlServiceBridge(sqlService);

        String exePath = ProcessHandle.current().info().command().orElse(null);

        // Brands connect()'s toasts with this app's own name/icon instead of "Windows
        // PowerShell" - see Notification.registerApp's javadoc. Must run before the first
        // Notification.show() call to take effect.
        Notification.registerApp(APP_ID, APP_NAME, exePath, sqlService.iconPath());

        // Menu item actions need the Application instance (to reload/emit),
        // which only exists once the builder finishes - stash it via onReady().
        AtomicReference<Application> appRef = new AtomicReference<>();

        Application.Builder builder = Application.builder()
                .title(config.getOrDefault("app.windowTitle", APP_NAME))
                .size(700, 700)
                .frontend(Frontend.auto())
                .menu(AppMenu.build(appRef, APP_ID, APP_NAME, exePath))
                .onReady(app -> {
                    appRef.set(app);
                    // Same "load a DB file" action as the File menu item, but reachable even
                    // when the window isn't focused - see GlobalShortcut's javadoc. The
                    // returned handle is intentionally not closed: it should live for the
                    // whole app run, same as the menu item it mirrors.
                    GlobalShortcut.register("Ctrl+Shift+L", () -> AppMenu.loadDbFile(app));
                });

        generatedBridge.bindTo(builder);
        builder.run();

        sqlService.close();
    }
}
