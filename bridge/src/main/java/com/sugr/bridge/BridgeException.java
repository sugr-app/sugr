package com.sugr.bridge;

/** Thrown when a bridge method is unknown, or a handler fails. Carries a message safe to send to JS. */
public final class BridgeException extends RuntimeException {

    public BridgeException(String message) {
        super(message);
    }

    public BridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
