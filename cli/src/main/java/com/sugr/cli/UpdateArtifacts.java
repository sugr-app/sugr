package com.sugr.cli;

import com.sugr.bridge.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the files an app's update endpoint serves: the signed update artifact
 * and the {@code latest.json} manifest describing it.
 *
 * <p>Kept separate from {@link PackageCommand}'s installer building because the
 * two answer different questions - an installer is for someone who doesn't have
 * the app yet, an update artifact is for someone who does. They ship together
 * but nothing else about them is shared.
 *
 * <p>Two signatures per platform entry, under the same key: {@code signature}
 * over the artifact's <em>bytes</em>, so what gets installed is exactly what
 * was built; and {@code manifestSignature} over {@link #signedPayload} - the
 * version, URLs, checksum, notes and app identifier - so a hostile endpoint
 * can't relabel an old signed build as new, or rewrite the notes or download
 * link the app shows its user.
 */
final class UpdateArtifacts {

    /** The manifest filename for the default channel; other channels get {@code latest-<channel>.json}. */
    static final String DEFAULT_MANIFEST = "latest.json";

    /** First line of {@link #signedPayload}; must match {@code com.sugr.core.UpdateManifest.PAYLOAD_HEADER}. */
    static final String PAYLOAD_HEADER = "sugr-update-manifest-v1";

    private UpdateArtifacts() {
    }

    /** What {@link #write} produced, for logging and for tests to assert against. */
    record Result(Path artifact, Path signature, Path manifest, String target) {
    }

    /**
     * Packs {@code binary} into a signed update artifact and writes the manifest
     * beside it.
     *
     * @param identifier the app identifier the runtime checks the manifest
     *                   signature against - must be what was embedded in the build
     * @param installer  optional platform installer, recorded (with its checksum,
     *                   covered by the manifest signature) as the manual download
     *                   for installs that can't be updated in place
     * @param baseUrl    where the files will be served from - the manifest needs
     *                   absolute URLs, and only the person publishing knows them
     */
    static Result write(Path dest, String name, String identifier, String version, String channel, Path binary,
                        String installedFileName, Path installer, String baseUrl, String notes,
                        String minVersion, Integer rolloutPercentage, PrivateKey privateKey)
            throws IOException, GeneralSecurityException {

        String target = hostTarget();
        String artifactName = name + "-" + version + "-" + target + ".zip";
        Path artifact = dest.resolve(artifactName);

        // The runtime unpacks the single entry and renames it over the installed
        // executable, so the entry is named for where it lands rather than for
        // whatever native-image happened to call its output.
        zipSingleFile(binary, artifact, installedFileName);

        String signature = SigningKeys.sign(artifact, privateKey);
        Path signatureFile = dest.resolve(artifactName + ".sig");
        Files.writeString(signatureFile, signature + System.lineSeparator());

        String format = "binary";
        String url = join(baseUrl, artifactName);
        String sha256 = SigningKeys.sha256(artifact);
        long size = Files.size(artifact);
        String installerUrl = null;
        String installerSha256 = null;
        if (installer != null && Files.isRegularFile(installer)) {
            installerUrl = join(baseUrl, installer.getFileName().toString());
            installerSha256 = SigningKeys.sha256(installer);
        }
        String publishedNotes = notes != null && !notes.isBlank() ? notes : null;
        String publishedMinVersion = minVersion != null && !minVersion.isBlank() ? minVersion : null;

        Map<String, Json> entry = new LinkedHashMap<>();
        entry.put("format", Json.of(format));
        entry.put("url", Json.of(url));
        entry.put("sha256", Json.of(sha256));
        entry.put("size", Json.of(size));
        entry.put("signature", Json.of(signature));
        if (installerUrl != null) {
            entry.put("installerUrl", Json.of(installerUrl));
            entry.put("installerSha256", Json.of(installerSha256));
        }
        byte[] payload = signedPayload(identifier, version, target, format, url, sha256, size,
                publishedMinVersion, publishedNotes, installerUrl, installerSha256);
        entry.put("manifestSignature", Json.of(SigningKeys.sign(payload, privateKey)));

        Map<String, Json> manifest = new LinkedHashMap<>();
        manifest.put("version", Json.of(version));
        manifest.put("pubDate", Json.of(Instant.now().toString()));
        if (publishedNotes != null) {
            manifest.put("notes", Json.of(publishedNotes));
        }
        if (publishedMinVersion != null) {
            manifest.put("minVersion", Json.of(publishedMinVersion));
        }
        if (rolloutPercentage != null) {
            manifest.put("rolloutPercentage", Json.of(rolloutPercentage.intValue()));
        }
        manifest.put("platforms", Json.object(Map.of(target, Json.object(entry))));

        Path manifestFile = dest.resolve(manifestName(channel));
        Files.writeString(manifestFile, prettyPrint(Json.object(manifest).encode()) + System.lineSeparator());

        return new Result(artifact, signatureFile, manifestFile, target);
    }

    /**
     * The bytes {@code manifestSignature} covers: a header line, then one
     * {@code key=<utf8 byte length>:<value>} line per field in this fixed
     * order, absent values as length 0. Must stay byte-identical to
     * {@code com.sugr.core.UpdateManifest#signedPayload}, which rebuilds it
     * from the parsed manifest - both modules' tests pin the same vector.
     *
     * <p>Edit {@code notes}, {@code version} or a URL in a published manifest
     * by hand and the app will (correctly) refuse it; {@code pubDate} and
     * {@code rolloutPercentage} are deliberately outside the signature so a
     * rollout can still be widened after publishing.
     */
    static byte[] signedPayload(String identifier, String version, String target, String format, String url,
                                String sha256, long size, String minVersion, String notes,
                                String installerUrl, String installerSha256) {
        StringBuilder out = new StringBuilder(PAYLOAD_HEADER).append('\n');
        field(out, "identifier", identifier);
        field(out, "version", version);
        field(out, "target", target.toLowerCase(java.util.Locale.ROOT));
        field(out, "format", format);
        field(out, "url", url);
        field(out, "sha256", sha256);
        field(out, "size", Long.toString(size));
        field(out, "minVersion", minVersion);
        field(out, "notes", notes);
        field(out, "installerUrl", installerUrl);
        field(out, "installerSha256", installerSha256);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void field(StringBuilder out, String key, String value) {
        String v = value == null ? "" : value;
        out.append(key).append('=')
                .append(v.getBytes(StandardCharsets.UTF_8).length).append(':')
                .append(v).append('\n');
    }

    /** {@code latest.json} for the default channel, {@code latest-<channel>.json} otherwise. */
    static String manifestName(String channel) {
        return channel == null || channel.isBlank() || SugrConfig.Updater.DEFAULT_CHANNEL.equals(channel)
                ? DEFAULT_MANIFEST
                : "latest-" + channel + ".json";
    }

    /**
     * The platform key for the machine running the build. Packaging is
     * inherently per-platform (native-image only emits a binary for its host),
     * so a manifest written here covers exactly one target - CI merges the three
     * per-OS manifests into one, or serves them from per-target URLs.
     */
    static String hostTarget() {
        String os;
        if (ProcessUtil.isWindows()) {
            os = "windows";
        } else if (ProcessUtil.isMac()) {
            os = "darwin";
        } else {
            os = "linux";
        }
        String arch = switch (System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT)) {
            case "aarch64", "arm64" -> "aarch64";
            case "x86", "i386", "i486", "i586", "i686" -> "i686";
            default -> "x86_64";
        };
        return os + "-" + arch;
    }

    private static void zipSingleFile(Path source, Path zip, String entryName) throws IOException {
        Files.deleteIfExists(zip);
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(entryName));
            Files.copy(source, zos);
            zos.closeEntry();
        }
    }

    private static String join(String baseUrl, String fileName) {
        if (baseUrl == null || baseUrl.isBlank()) {
            // No base URL given: emit the bare filename so the manifest is still
            // valid and obviously needs the URLs filling in before publishing.
            return fileName;
        }
        return baseUrl.endsWith("/") ? baseUrl + fileName : baseUrl + "/" + fileName;
    }

    /**
     * Reformats the compact encoding {@link Json} produces. A manifest is a file
     * humans read and hand-edit (bumping a rollout percentage, fixing a URL), so
     * it's worth the few lines to not ship it as one long line.
     */
    static String prettyPrint(String compact) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    out.append(c);
                }
                case '{', '[' -> {
                    depth++;
                    out.append(c).append('\n').append("  ".repeat(depth));
                }
                case '}', ']' -> {
                    depth--;
                    out.append('\n').append("  ".repeat(depth)).append(c);
                }
                case ',' -> out.append(",\n").append("  ".repeat(depth));
                case ':' -> out.append(": ");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
