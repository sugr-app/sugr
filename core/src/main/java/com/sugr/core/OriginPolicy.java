package com.sugr.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Interim origin allowlist for bridge calls. {@link Window}'s bootstrap script wraps
 * {@code window.invoke}/{@code window.emit} so every call also carries the page's
 * {@code location.origin}; {@link Window} rejects a call whose origin isn't in this
 * policy with a {@code FORBIDDEN} {@link com.sugr.bridge.BridgeException}. The allowed
 * set is the window's own frontend origin (the embedded {@link AssetServer}'s
 * {@code http://127.0.0.1:<port>}, or the {@link Frontend.DevServer} URL's origin) plus
 * anything added via {@link Window.Builder#allowOrigin}.
 *
 * <p><b>Defense-in-depth only, not a security boundary.</b> The origin is reported by
 * the page itself, so it stops the accidental case - the webview having been navigated
 * to some other site (a followed link, a redirect) that then finds {@code window.invoke}
 * lying around - but a malicious script running in the same page context can simply
 * call the raw binding with a spoofed origin. Real protection (blocking navigation away
 * from the app's own origin, and a Content-Security-Policy for the embedded frontend)
 * comes later; this is the stopgap until then.
 */
final class OriginPolicy {

    private final Set<String> allowed;

    /** @param origins origins or full URLs (only their scheme/host/port is kept); each must parse - see {@link #originOf}. */
    OriginPolicy(Collection<String> origins) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String origin : origins) {
            normalized.add(requireOrigin(origin));
        }
        this.allowed = Set.copyOf(normalized);
    }

    /**
     * The policy for a window whose frontend lives at {@code frontendUrl}, plus
     * {@code extraOrigins}. A {@code frontendUrl} that has no usable origin (a malformed
     * {@code SUGR_DEV_URL}, say) is logged and left out rather than failing window
     * creation - calls from it are then simply rejected.
     */
    static OriginPolicy forFrontend(String frontendUrl, Collection<String> extraOrigins) {
        Set<String> origins = new LinkedHashSet<>(extraOrigins);
        String frontendOrigin = originOf(frontendUrl);
        if (frontendOrigin != null) {
            origins.add(frontendOrigin);
        } else {
            System.err.println("[sugr] could not derive an origin from the frontend URL \"" + frontendUrl
                    + "\" - bridge calls will only be accepted from origins added via allowOrigin()");
        }
        return new OriginPolicy(origins);
    }

    /** The normalized allowed origins, e.g. {@code [http://127.0.0.1:54321]}. */
    Set<String> allowed() {
        return allowed;
    }

    /**
     * Whether a call reporting {@code origin} (the page's {@code location.origin}) is
     * allowed. The value must already be in the canonical form browsers produce -
     * lowercase scheme/host, no default port, no path - so anything else (including
     * the literal {@code "null"} an opaque origin like {@code about:blank} or a
     * {@code data:} URL reports) is rejected rather than coerced into a match.
     */
    boolean allows(String origin) {
        if (origin == null) {
            return false;
        }
        String normalized = originOf(origin);
        return origin.equals(normalized) && allowed.contains(normalized);
    }

    /**
     * {@code scheme://host[:port]} for {@code url}, lowercased, with the port dropped when
     * it's the scheme's default (matching {@code location.origin}), or {@code null} if it
     * has no scheme/host. E.g. {@code "http://LocalHost:5173/app"} -> {@code "http://localhost:5173"}.
     */
    static String originOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty()) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port == defaultPort(scheme)) {
            port = -1;
        }
        return scheme + "://" + host + (port == -1 ? "" : ":" + port);
    }

    static String requireOrigin(String origin) {
        String normalized = originOf(origin);
        if (normalized == null) {
            throw new IllegalArgumentException("Not a valid origin (expected e.g. \"https://example.com\"): " + origin);
        }
        return normalized;
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http", "ws" -> 80;
            case "https", "wss" -> 443;
            default -> -1;
        };
    }
}
