package com.sugr.core;

import com.sugr.bridge.Json;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The release document an update endpoint serves, and the rules for deciding
 * whether it applies to the app that fetched it.
 *
 * <p>The shape follows Tauri's updater manifest rather than electron-builder's
 * {@code latest.yml}, because a single JSON file keyed by platform is easier to
 * publish by hand and needs no YAML parser:
 *
 * <pre>{@code
 * {
 *   "version": "1.0.4",
 *   "pubDate": "2026-09-09T10:00:00Z",
 *   "notes": "...",
 *   "minVersion": "1.0.0",
 *   "rolloutPercentage": 100,
 *   "platforms": {
 *     "windows-x86_64": {
 *       "format": "binary",
 *       "url": "https://.../app-1.0.4-windows-x86_64.zip",
 *       "sha256": "...", "size": 26214400, "signature": "<base64 Ed25519>",
 *       "installerUrl": "https://.../app-1.0.4-Setup.exe", "installerSha256": "...",
 *       "manifestSignature": "<base64 Ed25519 over signedPayload()>"
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>A dynamic endpoint may instead return the flat, single-platform form
 * ({@code url}/{@code signature} at the top level) - also Tauri's convention,
 * and accepted here so a server that already knows the caller's platform
 * doesn't have to echo a whole platform map back.
 *
 * <p>Nothing in here is trusted until {@link UpdateVerifier#verifyManifest}
 * has checked the entry's {@code manifestSignature} - see
 * {@link #signedPayload} for exactly which fields that covers.
 *
 * @param rawVersion    {@code version} exactly as published, which is what got
 *                      signed - {@link #version} is its parsed form
 * @param rawMinVersion {@code minVersion} exactly as published, or null
 */
record UpdateManifest(SemVer version, String notes, String pubDate, SemVer minVersion,
                      int rolloutPercentage, Map<String, Artifact> platforms,
                      String rawVersion, String rawMinVersion) {

    /** First line of {@link #signedPayload} - bumped if the covered field set ever changes. */
    static final String PAYLOAD_HEADER = "sugr-update-manifest-v1";

    /**
     * One downloadable release for one platform. {@code url} is the update
     * artifact proper (the bare executable or {@code .app} bundle, archived);
     * {@code installerUrl} is the optional full installer, only ever offered to
     * the user as a manual download link - the updater never runs it.
     *
     * @param signature         Ed25519 over the artifact's bytes
     * @param manifestSignature Ed25519 over {@link #signedPayload} for this entry
     * @param rawFormat         {@code format} exactly as published (null when absent)
     */
    record Artifact(Format format, String url, String sha256, long size, String signature,
                    String installerUrl, String installerSha256, String manifestSignature,
                    String rawFormat) {
    }

    /** What {@code url} contains, which decides how the download gets applied. */
    enum Format {
        /** A zip holding a single executable. */
        BINARY,
        /** A tar.gz holding a macOS {@code .app} bundle. */
        APP_BUNDLE;

        static Format parse(String raw) {
            if (raw == null) {
                return BINARY;
            }
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "appbundle", "app_bundle", "app" -> APP_BUNDLE;
                default -> BINARY;
            };
        }
    }

    /** Why a manifest doesn't apply, or {@link #APPLIES} when it does. */
    enum Verdict {
        APPLIES,
        /** The release is the same version we're already running. */
        UP_TO_DATE,
        /** The release is older than what's installed and downgrades aren't allowed. */
        WOULD_DOWNGRADE,
        /** This user's bucket isn't in the staged rollout yet. */
        NOT_IN_ROLLOUT,
        /** The running version is older than {@code minVersion} - needs a manual reinstall. */
        BELOW_MIN_VERSION,
        /** No entry for this OS/architecture. */
        NO_ARTIFACT
    }

    static final int DEFAULT_ROLLOUT = 100;

    /**
     * Parses a manifest, using {@code fallbackTarget} as the platform key when
     * the document is in the flat single-platform form.
     *
     * @throws IllegalArgumentException if it isn't valid JSON, has no parseable
     *                                  {@code version}, or declares no artifact at all
     */
    static UpdateManifest parse(String json, String fallbackTarget) {
        Map<String, Json> fields = Json.parse(json).asObject();

        String rawVersion = string(fields, "version");
        SemVer version = SemVer.parseOrNull(rawVersion);
        if (version == null) {
            throw new IllegalArgumentException("Update manifest has no usable \"version\"");
        }

        Map<String, Artifact> platforms = new LinkedHashMap<>();
        Json rawPlatforms = fields.get("platforms");
        if (rawPlatforms != null && !rawPlatforms.isNull()) {
            for (Map.Entry<String, Json> entry : rawPlatforms.asObject().entrySet()) {
                Artifact artifact = parseArtifact(entry.getValue().asObject());
                if (artifact != null) {
                    platforms.put(entry.getKey().toLowerCase(Locale.ROOT), artifact);
                }
            }
        } else {
            Artifact flat = parseArtifact(fields);
            if (flat != null) {
                platforms.put(fallbackTarget.toLowerCase(Locale.ROOT), flat);
            }
        }
        if (platforms.isEmpty()) {
            throw new IllegalArgumentException("Update manifest declares no artifacts");
        }

        int rollout = (int) Math.round(number(fields, "rolloutPercentage", DEFAULT_ROLLOUT));
        String rawMinVersion = string(fields, "minVersion");
        return new UpdateManifest(
                version,
                string(fields, "notes"),
                string(fields, "pubDate"),
                SemVer.parseOrNull(rawMinVersion),
                Math.max(0, Math.min(100, rollout)),
                Map.copyOf(platforms),
                rawVersion,
                rawMinVersion);
    }

    private static Artifact parseArtifact(Map<String, Json> fields) {
        String url = string(fields, "url");
        String signature = string(fields, "signature");
        if (url == null || signature == null) {
            // Both are load-bearing: without a signature there's nothing to
            // verify against, and an entry we'd refuse to install anyway is
            // better dropped here than surfaced as an update that always fails.
            return null;
        }
        String rawFormat = string(fields, "format");
        return new Artifact(
                Format.parse(rawFormat),
                url,
                string(fields, "sha256"),
                (long) number(fields, "size", 0),
                signature,
                string(fields, "installerUrl"),
                string(fields, "installerSha256"),
                string(fields, "manifestSignature"),
                rawFormat);
    }

    /**
     * The exact bytes an entry's {@code manifestSignature} is computed over:
     * everything the updater acts on or shows the user before the artifact
     * itself has been downloaded and verified.
     *
     * <p>Without this, the artifact signature alone would let a hostile or
     * compromised endpoint replay an <em>old</em> signed build labelled as a
     * new version (a rollback to a known-vulnerable release), put arbitrary
     * text in the native update dialog, or point the "download it yourself"
     * link anywhere it liked. Binding the identifier stops a release of one app
     * being replayed to another app signed with the same key; binding the
     * target stops one platform's build being offered to another.
     *
     * <p>Format: a header line, then one {@code key=<utf8 byte length>:<value>}
     * line per field, in a fixed order, absent values as length 0. The length
     * prefix keeps it unambiguous when {@code notes} contains newlines or
     * {@code =}, with no canonical-JSON rules to get subtly wrong on either
     * side. {@code pubDate} and {@code rolloutPercentage} are left out on
     * purpose: per-OS builds stamp different dates, and a rollout is meant to
     * be edited after publishing.
     *
     * <p>{@code sugr package} builds the same bytes in
     * {@code com.sugr.cli.UpdateArtifacts#signedPayload}; both modules' tests
     * pin them to one shared vector.
     */
    byte[] signedPayload(String identifier, String target) {
        Artifact artifact = artifactFor(target);
        if (artifact == null) {
            throw new IllegalArgumentException("No artifact for " + target);
        }
        StringBuilder out = new StringBuilder(PAYLOAD_HEADER).append('\n');
        field(out, "identifier", identifier);
        field(out, "version", rawVersion);
        field(out, "target", target.toLowerCase(Locale.ROOT));
        field(out, "format", artifact.rawFormat());
        field(out, "url", artifact.url());
        field(out, "sha256", artifact.sha256());
        field(out, "size", Long.toString(artifact.size()));
        field(out, "minVersion", rawMinVersion);
        field(out, "notes", notes);
        field(out, "installerUrl", artifact.installerUrl());
        field(out, "installerSha256", artifact.installerSha256());
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void field(StringBuilder out, String key, String value) {
        String v = value == null ? "" : value;
        out.append(key).append('=')
                .append(v.getBytes(StandardCharsets.UTF_8).length).append(':')
                .append(v).append('\n');
    }

    /** The entry for {@code target} (e.g. {@code "windows-x86_64"}), or null when this release skips it. */
    Artifact artifactFor(String target) {
        return platforms.get(target.toLowerCase(Locale.ROOT));
    }

    /**
     * Decides whether this release should be offered to an app running
     * {@code current}.
     *
     * @param rolloutBucket a stable 0-99 bucket for this installation - see
     *                      {@link Updater#rolloutBucket}. Keeping it stable is the
     *                      point: a bucket recomputed per check would flip an
     *                      update in and out of a staged rollout at random.
     */
    Verdict appliesTo(SemVer current, String target, boolean allowDowngrade, int rolloutBucket) {
        if (artifactFor(target) == null) {
            return Verdict.NO_ARTIFACT;
        }
        int comparison = version.compareTo(current);
        if (comparison == 0) {
            return Verdict.UP_TO_DATE;
        }
        if (comparison < 0 && !allowDowngrade) {
            return Verdict.WOULD_DOWNGRADE;
        }
        if (minVersion != null && current.compareTo(minVersion) < 0) {
            return Verdict.BELOW_MIN_VERSION;
        }
        if (rolloutBucket >= rolloutPercentage) {
            return Verdict.NOT_IN_ROLLOUT;
        }
        return Verdict.APPLIES;
    }

    /**
     * The platform key for the running machine, in Tauri's {@code <os>-<arch>}
     * form so a manifest written for one framework reads the same as the other.
     */
    static String currentTarget() {
        return currentOs() + "-" + currentArch();
    }

    static String currentOs() {
        if (Os.isWindows()) {
            return "windows";
        }
        return Os.isMac() ? "darwin" : "linux";
    }

    static String currentArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "aarch64", "arm64" -> "aarch64";
            case "x86", "i386", "i486", "i586", "i686" -> "i686";
            default -> "x86_64";
        };
    }

    private static String string(Map<String, Json> fields, String key) {
        Json value = fields.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static double number(Map<String, Json> fields, String key, double fallback) {
        Json value = fields.get(key);
        return value == null || value.isNull() ? fallback : value.asNumber();
    }
}
