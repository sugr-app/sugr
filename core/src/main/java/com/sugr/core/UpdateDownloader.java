package com.sugr.core;

import com.sugr.core.net.SystemProxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;

/**
 * The network half of the updater: fetching manifests and downloading
 * artifacts.
 *
 * <p>Uses {@link HttpClient} from the JDK, which needs no extra native-image
 * configuration on GraalVM 25 - HTTPS, certificate validation and all.
 *
 * <p>Downloads resume rather than restart. An update artifact is the whole
 * application binary (tens of megabytes), and a user on a flaky connection who
 * loses progress every time is a user who never successfully updates, so a
 * partial file is kept and continued with a {@code Range} request on the next
 * attempt.
 */
final class UpdateDownloader {

    /** Long enough for a slow mobile connection to finish a large artifact, short enough to eventually give up. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MANIFEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ARTIFACT_TIMEOUT = Duration.ofMinutes(30);

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 1000;

    /** Manifests are small; a huge body here means something is wrong, so cap it rather than buffer it all. */
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private final HttpClient client;
    private final boolean allowInsecure;

    UpdateDownloader(boolean allowInsecure) {
        this.allowInsecure = allowInsecure;
        // The machine's proxy, not just the JDK's: behind a corporate proxy a
        // direct connection simply times out, and to the user that looks like
        // "this app never updates".
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(SystemProxy.selector())
                .build();
    }

    /** Reports bytes as they arrive. {@code total} is -1 when the server didn't say how big the artifact is. */
    interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    /**
     * Fetches an update manifest.
     *
     * @return the response body, or {@code null} for {@code 204 No Content} -
     *         the convention (shared with Tauri) a dynamic update server uses to
     *         say "you're already current" without inventing a payload for it
     * @throws IOException on a network failure or any non-2xx status
     */
    String fetchManifest(URI endpoint) throws IOException, InterruptedException {
        requireSecure(endpoint);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent())
                .timeout(MANIFEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try (InputStream in = response.body()) {
            int status = response.statusCode();
            if (status == 204) {
                return null;
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Update endpoint " + endpoint + " returned HTTP " + status);
            }
            return readCapped(in, MAX_MANIFEST_BYTES, endpoint.toString());
        }
    }

    /**
     * Reads at most {@code limit} bytes as UTF-8, failing as soon as there's a
     * byte more - so the cap bounds memory rather than being checked after the
     * whole body has already been buffered.
     */
    static String readCapped(InputStream in, int limit, String source) throws IOException {
        byte[] bytes = in.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw new IOException("Update manifest from " + source + " is implausibly large (over "
                    + limit + " bytes)");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Downloads {@code url} to {@code target}, resuming a partial file if one is
     * already there, and retrying a few times with backoff.
     *
     * <p>The caller is responsible for verifying the result - a resumed download
     * that silently stitched together bytes from two different releases would
     * still fail its signature check, which is exactly the intended safety net.
     */
    void download(URI url, Path target, long expectedSize, ProgressListener listener)
            throws IOException, InterruptedException {
        requireSecure(url);
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                downloadOnce(url, target, expectedSize, listener);
                return;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < MAX_ATTEMPTS) {
                    System.err.println("[sugr] update download attempt " + attempt + " failed (" + e.getMessage()
                            + "); retrying");
                    Thread.sleep(RETRY_BASE_DELAY_MS * (1L << (attempt - 1)));
                }
            }
        }
        throw new IOException("Update download failed after " + MAX_ATTEMPTS + " attempts", lastFailure);
    }

    private void downloadOnce(URI url, Path target, long expectedSize, ProgressListener listener)
            throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        long alreadyHave = Files.isRegularFile(target) ? Files.size(target) : 0;

        // A partial file at or beyond the expected size is not resumable - it's
        // either finished (the caller will verify) or garbage from a previous
        // run against a different release. Either way, start clean.
        if (expectedSize > 0 && alreadyHave >= expectedSize) {
            if (alreadyHave == expectedSize) {
                listener.onProgress(alreadyHave, expectedSize);
                return;
            }
            Files.delete(target);
            alreadyHave = 0;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(url)
                .header("User-Agent", userAgent())
                .timeout(ARTIFACT_TIMEOUT)
                .GET();
        if (alreadyHave > 0) {
            builder.header("Range", "bytes=" + alreadyHave + "-");
        }

        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();

        boolean append;
        if (status == 206) {
            append = true;
        } else if (status == 200) {
            // The server ignored our Range header and is sending the whole file.
            append = false;
            alreadyHave = 0;
        } else if (status == 416) {
            // "Range not satisfiable" - our partial file is longer than the
            // resource. It can't be the artifact we want; drop it and retry clean.
            response.body().close();
            Files.deleteIfExists(target);
            throw new IOException("Server rejected the resume range for " + url + " - discarded the partial file");
        } else {
            response.body().close();
            throw new IOException("Downloading " + url + " returned HTTP " + status);
        }

        long total = expectedSize > 0 ? expectedSize : contentTotal(response, alreadyHave);
        long written = alreadyHave;

        try (InputStream in = response.body();
             OutputStream out = append
                     ? Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                     : Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[128 * 1024];
            long sinceReport = 0;
            int read;
            listener.onProgress(written, total);
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                written += read;
                sinceReport += read;
                // Report roughly every 256KB rather than every chunk - each
                // report crosses into JS via an event, and a few thousand of
                // those per download would swamp the webview for no benefit.
                if (sinceReport >= 256 * 1024) {
                    sinceReport = 0;
                    listener.onProgress(written, total);
                }
            }
            listener.onProgress(written, total);
        }

        if (expectedSize > 0 && written != expectedSize) {
            throw new IOException("Download ended at " + written + " bytes, expected " + expectedSize);
        }
    }

    /** Total size implied by {@code Content-Length} plus whatever we'd already downloaded, or -1 if unknown. */
    private static long contentTotal(HttpResponse<?> response, long alreadyHave) {
        return response.headers().firstValueAsLong("content-length")
                .stream()
                .map(length -> length + alreadyHave)
                .findFirst()
                .orElse(-1L);
    }

    /**
     * Refuses plaintext HTTP. Without TLS an attacker on the network can serve
     * their own manifest; the signature check would still stop them installing
     * anything, but it also lets them pin a user to an old, vulnerable release
     * forever, so the transport is required to be encrypted regardless.
     * {@code allowInsecure} exists for testing against a local server, and is
     * forced off in packaged builds (see {@link Updater#effectiveAllowInsecure}).
     */
    private void requireSecure(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IOException("Update URL has no scheme: " + uri);
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if ("http".equalsIgnoreCase(scheme) && allowInsecure) {
            return;
        }
        throw new IOException("Update URLs must use https (got " + scheme + "://). "
                + "\"allowInsecure\" under \"updater\" allows http:// only in unpackaged test runs.");
    }

    private static String userAgent() {
        AppMetadata metadata = AppMetadata.get();
        return "sugr-updater/" + metadata.name().toLowerCase(Locale.ROOT) + "/" + metadata.version();
    }
}
