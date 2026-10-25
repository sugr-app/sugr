package com.sugr.core;

import com.sugr.bridge.Bridge;
import com.sugr.bridge.BridgeException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The framework's own {@code sugr.*} methods sit on the same {@link Bridge} as the
 * app's handlers, so malformed calls to them must fail the same way - a rejected
 * future with a stable code, never an exception thrown back into the upcall.
 */
class SugrBridgeTest {

    private static Bridge bridge() {
        Bridge bridge = new Bridge();
        SugrBridge.register(bridge);
        return bridge;
    }

    private static String errorCode(CompletableFuture<String> future) {
        ExecutionException e = assertThrows(ExecutionException.class, future::get);
        return assertInstanceOf(BridgeException.class, e.getCause()).code();
    }

    @Test
    void malformedRequestJsonIsBadRequest() {
        assertEquals(BridgeException.BAD_REQUEST, errorCode(bridge().dispatch("[\"sugr.updater.check\"")));
    }

    @Test
    void nonStringParamsIsBadRequest() {
        assertEquals(BridgeException.BAD_REQUEST, errorCode(bridge().dispatch("[\"sugr.updater.install\", {}]")));
    }

    @Test
    void updaterCallsWithoutAnUpdaterRejectInsteadOfThrowing() {
        // No packaged binary under test, so there's no shared updater.
        Bridge bridge = bridge();
        for (String method : new String[] {"sugr.updater.check", "sugr.updater.download",
                "sugr.updater.downloadAndInstall", "sugr.updater.install"}) {
            assertEquals("UPDATER_DISABLED", errorCode(bridge.dispatch("[\"" + method + "\", \"{not json\"]")), method);
        }
    }
}
