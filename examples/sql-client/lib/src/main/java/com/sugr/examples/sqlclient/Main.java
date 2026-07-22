package com.sugr.examples.sqlclient;

import com.sugr.core.Application;
import com.sugr.core.Frontend;
import com.sugr.core.Notification;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Example app for the sugr core/bridge library: a small SQLite client.
 * Everything below is app-specific wiring - the library itself only knows
 * about Application/Frontend/Bridge. All actual logic (connect/query/list
 * tables/pick file/clipboard) lives in SqlService's @Bind methods, generated
 * into SqlServiceBridge by the annotation processor; the menu bar lives in
 * AppMenu.
 */
public final class Main {

    public static void main(String[] args) throws Throwable {
        SqlService sqlService = new SqlService();
        SqlServiceBridge generatedBridge = new SqlServiceBridge(sqlService);

        // Brands connect()'s toasts with this app's own name/icon instead of "Windows
        // PowerShell" - see Notification.registerApp's javadoc. Must run before the first
        // Notification.show() call to take effect.
        Notification.registerApp("sugr.examples.sqlclient", "sugr - SQL client",
                ProcessHandle.current().info().command().orElse(null), sqlService.iconPath());

        // Menu item actions need the Application instance (to reload/emit),
        // which only exists once the builder finishes - stash it via onReady().
        AtomicReference<Application> appRef = new AtomicReference<>();

        Application.Builder builder = Application.builder()
                .title("sugr - SQL client")
                .size(700, 700)
                .frontend(Frontend.auto())
                .menu(AppMenu.build(appRef))
                .onReady(appRef::set);

        generatedBridge.bindTo(builder);
        builder.run();

        sqlService.close();
    }
}
