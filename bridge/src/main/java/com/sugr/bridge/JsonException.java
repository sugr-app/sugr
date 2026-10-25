package com.sugr.bridge;

/**
 * Thrown by {@link Json#parse} for any malformed input - unterminated strings,
 * bad escapes, malformed numbers, trailing content, or nesting deeper than the
 * parser's limit. The parser never lets a lower-level exception (e.g.
 * {@code StringIndexOutOfBoundsException}, {@code NumberFormatException}, or a
 * {@code StackOverflowError} from runaway recursion) escape instead: JSON
 * arriving over the bridge is untrusted input from the page, so every failure
 * mode has to be this one predictable, catchable type.
 *
 * <p>Extends {@link IllegalArgumentException} (rather than {@link RuntimeException}
 * directly) so existing callers that already catch {@code IllegalArgumentException}
 * from {@link Json#parse} keep working.
 */
public final class JsonException extends IllegalArgumentException {

    public JsonException(String message) {
        super(message);
    }
}
