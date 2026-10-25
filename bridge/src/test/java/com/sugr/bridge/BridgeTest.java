package com.sugr.bridge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void badRequestWhenRequestIsNotJson() {
        Bridge bridge = new Bridge();
        BridgeException e = unwrap(bridge.dispatch("not json"));
        assertEquals(BridgeException.BAD_REQUEST, e.code());
    }

    @Test
    void badRequestWhenRequestIsANumberInsteadOfAnArray() {
        Bridge bridge = new Bridge();
        BridgeException e = unwrap(bridge.dispatch("123"));
        assertEquals(BridgeException.BAD_REQUEST, e.code());
    }

    @Test
    void badRequestWhenMethodNameIsANumber() {
        // window.invoke(123)
        Bridge bridge = new Bridge();
        BridgeException e = unwrap(bridge.dispatch("[123]"));
        assertEquals(BridgeException.BAD_REQUEST, e.code());
    }

    @Test
    void badRequestWhenParamsIsAnObjectInsteadOfAJsonString() {
        // window.invoke('greet', {}) - params must be pre-encoded JSON text
        Bridge bridge = new Bridge();
        bridge.register("greet", params -> "\"hi\"");
        BridgeException e = unwrap(bridge.dispatch("[\"greet\", {}]"));
        assertEquals(BridgeException.BAD_REQUEST, e.code());
    }

    @Test
    void badRequestWhenParamsIsAnArrayInsteadOfAJsonString() {
        Bridge bridge = new Bridge();
        bridge.register("greet", params -> "\"hi\"");
        BridgeException e = unwrap(bridge.dispatch("[\"greet\", [1, 2]]"));
        assertEquals(BridgeException.BAD_REQUEST, e.code());
    }

    @Test
    void badRequestWhenRequestHasAnUnterminatedString() {
        Bridge bridge = new Bridge();
        BridgeException e = unwrap(bridge.dispatch("[\"greet"));
        assertEquals(BridgeException.BAD_REQUEST, e.code());
    }

    @Test
    void badRequestWhenRequestIsNestedTenThousandDeep() {
        Bridge bridge = new Bridge();
        BridgeException e = unwrap(bridge.dispatch("[".repeat(10_000) + "]".repeat(10_000)));
        assertEquals(BridgeException.BAD_REQUEST, e.code());
    }

    @Test
    void dispatchNeverThrowsForNullRequest() {
        Bridge bridge = new Bridge();
        BridgeException e = unwrap(bridge.dispatch(null));
        assertEquals(BridgeException.BAD_REQUEST, e.code());
    }

    @Test
    void parseRequestReadsMethodParamsAndOrigin() {
        Bridge.Request request = Bridge.parseRequest("[\"greet\", \"[1]\", \"http://127.0.0.1:1234\"]");
        assertEquals("greet", request.method());
        assertEquals("[1]", request.paramsJson());
        assertEquals("http://127.0.0.1:1234", request.origin());
    }

    @Test
    void parseRequestLeavesParamsAndOriginNullWhenOmitted() {
        Bridge.Request request = Bridge.parseRequest("[\"greet\", null]");
        assertNull(request.paramsJson());
        assertNull(request.origin());
    }

    @Test
    void badRequestWhenOriginIsNotAString() {
        BridgeException e = assertThrows(BridgeException.class,
                () -> Bridge.parseRequest("[\"greet\", \"[]\", 42]"));
        assertEquals(BridgeException.BAD_REQUEST, e.code());
    }

    @Test
    void trailingOriginIsIgnoredByDispatch() throws Exception {
        Bridge bridge = new Bridge();
        String[] receivedParams = new String[1];
        bridge.register("greet", params -> {
            receivedParams[0] = params;
            return "\"hi\"";
        });
        assertEquals("\"hi\"", bridge.dispatch("[\"greet\", \"[1]\", \"http://localhost:5173\"]").get());
        assertEquals("[1]", receivedParams[0]);
    }

    @Test
    void asyncHandlerReturningNullFutureBecomesInternalError() {
        Bridge bridge = new Bridge();
        bridge.registerAsync("broken", params -> null);
        BridgeException e = unwrap(bridge.dispatch("[\"broken\"]"));
        assertEquals(BridgeException.INTERNAL_ERROR, e.code());
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
