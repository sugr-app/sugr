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
     * One decoded {@code invoke()} call. {@code paramsJson} is {@code null} when the caller
     * omitted it ({@link #dispatch(String, String)} then defaults it to {@code "{}"}).
     * {@code origin} is the optional third element
     * {@code core}'s bootstrap script appends (the page's {@code location.origin}) -
     * {@code null} when the caller didn't send one. This class doesn't act on it; see
     * {@code core}'s {@code OriginPolicy}.
     */
    public record Request(String method, String paramsJson, String origin) {
    }

    /**
     * Decodes the raw JSON array the webview hands over for an {@code invoke()} call:
     * {@code [method, paramsJson?, origin?]}, where {@code method} must be a string and
     * {@code paramsJson}/{@code origin} must be strings or null/absent. The page controls
     * this text completely - {@code window.invoke(123)} or {@code window.invoke('x', {})}
     * are both one console line away - so anything else
     * fails with a {@link BridgeException} coded {@link BridgeException#BAD_REQUEST}, never with a
     * parser exception.
     */
    public static Request parseRequest(String requestJson) {
        List<Json> args;
        try {
            Json parsed = Json.parse(requestJson);
            if (!(parsed instanceof Json.Arr arr)) {
                throw new BridgeException(BridgeException.BAD_REQUEST, "invoke() request must be a JSON array");
            }
            args = arr.items();
        } catch (JsonException e) {
            throw new BridgeException(BridgeException.BAD_REQUEST, "Malformed invoke() request: " + e.getMessage());
        }
        if (args.isEmpty() || !(args.get(0) instanceof Json.Str method)) {
            throw new BridgeException(BridgeException.BAD_REQUEST, "invoke() requires a method name");
        }
        String params = optionalString(args, 1, "params");
        String origin = optionalString(args, 2, "origin");
        return new Request(method.value(), params, origin);
    }

    private static String optionalString(List<Json> args, int index, String what) {
        if (args.size() <= index || args.get(index).isNull()) {
            return null;
        }
        if (args.get(index) instanceof Json.Str s) {
            return s.value();
        }
        throw new BridgeException(BridgeException.BAD_REQUEST, "invoke() " + what + " must be a JSON-encoded string, got: "
                + args.get(index).encode());
    }

    /**
     * @param requestJson the JSON array [method, paramsJson] sent by invoke()
     * @return a future completing with the JSON result to send back to JS,
     *         or completing exceptionally with a {@link BridgeException} - never
     *         throws itself, even for malformed input (that's {@link BridgeException#BAD_REQUEST})
     */
    public CompletableFuture<String> dispatch(String requestJson) {
        Request request;
        try {
            request = parseRequest(requestJson);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(toBridgeException(t));
        }
        return dispatch(request.method(), request.paramsJson());
    }

    /** Like {@link #dispatch(String)}, for a request that's already been decoded (see {@link #parseRequest}). */
    public CompletableFuture<String> dispatch(String method, String paramsJson) {
        if (method == null) {
            return CompletableFuture.failedFuture(new BridgeException(BridgeException.BAD_REQUEST, "invoke() requires a method name"));
        }
        String params = paramsJson != null ? paramsJson : "{}";

        AsyncHandler asyncHandler = asyncHandlers.get(method);
        if (asyncHandler != null) {
            try {
                CompletableFuture<String> future = asyncHandler.handle(params);
                if (future == null) {
                    return CompletableFuture.failedFuture(
                            new BridgeException("Async handler for " + method + " returned no future"));
                }
                return future.handle((result, error) -> {
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
            return CompletableFuture.failedFuture(
                    new BridgeException("METHOD_NOT_FOUND", "No handler registered for method: " + method));
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
