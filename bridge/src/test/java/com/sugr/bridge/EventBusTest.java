package com.sugr.bridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventBusTest {

    @Test
    void dispatchesToRegisteredListener() {
        EventBus bus = new EventBus();
        List<String> received = new ArrayList<>();
        bus.on("ping", received::add);

        bus.dispatch("ping", "{\"from\":\"js\"}");

        assertEquals(List.of("{\"from\":\"js\"}"), received);
    }

    @Test
    void dispatchesToEveryListenerRegisteredForTheSameEvent() {
        EventBus bus = new EventBus();
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        bus.on("ping", first::add);
        bus.on("ping", second::add);

        bus.dispatch("ping", "payload");

        assertEquals(List.of("payload"), first);
        assertEquals(List.of("payload"), second);
    }

    @Test
    void listenersForOneEventDoNotFireForAnother() {
        EventBus bus = new EventBus();
        List<String> pingReceived = new ArrayList<>();
        bus.on("ping", pingReceived::add);

        bus.dispatch("pong", "payload");

        assertTrue(pingReceived.isEmpty());
    }

    @Test
    void dispatchingAnEventWithNoListenersIsANoop() {
        EventBus bus = new EventBus();
        bus.dispatch("nobody-listening", "payload");
        // no exception - that's the whole assertion
    }

    @Test
    void aThrowingListenerDoesNotStopTheOthersOrEscapeDispatch() {
        EventBus bus = new EventBus();
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        bus.on("ping", before::add);
        bus.on("ping", payload -> {
            throw new IllegalStateException("listener blew up");
        });
        bus.on("ping", after::add);

        bus.dispatch("ping", "payload");

        assertEquals(List.of("payload"), before);
        assertEquals(List.of("payload"), after);
    }

    @Test
    void offRemovesTheGivenListenerOnly() {
        EventBus bus = new EventBus();
        List<String> removed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        Consumer<String> removedListener = removed::add;
        bus.on("ping", removedListener);
        bus.on("ping", kept::add);

        bus.off("ping", removedListener);
        bus.dispatch("ping", "payload");

        assertTrue(removed.isEmpty());
        assertEquals(List.of("payload"), kept);
    }

    @Test
    void offForAnUnregisteredListenerOrEventIsANoop() {
        EventBus bus = new EventBus();
        List<String> received = new ArrayList<>();
        bus.on("ping", received::add);

        bus.off("ping", payload -> { });
        bus.off("never-registered", received::add);
        bus.dispatch("ping", "payload");

        assertEquals(List.of("payload"), received);
    }

    @Test
    void listenerCanBeReAddedAfterOff() {
        EventBus bus = new EventBus();
        List<String> received = new ArrayList<>();
        Consumer<String> listener = received::add;
        bus.on("ping", listener);
        bus.off("ping", listener);
        bus.on("ping", listener);

        bus.dispatch("ping", "payload");

        assertEquals(List.of("payload"), received);
    }
}
