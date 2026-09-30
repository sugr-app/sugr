package com.sugr.cli;

import com.sugr.bridge.Json;

import java.io.IOException;
import java.io.OutputStream;
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
 * <p>The signature covers the artifact's <em>bytes</em>, not the manifest.
 * That's what makes a tampered manifest useless: swapping in a different URL
 * doesn't help an attacker who can't produce a matching signature for whatever
 * that URL serves.
 */
final class UpdateArtifacts {

    /** The manifest filename for the default channel; other channels get {@code latest-<channel>.json}. */
    static final String DEFAULT_MANIFEST = "latest.json";

    private UpdateArtifacts() {
    }

    /** What {@link #write} produced, for logging and for tests to assert against. */
    record Result(Path artifact, Path signature, Path manifest, String target) {
    }

    /**
     * Packs {@code binary} into a signed update artifact and writes the manifest
     * beside it.
     *
     * @param installer optional platform installer to record as the fallback for
     *                  installs that can't be updated in place (a machine-wide
     *                  Windows install); signed the same way when present
     * @param baseUrl   where the files will be served from - the manifest needs
     *                  absolute URLs, and only the person publishing knows them
     */
    static Result write(Path dest, String name, String version, String channel, Path binary,
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

        Map<String, Json> entry = new LinkedHashMap<>();
        entry.put("format", Json.of("binary"));
        entry.put("url", Json.of(join(baseUrl, artifactName)));
        entry.put("sha256", Json.of(SigningKeys.sha256(artifact)));
        entry.put("size", Json.of(Files.size(artifact)));
        entry.put("signature", Json.of(signature));
        if (installer != null && Files.isRegularFile(installer)) {
            String installerName = installer.getFileName().toString();
            entry.put("installerUrl", Json.of(join(baseUrl, installerName)));
            entry.put("installerSha256", Json.of(SigningKeys.sha256(installer)));
            entry.put("installerSignature", Json.of(SigningKeys.sign(installer, privateKey)));
        }

        Map<String, Json> manifest = new LinkedHashMap<>();
        manifest.put("version", Json.of(version));
        manifest.put("pubDate", Json.of(Instant.now().toString()));
        if (notes != null && !notes.isBlank()) {
            manifest.put("notes", Json.of(notes));
        }
        if (minVersion != null && !minVersion.isBlank()) {
            manifest.put("minVersion", Json.of(minVersion));
        }
        if (rolloutPercentage != null) {
            manifest.put("rolloutPercentage", Json.of(rolloutPercentage.intValue()));
        }
        manifest.put("platforms", Json.object(Map.of(target, Json.object(entry))));

        Path manifestFile = dest.resolve(manifestName(channel));
        Files.writeString(manifestFile, prettyPrint(Json.object(manifest).encode()) + System.lineSeparator());

        return new Result(artifact, signatureFile, manifestFile, target);
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
