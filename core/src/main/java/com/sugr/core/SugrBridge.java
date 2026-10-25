package com.sugr.core;

import com.sugr.bridge.Bridge;
import com.sugr.bridge.BridgeException;
import com.sugr.bridge.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The framework's own bridge methods - the first namespace {@code sugr} owns
 * rather than the app author.
 *
 * <p>Names are dotted ({@code sugr.updater.check}). That can't collide with an
 * app's own bindings by construction: {@code @Bind} derives a method name from
 * a Java method name, which can never contain a dot, so the {@code sugr.}
 * prefix is reserved for free without any registry or check.
 *
 * <p>Registered on every window so a secondary window's frontend has the same
 * API as the main one. The updater itself is process-wide, so all windows see
 * the same check/download state.
 */
final class SugrBridge {

    private SugrBridge() {
    }

    /** Registers {@code sugr.app.*} and {@code sugr.updater.*} on a window's bridge. */
    static void register(Bridge bridge) {
        bridge.register("sugr.app.info", params -> {
            AppMetadata metadata = AppMetadata.get();
            Map<String, Json> info = new LinkedHashMap<>();
            info.put("name", Json.of(metadata.name()));
            info.put("version", Json.of(metadata.version().toString()));
            info.put("identifier", Json.of(metadata.identifier()));
            return Json.object(info).encode();
        });

        bridge.register("sugr.updater.isSupported", params -> String.valueOf(Updater.shared() != null));

        bridge.register("sugr.updater.currentVersion", params ->
                Json.quote(AppMetadata.get().version().toString()));

        bridge.registerAsync("sugr.updater.check", params -> {
            Updater updater = require();
            return updater.check().thenApply(info -> info == null ? "null" : info.toJson().encode());
        });

        bridge.registerAsync("sugr.updater.download", params -> {
            Updater updater = require();
            return updater.download().thenApply(ignored -> "null");
        });

        // install/relaunch end the process, so they're async on purpose: the
        // swap runs off the UI thread, the reply goes back to JS, and only then
        // does a separate thread exit. A sync handler would run on the UI
        // thread and call System.exit inside the webview's native callback.
        bridge.registerAsync("sugr.updater.downloadAndInstall", params -> {
            Updater updater = require();
            return updater.downloadAndApply().thenApply(applied -> {
                if (applied) {
                    Relauncher.relaunchSoon();
                }
                return "null";
            });
        });

        bridge.registerAsync("sugr.updater.install", params -> {
            Updater updater = require();
            return CompletableFuture.supplyAsync(() -> {
                updater.applyStagedUpdate();
                Relauncher.relaunchSoon();
                return "null";
            }, SugrBridge::offUiThread);
        });

        bridge.register("sugr.updater.isReadyToInstall", params ->
                String.valueOf(Updater.shared() != null && Updater.shared().isReadyToInstall()));

        bridge.registerAsync("sugr.updater.relaunch", params -> {
            Relauncher.relaunchSoon();
            return CompletableFuture.completedFuture("null");
        });
    }

    private static void offUiThread(Runnable task) {
        Thread thread = new Thread(task, "sugr-updater-install");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Bridges updater events into a window's JS listeners, and kicks off the
     * automatic startup check when the app asked for one.
     *
     * <p>Called once the window can actually evaluate JS - emitting before then
     * would drop the event on the floor.
     */
    static void attach(Window window, boolean isMain) {
        Updater updater = Updater.shared();
        if (updater == null) {
            return;
        }
        updater.addEventSink(window::emit);
        if (isMain) {
            UpdateFlow.startAutoCheck(window, updater);
        }
    }

    /**
     * The updater, or a {@link BridgeException} the frontend can branch on.
     * Rejecting with a stable code beats returning null: a frontend that calls
     * {@code check()} in a dev build should be able to tell "updates are off
     * here" apart from "you're up to date".
     */
    private static Updater require() {
        Updater updater = Updater.shared();
        if (updater == null) {
            throw new BridgeException("UPDATER_DISABLED",
                    "Updates aren't available in this build - see the updater section of sugr.config.json");
        }
        return updater;
    }
}
