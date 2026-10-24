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

    /**
     * Removes {@code listener} from {@code event} - the same instance passed to
     * {@link #on} (one registration per call, if it was added more than once).
     * A no-op if it isn't registered.
     */
    public void off(String event, Consumer<String> listener) {
        listeners.computeIfPresent(event, (k, registered) -> {
            registered.remove(listener);
            return registered.isEmpty() ? null : registered;
        });
    }

    /**
     * Calls every listener for {@code event}, in registration order. Listeners are
     * isolated from each other: one that throws is logged and skipped, the rest
     * still run, and nothing propagates to the caller - which is the webview's
     * "emit" upcall, where an escaping exception would take the JVM down.
     */
    public void dispatch(String event, String payloadJson) {
        for (Consumer<String> listener : listeners.getOrDefault(event, List.of())) {
            try {
                listener.accept(payloadJson);
            } catch (Throwable t) {
                System.err.println("[sugr] listener for event \"" + event + "\" threw:");
                t.printStackTrace();
            }
        }
    }
}
