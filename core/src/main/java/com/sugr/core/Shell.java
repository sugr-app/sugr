package com.sugr.core;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * OS shell actions: opening an external URL in the user's default browser or
 * mail client ({@link #openUrl}), and opening a local folder or file in the
 * OS file manager / default handler ({@link #openPath}).
 *
 * <p>{@link #openUrl} restricts schemes to {@code http}, {@code https}, and
 * {@code mailto} so an untrusted string from the frontend can never be turned
 * into local command or protocol-handler execution ({@code file:},
 * {@code javascript:}, {@code ms-msdt:}, ...). Use {@link #openPath} explicitly
 * when you intend to open a local file or directory from Java.
 */
public final class Shell {

    private static final Set<String> ALLOWED_URL_SCHEMES = Set.of("http", "https", "mailto");

    private Shell() {
    }

    /**
     * Opens {@code url} in the OS's default handler (browser for
     * {@code http}/{@code https}, mail client for {@code mailto}).
     *
     * @throws IllegalArgumentException if {@code url} is null, malformed, or uses
     *                                  a scheme outside {@code http}, {@code https}, {@code mailto}
     */
    public static void openUrl(String url) {
        URI uri = validateUrl(url);
        launchTarget(uri.toString());
    }

    /**
     * Opens {@code path} (a directory or file) in the OS's default file manager
     * or associated application.
     *
     * @throws IllegalArgumentException if {@code path} is null or does not exist
     */
    public static void openPath(Path path) {
        Path normalized = validatePath(path);
        launchTarget(normalized.toString());
    }

    /** Convenience overload for {@link #openPath(Path)}. */
    public static void openPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be null or blank");
        }
        openPath(Path.of(path));
    }

    static URI validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL must not be null or blank");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
        String rawScheme = uri.getScheme();
        if (rawScheme == null) {
            throw new IllegalArgumentException("URL is missing a scheme: " + url);
        }
        String scheme = rawScheme.toLowerCase(Locale.ROOT);
        if (!ALLOWED_URL_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException(
                    "Disallowed URL scheme \"" + rawScheme + "\" (allowed: http, https, mailto)");
        }
        if (("http".equals(scheme) || "https".equals(scheme))
                && (uri.getHost() == null || uri.getHost().isBlank())) {
            throw new IllegalArgumentException("HTTP(S) URL is missing a host: " + url);
        }
        if ("mailto".equals(scheme)
                && (uri.getSchemeSpecificPart() == null || uri.getSchemeSpecificPart().isBlank())) {
            throw new IllegalArgumentException("mailto URL is missing an address: " + url);
        }
        return uri;
    }

    static Path validatePath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Path must not be null");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new IllegalArgumentException("Path does not exist: " + normalized);
        }
        return normalized;
    }

    private static void launchTarget(String target) {
        try {
            if (Os.isWindows()) {
                // Start-Process via -EncodedCommand avoids cmd.exe / rundll32 quoting
                // pitfalls with '&' in query strings or spaces in file paths.
                String script = "$ProgressPreference = 'SilentlyContinue'\n"
                        + "Start-Process -FilePath " + Os.psQuote(target);
                String encoded = Base64.getEncoder().encodeToString(
                        script.getBytes(StandardCharsets.UTF_16LE));
                new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                        .redirectErrorStream(true)
                        .start();
            } else if (Os.isMac()) {
                new ProcessBuilder("open", target)
                        .redirectErrorStream(true)
                        .start();
            } else {
                new ProcessBuilder("xdg-open", target)
                        .redirectErrorStream(true)
                        .start();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to open target with OS shell: " + target, e);
        }
    }
}
