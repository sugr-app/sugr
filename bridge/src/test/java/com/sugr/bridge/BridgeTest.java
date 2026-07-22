package com.sugr.bridge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class BridgeTest {

    @Test
    void badRequestWhenNoMethodName() {
        Bridge bridge = new Bridge();
        BridgeException e = unwrap(bridge.dispatch("[]"));
        assertEquals("BAD_REQUEST", e.code());
    }

    @Test
    void badRequestWhenMethodNameIsNull() {
        Bridge bridge = new Bridge();
        BridgeException e = unwrap(bridge.dispatch("[null]"));
        assertEquals("BAD_REQUEST", e.code());
    }

    @Test
    void methodNotFoundWhenNothingRegistered() {
        Bridge bridge = new Bridge();
        BridgeException e = unwrap(bridge.dispatch("[\"missing\"]"));
        assertEquals("METHOD_NOT_FOUND", e.code());
    }

    @Test
    void dispatchesToRegisteredSyncHandler() throws Exception {
        Bridge bridge = new Bridge();
        bridge.register("greet", params -> "\"hi\"");
        String result = bridge.dispatch("[\"greet\", \"{}\"]").get();
        assertEquals("\"hi\"", result);
    }

    @Test
    void defaultsParamsToEmptyObjectWhenOmitted() throws Exception {
        Bridge bridge = new Bridge();
        String[] receivedParams = new String[1];
        bridge.register("greet", params -> {
            receivedParams[0] = params;
            return "null";
        });
        bridge.dispatch("[\"greet\"]").get();
        assertEquals("{}", receivedParams[0]);
    }

    @Test
    void syncHandlerThrowingPlainExceptionBecomesInternalError() {
        Bridge bridge = new Bridge();
        bridge.register("boom", params -> {
            throw new RuntimeException("oops");
        });
        BridgeException e = unwrap(bridge.dispatch("[\"boom\"]"));
        assertEquals(BridgeException.INTERNAL_ERROR, e.code());
        assertEquals("oops", e.getMessage());
    }

    @Test
    void syncHandlerThrowingBridgeExceptionIsPreservedAsIs() {
        Bridge bridge = new Bridge();
        bridge.register("cancelled", params -> {
            throw new BridgeException("DIALOG_CANCELLED", "no file was selected");
        });
        BridgeException e = unwrap(bridge.dispatch("[\"cancelled\"]"));
        assertEquals("DIALOG_CANCELLED", e.code());
        assertEquals("no file was selected", e.getMessage());
    }

    @Test
    void dispatchesToRegisteredAsyncHandler() throws Exception {
        Bridge bridge = new Bridge();
        bridge.registerAsync("slow", params -> CompletableFuture.completedFuture("\"done\""));
        String result = bridge.dispatch("[\"slow\"]").get();
        assertEquals("\"done\"", result);
    }

    @Test
    void asyncHandlerFailureBecomesBridgeException() {
        Bridge bridge = new Bridge();
        bridge.registerAsync("slow", params -> CompletableFuture.failedFuture(new RuntimeException("async fail")));
        BridgeException e = unwrap(bridge.dispatch("[\"slow\"]"));
        assertEquals(BridgeException.INTERNAL_ERROR, e.code());
        assertEquals("async fail", e.getMessage());
    }

    @Test
    void asyncHandlerTakesPriorityOverSyncHandlerForSameMethod() throws Exception {
        Bridge bridge = new Bridge();
        bridge.register("dual", params -> "\"sync\"");
        bridge.registerAsync("dual", params -> CompletableFuture.completedFuture("\"async\""));
        assertEquals("\"async\"", bridge.dispatch("[\"dual\"]").get());
    }

    /** Awaits the future and unwraps whatever BridgeException ends up in the exception chain. */
    private static BridgeException unwrap(CompletableFuture<String> future) {
        ExecutionException outer = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class, future::get);
        Throwable cause = outer.getCause();
        while (cause != null && !(cause instanceof BridgeException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, "expected a BridgeException somewhere in the cause chain");
        return (BridgeException) cause;
    }
}
