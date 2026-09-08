package com.sugr.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@code sugr.config.json} loading - the optional per-app packaging settings. */
final class SugrConfigTest {

    @TempDir
    Path appDir;

    @Test
    void aMissingFileGivesAllNullFields() throws IOException {
        SugrConfig config = SugrConfig.load(appDir);
        assertNull(config.name);
        assertNull(config.appVersion);
        assertNull(config.icon);
    }

    @Test
    void readsNameVersionAndIcon() throws IOException {
        Files.writeString(appDir.resolve("sugr.config.json"), """
                { "name": "sql-client", "appVersion": "1.0.3", "icon": "assets/app.ico" }
                """);

        SugrConfig config = SugrConfig.load(appDir);
        assertEquals("sql-client", config.name);
        assertEquals("1.0.3", config.appVersion);
        assertEquals("assets/app.ico", config.icon);
    }

    @Test
    void absentKeysAreNull() throws IOException {
        Files.writeString(appDir.resolve("sugr.config.json"), "{ \"name\": \"only-name\" }");

        SugrConfig config = SugrConfig.load(appDir);
        assertEquals("only-name", config.name);
        assertNull(config.appVersion);
        assertNull(config.icon);
    }

    @Test
    void anExplicitJsonNullIsTreatedAsAbsent() throws IOException {
        Files.writeString(appDir.resolve("sugr.config.json"),
                "{ \"name\": null, \"appVersion\": \"2.0.0\" }");

        SugrConfig config = SugrConfig.load(appDir);
        assertNull(config.name);
        assertEquals("2.0.0", config.appVersion);
    }

    @Test
    void anEmptyJsonObjectGivesAllNullFields() throws IOException {
        Files.writeString(appDir.resolve("sugr.config.json"), "{}");

        SugrConfig config = SugrConfig.load(appDir);
        assertNull(config.name);
        assertNull(config.appVersion);
        assertNull(config.icon);
    }
}
