package com.sugr.bridge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal JSON-RPC-style dispatcher. Frontend calls a single JS-side
 * `invoke(method, params)` function (see @sugr/runtime); this class routes
 * the call to the handler registered under that method name and returns its
 * JSON result. The transport (webview_bind/webview_return) lives in the
 * `core` module - this class knows nothing about webview.
 */
public final class Bridge {

    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Map<String, AsyncHandler> asyncHandlers = new ConcurrentHashMap<>();

    /** handler receives the raw JSON params string and returns a raw JSON result string. */
    public void register(String method, Handler handler) {
        handlers.put(method, handler);
    }

    /** Like {@link #register}, but for methods whose result isn't ready immediately (e.g. backed by CompletableFuture). */
    public void registerAsync(String method, AsyncHandler handler) {
        asyncHandlers.put(method, handler);
    }

    /**
     * @param requestJson the JSON array [method, paramsJson] sent by invoke()
     * @return a future completing with the JSON result to send back to JS,
     *         or completing exceptionally with a {@link BridgeException}
     */
    public CompletableFuture<String> dispatch(String requestJson) {
        List<String> args = Json.parseStringArray(requestJson);
        if (args.isEmpty() || args.get(0) == null) {
            return CompletableFuture.failedFuture(new BridgeException("invoke() requires a method name"));
        }
        String method = args.get(0);
        String params = args.size() > 1 && args.get(1) != null ? args.get(1) : "{}";

        AsyncHandler asyncHandler = asyncHandlers.get(method);
        if (asyncHandler != null) {
            try {
                return asyncHandler.handle(params).handle((result, error) -> {
                    if (error == null) {
                        return result;
                    }
                    throw toBridgeException(error);
                });
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(toBridgeException(t));
            }
        }

        Handler handler = handlers.get(method);
        if (handler == null) {
            return CompletableFuture.failedFuture(new BridgeException("No handler registered for method: " + method));
        }
        try {
            return CompletableFuture.completedFuture(handler.handle(params));
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(toBridgeException(t));
        }
    }

    private static BridgeException toBridgeException(Throwable t) {
        if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof BridgeException e) {
            return e;
        }
        return new BridgeException(t.getMessage() == null ? t.toString() : t.getMessage(), t);
    }

    /**
     * Throws Throwable (not just Exception) because handlers typically call
     * java.lang.foreign.MethodHandle.invoke(), which declares Throwable.
     */
    @FunctionalInterface
    public interface Handler {
        String handle(String paramsJson) throws Throwable;
    }

    @FunctionalInterface
    public interface AsyncHandler {
        CompletableFuture<String> handle(String paramsJson);
    }
}
