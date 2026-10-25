package com.sugr.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OriginPolicyTest {

    @Test
    void originOfKeepsOnlySchemeHostAndPort() {
        assertEquals("http://127.0.0.1:54321", OriginPolicy.originOf("http://127.0.0.1:54321/"));
        assertEquals("http://localhost:5173", OriginPolicy.originOf("http://localhost:5173/app/index.html?x=1#y"));
    }

    @Test
    void originOfLowercasesAndDropsDefaultPorts() {
        assertEquals("http://localhost", OriginPolicy.originOf("HTTP://LocalHost:80/"));
        assertEquals("https://example.com", OriginPolicy.originOf("https://Example.com:443"));
        assertEquals("https://example.com:8443", OriginPolicy.originOf("https://example.com:8443"));
    }

    @Test
    void originOfIsNullForThingsWithoutAnOrigin() {
        assertNull(OriginPolicy.originOf(null));
        assertNull(OriginPolicy.originOf(""));
        assertNull(OriginPolicy.originOf("null"));
        assertNull(OriginPolicy.originOf("about:blank"));
        assertNull(OriginPolicy.originOf("localhost:5173"));
        assertNull(OriginPolicy.originOf("http://"));
        assertNull(OriginPolicy.originOf("not a url"));
    }

    @Test
    void allowsTheEmbeddedAssetServerOrigin() {
        OriginPolicy policy = OriginPolicy.forFrontend("http://127.0.0.1:54321/", List.of());
        assertTrue(policy.allows("http://127.0.0.1:54321"));
        assertEquals(Set.of("http://127.0.0.1:54321"), policy.allowed());
    }

    @Test
    void allowsTheDevServerOrigin() {
        OriginPolicy policy = OriginPolicy.forFrontend("http://localhost:5173/", List.of());
        assertTrue(policy.allows("http://localhost:5173"));
    }

    @Test
    void rejectsADifferentPortHostOrScheme() {
        OriginPolicy policy = OriginPolicy.forFrontend("http://127.0.0.1:54321/", List.of());
        assertFalse(policy.allows("http://127.0.0.1:54322"));
        assertFalse(policy.allows("http://localhost:54321"));
        assertFalse(policy.allows("https://127.0.0.1:54321"));
        assertFalse(policy.allows("https://evil.example.com"));
    }

    @Test
    void rejectsMissingAndOpaqueOrigins() {
        OriginPolicy policy = OriginPolicy.forFrontend("http://127.0.0.1:54321/", List.of());
        assertFalse(policy.allows(null));
        assertFalse(policy.allows(""));
        assertFalse(policy.allows("null"));
    }

    @Test
    void rejectsNonCanonicalOriginsInsteadOfCoercingThem() {
        OriginPolicy policy = OriginPolicy.forFrontend("http://127.0.0.1:54321/", List.of());
        assertFalse(policy.allows("http://127.0.0.1:54321/"));
        assertFalse(policy.allows("http://127.0.0.1:54321/some/path"));
        assertFalse(policy.allows("HTTP://127.0.0.1:54321"));
    }

    @Test
    void allowsExtraOriginsAddedByTheApp() {
        OriginPolicy policy = OriginPolicy.forFrontend("http://127.0.0.1:54321/",
                List.of("https://app.example.com", "http://localhost:3000/"));
        assertTrue(policy.allows("http://127.0.0.1:54321"));
        assertTrue(policy.allows("https://app.example.com"));
        assertTrue(policy.allows("http://localhost:3000"));
        assertFalse(policy.allows("https://other.example.com"));
    }

    @Test
    void unusableFrontendUrlIsLeftOutRatherThanFailing() {
        OriginPolicy policy = OriginPolicy.forFrontend("not a url", List.of("https://app.example.com"));
        assertEquals(Set.of("https://app.example.com"), policy.allowed());
    }

    @Test
    void requireOriginRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> OriginPolicy.requireOrigin("example.com"));
        assertThrows(IllegalArgumentException.class, () -> OriginPolicy.requireOrigin(null));
        assertEquals("https://example.com", OriginPolicy.requireOrigin("https://example.com/"));
    }
}
