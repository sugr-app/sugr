package com.sugr.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgeExceptionTest {

    @Test
    void defaultsToInternalErrorCode() {
        BridgeException e = new BridgeException("boom");
        assertEquals(BridgeException.INTERNAL_ERROR, e.code());
        assertEquals("boom", e.getMessage());
    }

    @Test
    void toJsonOmitsDataWhenNull() {
        BridgeException e = new BridgeException("NOT_FOUND", "no such record");
        String encoded = e.toJson().encode();
        assertEquals("{\"code\":\"NOT_FOUND\",\"message\":\"no such record\"}", encoded);
        assertFalse(encoded.contains("data"));
    }

    @Test
    void toJsonIncludesDataWhenPresent() {
        BridgeException e = new BridgeException("NOT_FOUND", "no such record", Json.of(42L));
        String encoded = e.toJson().encode();
        assertEquals("{\"code\":\"NOT_FOUND\",\"message\":\"no such record\",\"data\":42}", encoded);
    }

    @Test
    void codeAndMessageConstructorHasNoData() {
        BridgeException e = new BridgeException("DIALOG_CANCELLED", "no file was selected");
        assertTrue(e.toJson().asObject().containsKey("code"));
        assertFalse(e.toJson().asObject().containsKey("data"));
    }
}
