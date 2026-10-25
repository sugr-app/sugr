package com.sugr.core;

import com.sugr.bridge.Json;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The app's own identity - name, version, bundle identifier, updater settings -
 * read from the classpath resource {@code /sugr/app.json}, which the CLI
 * generates from {@code sugr.config.json} before handing off to Gradle (the
 * same trick {@code sugr build} already uses to embed {@code frontend/dist}
 * into {@code resources/frontend}).
 *
 * <p>This exists because {@code appVersion} used to live <em>only</em> in
 * {@code sugr.config.json}, which the packaged binary never sees - so a
 * running app had no way to know its own version, and an updater has nothing
 * to compare a release against without one.
 *
 * <p>Loading is fail-soft on purpose: an app built before this resource
 * existed, or run straight from Gradle, gets {@link #version()}
 * {@code "0.0.0-dev"} and no updater rather than a startup crash.
 */
final class AppMetadata {

    static final String RESOURCE = "/sugr/app.json";

    private static final AppMetadata FALLBACK =
            new AppMetadata("sugr-app", SemVer.UNKNOWN, "sugr-app", null);

    private static volatile AppMetadata cached;

    private final String name;
    private final SemVer version;
    private final String identifier;
    private final UpdaterSettings updater;

    private AppMetadata(String name, SemVer version, String identifier, UpdaterSettings updater) {
        this.name = name;
        this.version = version;
        this.identifier = identifier;
        this.updater = updater;
    }

    /** The generated metadata for this app, loaded once and cached. Never null. */
    static AppMetadata get() {
        AppMetadata local = cached;
        if (local == null) {
            synchronized (AppMetadata.class) {
                local = cached;
                if (local == null) {
                    local = loadOrFallback();
                    cached = local;
                }
            }
        }
        return local;
    }

    String name() {
        return name;
    }

    SemVer version() {
        return version;
    }

    String identifier() {
        return identifier;
    }

    /** Updater settings from {@code sugr.config.json}, or null when the app didn't configure one. */
    UpdaterSettings updater() {
        return updater;
    }

    private static AppMetadata loadOrFallback() {
        try (InputStream in = AppMetadata.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return FALLBACK;
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[sugr] couldn't read " + RESOURCE + " (" + e + ") - "
                    + "falling back to " + FALLBACK.version + " with no updater.");
            return FALLBACK;
        }
    }

    /** Visible for testing - parses the generated document without touching the classpath. */
    static AppMetadata parse(String json) {
        Map<String, Json> fields = Json.parse(json).asObject();
        String name = string(fields, "name", FALLBACK.name);
        String identifier = string(fields, "identifier", name);
        SemVer version = SemVer.parseOrNull(string(fields, "version", null));
        if (version == null) {
            version = SemVer.UNKNOWN;
        }
        return new AppMetadata(name, version, identifier, UpdaterSettings.parse(fields.get("updater")));
    }

    /**
     * Everything under {@code "updater"} in {@code sugr.config.json}. Absent or
     * malformed values fall back to the documented defaults rather than failing
     * the app's startup - the one exception being {@link #isUsable()}, which
     * refuses to enable an updater that has endpoints but no public key to
     * verify what it downloads.
     */
    record UpdaterSettings(List<String> endpoints, String pubkey, String channel, boolean autoCheck,
                           boolean prompt, boolean allowDowngrade, boolean allowInsecure) {

        static final String DEFAULT_CHANNEL = "stable";

        static UpdaterSettings parse(Json value) {
            if (value == null || value.isNull()) {
                return null;
            }
            Map<String, Json> fields = value.asObject();
            List<String> endpoints = new ArrayList<>();
            Json rawEndpoints = fields.get("endpoints");
            if (rawEndpoints != null && !rawEndpoints.isNull()) {
                for (Json endpoint : rawEndpoints.asArray()) {
                    if (!endpoint.isNull()) {
                        endpoints.add(endpoint.asString());
                    }
                }
            }
            return new UpdaterSettings(
                    List.copyOf(endpoints),
                    string(fields, "pubkey", null),
                    string(fields, "channel", DEFAULT_CHANNEL),
                    bool(fields, "autoCheck", true),
                    bool(fields, "prompt", true),
                    bool(fields, "allowDowngrade", false),
                    bool(fields, "allowInsecure", false));
        }

        /**
         * True when this configuration can actually be used. Endpoints without a
         * {@code pubkey} are rejected rather than downgraded to "download over
         * HTTPS and hope": a signature is the only thing that makes replacing
         * the running executable safe, so an unverifiable updater is treated as
         * no updater at all.
         */
        boolean isUsable() {
            return !endpoints.isEmpty() && pubkey != null && !pubkey.isBlank();
        }

        /** Explains why {@link #isUsable()} is false, for a one-line startup warning. Null when it's fine. */
        String unusableReason() {
            if (endpoints.isEmpty()) {
                return "no \"endpoints\" configured";
            }
            if (pubkey == null || pubkey.isBlank()) {
                return "\"endpoints\" is set but \"pubkey\" is missing - run `sugr keygen` and paste the "
                        + "public key into sugr.config.json (updates are refused without one)";
            }
            return null;
        }
    }

    private static String string(Map<String, Json> fields, String key, String fallback) {
        Json value = fields.get(key);
        return value == null || value.isNull() ? fallback : value.asString();
    }

    private static boolean bool(Map<String, Json> fields, String key, boolean fallback) {
        Json value = fields.get(key);
        return value == null || value.isNull() ? fallback : value.asBoolean();
    }
}
