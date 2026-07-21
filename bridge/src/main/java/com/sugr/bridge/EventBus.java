package com.sugr.bridge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Java-side half of the two-way event bus. Holds listeners for events
 * emitted from JS (`events.emit()` in @sugr/runtime). The other direction
 * (Java -> JS) is `Application.emit()` in the `core` module, which doesn't
 * need a registry here - it just runs JS directly via webview_eval.
 */
public final class EventBus {

    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();

    /** listener receives the event's raw JSON payload. */
    public void on(String event, Consumer<String> listener) {
        listeners.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void dispatch(String event, String payloadJson) {
        for (Consumer<String> listener : listeners.getOrDefault(event, List.of())) {
            listener.accept(payloadJson);
        }
    }
}
