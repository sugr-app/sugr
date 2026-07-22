package com.sugr.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonTest {

    @Test
    void encodesStringsWithEscaping() {
        assertEquals("\"hello\"", Json.of("hello").encode());
        assertEquals("\"line1\\nline2\"", Json.of("line1\nline2").encode());
        assertEquals("\"a\\\"b\\\\c\"", Json.of("a\"b\\c").encode());
    }

    @Test
    void encodesNullStringAsJsonNull() {
        assertEquals("null", Json.of((String) null).encode());
    }

    @Test
    void encodesNumbersWithoutTrailingDecimalWhenIntegral() {
        assertEquals("42", Json.of(42.0).encode());
        assertEquals("42", Json.of(42L).encode());
        assertEquals("3.5", Json.of(3.5).encode());
    }

    @Test
    void encodesBooleansAndArraysAndObjects() {
        assertEquals("true", Json.of(true).encode());
        assertEquals("false", Json.of(false).encode());
        assertEquals("[1,2,3]", Json.array(List.of(Json.of(1L), Json.of(2L), Json.of(3L))).encode());
        assertEquals("{\"a\":1}", Json.object(Map.of("a", Json.of(1L))).encode());
    }

    @Test
    void parsesRoundTripForEveryKind() {
        assertEquals("hi", Json.parse("\"hi\"").asString());
        assertEquals(7.0, Json.parse("7").asNumber());
        assertEquals(-1.5, Json.parse("-1.5").asNumber());
        assertTrue(Json.parse("true").asBoolean());
        assertFalse(Json.parse("false").asBoolean());
        assertTrue(Json.parse("null").isNull());
        assertEquals(List.of(), Json.parse("[]").asArray());
        assertEquals(2, Json.parse("[1,2]").asArray().size());
        assertEquals(1, Json.parse("{\"x\":1}").asObject().size());
    }

    @Test
    void parsesEscapeSequences() {
        assertEquals("a\nb\tc\"d", Json.parse("\"a\\nb\\tc\\\"d\"").asString());
        assertEquals("é", Json.parse("\"\\u00e9\"").asString());
    }

    @Test
    void parseRejectsTrailingContent() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("1 2"));
    }

    @Test
    void accessorsThrowOnWrongKind() {
        assertThrows(IllegalStateException.class, () -> Json.of("s").asNumber());
        assertThrows(IllegalStateException.class, () -> Json.of(1L).asString());
        assertThrows(IllegalStateException.class, () -> Json.of(true).asString());
        assertThrows(IllegalStateException.class, () -> Json.array(List.of()).asObject());
        assertThrows(IllegalStateException.class, () -> Json.object(Map.of()).asArray());
    }

    @Test
    void quoteIsShorthandForOfThenEncode() {
        assertEquals("\"hi\"", Json.quote("hi"));
        assertEquals("null", Json.quote(null));
    }

    @Test
    void parseStringArrayHandlesNulls() {
        List<String> result = Json.parseStringArray("[\"a\", null, \"b\"]");
        assertEquals(3, result.size());
        assertEquals("a", result.get(0));
        assertNull(result.get(1));
        assertEquals("b", result.get(2));
    }

    @Test
    void parseStringArrayHandlesEmptyArray() {
        assertTrue(Json.parseStringArray("[]").isEmpty());
    }
}
