package com.sugr.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal static file server for {@link Frontend.Embedded} sources.
 * Accepted as "good enough for now" for v0.1 - a custom scheme handler
 * is a later optimization (see plan.md Giai đoạn 5, v0.5).
 */
final class AssetServer {

    private static final String INDEX = "/index.html";

    private final HttpServer server;
    private final String resourceRoot;

    AssetServer(String resourceRoot) throws IOException {
        this.resourceRoot = resourceRoot;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                sendPlain(exchange, 405, "405 Method Not Allowed");
                return;
            }

            String rawPath = exchange.getRequestURI().getRawPath();
            String resourcePath = resolveResourcePath(rawPath);
            if (resourcePath == null) {
                sendPlain(exchange, 404, "404 Not Found");
                return;
            }

            boolean isIndex = resourcePath.equals(INDEX);
            byte[] bytes = readResource(resourcePath);
            if (bytes == null && isSpaFallbackCandidate(resourcePath)) {
                resourcePath = INDEX;
                isIndex = true;
                bytes = readResource(resourcePath);
            }
            if (bytes == null) {
                sendPlain(exchange, 404, "404 Not Found");
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", contentType(resourcePath));
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if (isIndex) {
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            }
            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private byte[] readResource(String resourcePath) throws IOException {
        try (InputStream in = AssetServer.class.getResourceAsStream(resourceRoot + resourcePath)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static void sendPlain(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /**
     * Normalizes a raw request path into a resource path safe to append to
     * {@code resourceRoot}, or {@code null} if it's missing, malformed, or attempts to
     * escape the root (a literal {@code ..} segment, a backslash, or anything that
     * percent-decodes into either - {@code %2e} for {@code .}, {@code %2f}/{@code %5c}
     * for a slash/backslash). Decoding happens exactly once, via {@link URI#getPath()}
     * on the raw (still-encoded) path the exchange handed us - the JDK's own HttpServer
     * never decodes it for us. Rejecting the raw form outright on a backslash or
     * encoded slash means a sneaky "%2e%2e%2fsecret" can't decode into a working "../"
     * only after we've already done our segment check.
     */
    static String resolveResourcePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return null;
        }
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("\\") || lower.contains("%5c") || lower.contains("%2f") || lower.contains("%2e")) {
            return null;
        }
        String decoded;
        try {
            decoded = URI.create(rawPath).getPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded == null || decoded.isEmpty()) {
            return null;
        }
        for (String segment : decoded.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) {
                return null;
            }
        }
        if (decoded.equals("/") || decoded.isEmpty()) {
            return INDEX;
        }
        return decoded;
    }

    /**
     * Whether a missing {@code resourcePath} should fall back to {@code /index.html} -
     * true for an extensionless path (a client-side route like {@code /settings}), false
     * for anything that looks like a real asset request (e.g. a typo'd {@code /app.jss})
     * so a genuinely missing file still 404s instead of silently returning the SPA shell.
     */
    static boolean isSpaFallbackCandidate(String resourcePath) {
        if (resourcePath.equals(INDEX)) {
            return false;
        }
        String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        return !lastSegment.contains(".");
    }

    static String contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".map")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }
}
