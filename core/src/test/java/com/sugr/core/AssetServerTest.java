package com.sugr.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path-resolution/content-type logic (pure, no server involved) plus a real
 * end-to-end smoke test against {@code core/src/test/resources/frontend-test}.
 */
final class AssetServerTest {

    private AssetServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    // --- resolveResourcePath: traversal rejection ---

    @Test
    void plainPathResolvesUnchanged() {
        assertEquals("/assets/app.js", AssetServer.resolveResourcePath("/assets/app.js"));
    }

    @Test
    void rootResolvesToIndex() {
        assertEquals("/index.html", AssetServer.resolveResourcePath("/"));
    }

    @Test
    void literalDotDotSegmentIsRejected() {
        assertNull(AssetServer.resolveResourcePath("/../secret.txt"));
        assertNull(AssetServer.resolveResourcePath("/assets/../../secret.txt"));
    }

    @Test
    void literalDotSegmentIsRejected() {
        assertNull(AssetServer.resolveResourcePath("/./index.html"));
    }

    @Test
    void backslashIsRejected() {
        assertNull(AssetServer.resolveResourcePath("/assets\\..\\secret.txt"));
    }

    @Test
    void percentEncodedDotIsRejected() {
        assertNull(AssetServer.resolveResourcePath("/%2e%2e/secret.txt"));
        assertNull(AssetServer.resolveResourcePath("/%2E%2E/secret.txt"));
    }

    @Test
    void percentEncodedSlashIsRejected() {
        assertNull(AssetServer.resolveResourcePath("/assets%2f..%2fsecret.txt"));
    }

    @Test
    void percentEncodedBackslashIsRejected() {
        assertNull(AssetServer.resolveResourcePath("/assets%5c..%5csecret.txt"));
    }

    @Test
    void nullOrEmptyPathIsRejected() {
        assertNull(AssetServer.resolveResourcePath(null));
        assertNull(AssetServer.resolveResourcePath(""));
    }

    // --- MIME mapping ---

    @Test
    void knownExtensionsMapToExpectedContentTypes() {
        assertEquals("text/html; charset=utf-8", AssetServer.contentType("/index.html"));
        assertEquals("text/javascript; charset=utf-8", AssetServer.contentType("/app.js"));
        assertEquals("text/css; charset=utf-8", AssetServer.contentType("/app.css"));
        assertEquals("image/svg+xml", AssetServer.contentType("/logo.svg"));
        assertEquals("application/json; charset=utf-8", AssetServer.contentType("/data.json"));
        assertEquals("text/plain; charset=utf-8", AssetServer.contentType("/readme.txt"));
        assertEquals("application/json; charset=utf-8", AssetServer.contentType("/app.js.map"));
        assertEquals("image/png", AssetServer.contentType("/a.png"));
        assertEquals("image/jpeg", AssetServer.contentType("/a.jpg"));
        assertEquals("image/jpeg", AssetServer.contentType("/a.jpeg"));
        assertEquals("image/gif", AssetServer.contentType("/a.gif"));
        assertEquals("image/webp", AssetServer.contentType("/a.webp"));
        assertEquals("image/x-icon", AssetServer.contentType("/favicon.ico"));
        assertEquals("font/woff", AssetServer.contentType("/a.woff"));
        assertEquals("font/woff2", AssetServer.contentType("/a.woff2"));
        assertEquals("font/ttf", AssetServer.contentType("/a.ttf"));
        assertEquals("application/wasm", AssetServer.contentType("/a.wasm"));
    }

    @Test
    void unknownExtensionFallsBackToOctetStream() {
        assertEquals("application/octet-stream", AssetServer.contentType("/a.bin"));
    }

    @Test
    void contentTypeLookupIsCaseInsensitive() {
        assertEquals("image/png", AssetServer.contentType("/A.PNG"));
    }

    // --- SPA fallback decision ---

    @Test
    void extensionlessPathIsASpaFallbackCandidate() {
        assertTrue(AssetServer.isSpaFallbackCandidate("/settings"));
        assertTrue(AssetServer.isSpaFallbackCandidate("/nested/route"));
    }

    @Test
    void pathWithAnExtensionIsNotASpaFallbackCandidate() {
        assertFalse(AssetServer.isSpaFallbackCandidate("/app.js"));
        assertFalse(AssetServer.isSpaFallbackCandidate("/assets/app.jss"));
    }

    @Test
    void indexItselfIsNotASpaFallbackCandidate() {
        assertFalse(AssetServer.isSpaFallbackCandidate("/index.html"));
    }

    // --- integration: real HTTP round trip against the test fixture ---

    @Test
    void servesIndexAtRoot() throws IOException, InterruptedException {
        server = new AssetServer("/frontend-test");
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("sugr test fixture"));
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
    }

    @Test
    void servesANestedAsset() throws IOException, InterruptedException {
        server = new AssetServer("/frontend-test");
        HttpResponse<String> response = get("/assets/app.js");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("fixture"));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
    }

    @Test
    void fallsBackToIndexForAnUnknownExtensionlessRoute() throws IOException, InterruptedException {
        server = new AssetServer("/frontend-test");
        HttpResponse<String> response = get("/some/client/route");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("sugr test fixture"));
    }

    @Test
    void returns404ForAMissingAssetWithAnExtension() throws IOException, InterruptedException {
        server = new AssetServer("/frontend-test");
        HttpResponse<String> response = get("/assets/does-not-exist.js");
        assertEquals(404, response.statusCode());
    }

    @Test
    void returns404ForATraversalAttempt() throws IOException, InterruptedException {
        // Percent-encoded so the HttpClient/URI stack doesn't normalize ".." away
        // before the request is even sent - this is exactly the form a hostile
        // client would use to get past a naive check.
        server = new AssetServer("/frontend-test");
        HttpResponse<String> response = get("/%2e%2e/build.gradle");
        assertEquals(404, response.statusCode());
    }

    @Test
    void returns405ForAPostRequest() throws IOException, InterruptedException {
        server = new AssetServer("/frontend-test");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.baseUrl()))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path.substring(1))).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
