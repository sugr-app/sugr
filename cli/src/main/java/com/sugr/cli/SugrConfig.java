package com.sugr.cli;

import com.sugr.bridge.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional per-app settings loaded from {@code sugr.config.json} in the app's
 * root directory (alongside {@code frontend/} and {@code lib/}). CLI flags
 * always win over the file, and the file itself is entirely optional.
 *
 * <p>Beyond what {@link PackageCommand} needs to name an installer, this is
 * also the source of the app metadata that gets <em>embedded into the binary</em>
 * by {@link #writeAppMetadata}: a packaged app can't read this file at runtime
 * (it isn't shipped), so the parts it needs to know about itself - version,
 * identifier, updater endpoints - are baked into a generated resource instead.
 */
final class SugrConfig {

    /** Where {@link #writeAppMetadata} writes, relative to the Java module dir. Matches AppMetadata.RESOURCE. */
    static final String APP_METADATA_RESOURCE = "src/main/resources/sugr/app.json";

    static final String DEFAULT_NAME = "sugr-app";
    static final String DEFAULT_VERSION = "0.1.0";

    final String name;
    final String appVersion;
    final String icon;
    final String identifier;
    final Updater updater;

    private SugrConfig(String name, String appVersion, String icon, String identifier, Updater updater) {
        this.name = name;
        this.appVersion = appVersion;
        this.icon = icon;
        this.identifier = identifier;
        this.updater = updater;
    }

    static SugrConfig load(Path appDir) throws IOException {
        Path configFile = appDir.resolve("sugr.config.json");
        if (!Files.isRegularFile(configFile)) {
            return new SugrConfig(null, null, null, null, null);
        }
        Map<String, Json> fields = Json.parse(Files.readString(configFile)).asObject();
        return new SugrConfig(
                stringOrNull(fields, "name"),
                stringOrNull(fields, "appVersion"),
                stringOrNull(fields, "icon"),
                stringOrNull(fields, "identifier"),
                Updater.parse(fields.get("updater")));
    }

    /** {@link #name} or the fallback every command shares. */
    String nameOrDefault() {
        return name != null ? name : DEFAULT_NAME;
    }

    /** {@link #appVersion} or the fallback every command shares. */
    String versionOrDefault() {
        return appVersion != null ? appVersion : DEFAULT_VERSION;
    }

    /**
     * {@link #identifier}, defaulting to {@code com.sugr.<name>} - the same
     * bundle id {@code sugr package} has always written into macOS'
     * {@code Info.plist}, now also used for the app's data/cache directories.
     */
    String identifierOrDefault() {
        return identifier != null ? identifier : "com.sugr." + nameOrDefault();
    }

    /**
     * Writes the {@code /sugr/app.json} resource the runtime reads back through
     * {@code com.sugr.core.AppMetadata}, using {@code name}/{@code version}
     * overrides when a command was given them on the command line.
     *
     * <p>Called before Gradle runs, so native-image picks the file up as a
     * regular resource - the same ordering {@code sugr build} already relies on
     * when it copies {@code frontend/dist} into the module's resources.
     *
     * @return the file written, for logging
     */
    Path writeAppMetadata(Path libDir, String nameOverride, String versionOverride) throws IOException {
        Map<String, Json> doc = new LinkedHashMap<>();
        doc.put("name", Json.of(nameOverride != null ? nameOverride : nameOrDefault()));
        doc.put("version", Json.of(versionOverride != null ? versionOverride : versionOrDefault()));
        doc.put("identifier", Json.of(identifierOrDefault()));
        if (updater != null) {
            doc.put("updater", updater.toJson());
        }

        Path target = libDir.resolve(APP_METADATA_RESOURCE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, Json.object(doc).encode() + System.lineSeparator());
        return target;
    }

    /**
     * Loads {@code sugr.config.json} from the current directory and writes the
     * embedded metadata resource into {@code libDir}, logging what it wrote.
     * Shared by {@code dev}, {@code build} and {@code package} so all three
     * produce a binary that knows its own version - a dev-mode app reporting a
     * different version than the packaged one would make the updater
     * untestable.
     *
     * <p>Failing to write is reported but not fatal: the runtime falls back to
     * {@code 0.0.0-dev} with no updater, which is a strictly better outcome
     * than refusing to build.
     */
    static SugrConfig emitAppMetadata(Path appDir, Path libDir, String nameOverride, String versionOverride) {
        SugrConfig config;
        try {
            config = load(appDir);
        } catch (IOException e) {
            System.err.println("[sugr] couldn't read sugr.config.json: " + e.getMessage());
            return new SugrConfig(null, null, null, null, null);
        }
        try {
            Path written = config.writeAppMetadata(libDir, nameOverride, versionOverride);
            System.out.println("[sugr] app metadata -> " + written + " (version "
                    + (versionOverride != null ? versionOverride : config.versionOrDefault()) + ")");
        } catch (IOException e) {
            System.err.println("[sugr] couldn't write app metadata into " + libDir + ": " + e.getMessage());
        }
        return config;
    }

    /**
     * The {@code "updater"} block. Everything is optional; {@code endpoints}
     * plus {@code pubkey} are what actually turn the feature on, and
     * {@link PackageCommand} refuses to build update artifacts without a
     * {@code pubkey} to match them against.
     */
    record Updater(List<String> endpoints, String pubkey, String channel, Boolean autoCheck, Boolean prompt,
                   Boolean allowDowngrade, Boolean allowInsecure, String windowsInstallMode) {

        static final String DEFAULT_CHANNEL = "stable";

        static Updater parse(Json value) {
            if (value == null || value.isNull()) {
                return null;
            }
            Map<String, Json> fields = value.asObject();
            List<String> endpoints = new ArrayList<>();
            Json raw = fields.get("endpoints");
            if (raw != null && !raw.isNull()) {
                for (Json endpoint : raw.asArray()) {
                    if (!endpoint.isNull()) {
                        endpoints.add(endpoint.asString());
                    }
                }
            }
            return new Updater(
                    List.copyOf(endpoints),
                    stringOrNull(fields, "pubkey"),
                    stringOrNull(fields, "channel"),
                    boolOrNull(fields, "autoCheck"),
                    boolOrNull(fields, "prompt"),
                    boolOrNull(fields, "allowDowngrade"),
                    boolOrNull(fields, "allowInsecure"),
                    stringOrNull(fields, "windowsInstallMode"));
        }

        String channelOrDefault() {
            return channel != null && !channel.isBlank() ? channel : DEFAULT_CHANNEL;
        }

        /** Re-encodes only the keys that were actually set, so defaults stay owned by the runtime. */
        Json toJson() {
            Map<String, Json> out = new LinkedHashMap<>();
            if (!endpoints.isEmpty()) {
                out.put("endpoints", Json.array(endpoints.stream().map(Json::of).toList()));
            }
            putIfSet(out, "pubkey", pubkey);
            putIfSet(out, "channel", channel);
            putIfSet(out, "autoCheck", autoCheck);
            putIfSet(out, "prompt", prompt);
            putIfSet(out, "allowDowngrade", allowDowngrade);
            putIfSet(out, "allowInsecure", allowInsecure);
            putIfSet(out, "windowsInstallMode", windowsInstallMode);
            return Json.object(out);
        }

        private static void putIfSet(Map<String, Json> out, String key, String value) {
            if (value != null) {
                out.put(key, Json.of(value));
            }
        }

        private static void putIfSet(Map<String, Json> out, String key, Boolean value) {
            if (value != null) {
                out.put(key, Json.of(value.booleanValue()));
            }
        }
    }

    private static String stringOrNull(Map<String, Json> fields, String key) {
        Json value = fields.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Boolean boolOrNull(Map<String, Json> fields, String key) {
        Json value = fields.get(key);
        return value == null || value.isNull() ? null : Boolean.valueOf(value.asBoolean());
    }
}
