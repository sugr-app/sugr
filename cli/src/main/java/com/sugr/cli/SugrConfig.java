package com.sugr.cli;

import com.sugr.bridge.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Optional per-app packaging settings loaded from {@code sugr.config.json}
 * in the app's root directory (alongside {@code frontend/} and {@code lib/}).
 * Currently just what {@link PackageCommand} needs (name, version, icon) -
 * CLI flags always win over the file, and the file itself is entirely
 * optional; nothing here is required for {@code dev}/{@code build}.
 */
final class SugrConfig {

    final String name;
    final String appVersion;
    final String icon;

    private SugrConfig(String name, String appVersion, String icon) {
        this.name = name;
        this.appVersion = appVersion;
        this.icon = icon;
    }

    static SugrConfig load(Path appDir) throws IOException {
        Path configFile = appDir.resolve("sugr.config.json");
        if (!Files.isRegularFile(configFile)) {
            return new SugrConfig(null, null, null);
        }
        Map<String, Json> fields = Json.parse(Files.readString(configFile)).asObject();
        return new SugrConfig(stringOrNull(fields, "name"), stringOrNull(fields, "appVersion"),
                stringOrNull(fields, "icon"));
    }

    private static String stringOrNull(Map<String, Json> fields, String key) {
        Json value = fields.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }
}
