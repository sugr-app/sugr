package com.sugr.bridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
