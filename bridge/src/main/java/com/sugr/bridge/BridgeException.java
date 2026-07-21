package com.sugr.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thrown when a bridge method is unknown, or a handler fails. Carries a
 * {@code code} (a short, stable string like {@code "NOT_FOUND"}) alongside
 * the human-readable message, and an optional structured {@code data}
 * payload - lets JS distinguish error kinds (e.g. "the user cancelled a
 * dialog" vs. "the handler actually failed") without parsing message text.
 * {@link #toJson()} is what actually crosses the wire; see
 * {@code core}'s {@code Application.onInvoke} for where that happens.
 */
public final class BridgeException extends RuntimeException {

    /** Default code for handler failures that didn't specify their own - see {@link Bridge#toBridgeException}. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private final String code;
    private final Json data;

    public BridgeException(String message) {
        this(INTERNAL_ERROR, message, null, null);
    }

    public BridgeException(String message, Throwable cause) {
        this(INTERNAL_ERROR, message, null, cause);
    }

    public BridgeException(String code, String message) {
        this(code, message, null, null);
    }

    public BridgeException(String code, String message, Json data) {
        this(code, message, data, null);
    }

    private BridgeException(String code, String message, Json data, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.data = data;
    }

    public String code() {
        return code;
    }

    public Json data() {
        return data;
    }

    /** {@code {"code": ..., "message": ..., "data": ...}} - data is omitted entirely when null. */
    public Json toJson() {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("code", Json.of(code));
        fields.put("message", Json.of(getMessage()));
        if (data != null) {
            fields.put("data", data);
        }
        return Json.object(fields);
    }
}
