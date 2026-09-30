package com.sugr.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void fallsBackToSharedDefaults() throws IOException {
        SugrConfig empty = SugrConfig.load(appDir);
        assertEquals(SugrConfig.DEFAULT_NAME, empty.nameOrDefault());
        assertEquals(SugrConfig.DEFAULT_VERSION, empty.versionOrDefault());
        assertEquals("com.sugr." + SugrConfig.DEFAULT_NAME, empty.identifierOrDefault());
    }

    @Test
    void identifierDefaultsToTheMacBundleIdItAlwaysUsed() throws IOException {
        Files.writeString(appDir.resolve("sugr.config.json"), "{ \"name\": \"sql-client\" }");
        assertEquals("com.sugr.sql-client", SugrConfig.load(appDir).identifierOrDefault());
    }

    @Test
    void readsAnExplicitIdentifier() throws IOException {
        Files.writeString(appDir.resolve("sugr.config.json"), """
                { "name": "sql-client", "identifier": "sugr.examples.sqlclient" }
                """);
        assertEquals("sugr.examples.sqlclient", SugrConfig.load(appDir).identifierOrDefault());
    }

    @Test
    void readsTheUpdaterBlock() throws IOException {
        Files.writeString(appDir.resolve("sugr.config.json"), """
                { "name": "app", "appVersion": "1.0.0", "updater": {
                    "endpoints": ["https://example.test/latest.json", "https://mirror.test/latest.json"],
                    "pubkey": "a2V5", "channel": "beta", "autoCheck": false, "prompt": false,
                    "allowDowngrade": true, "allowInsecure": true, "windowsInstallMode": "quiet" } }
                """);

        SugrConfig.Updater updater = SugrConfig.load(appDir).updater;
        assertEquals(2, updater.endpoints().size());
        assertEquals("a2V5", updater.pubkey());
        assertEquals("beta", updater.channelOrDefault());
        assertEquals(Boolean.FALSE, updater.autoCheck());
        assertEquals(Boolean.TRUE, updater.allowDowngrade());
        assertEquals("quiet", updater.windowsInstallMode());
    }

    @Test
    void noUpdaterBlockMeansNoUpdater() throws IOException {
        Files.writeString(appDir.resolve("sugr.config.json"), "{ \"name\": \"app\" }");
        assertNull(SugrConfig.load(appDir).updater);
    }

    @Test
    void channelDefaultsToStable() throws IOException {
        Files.writeString(appDir.resolve("sugr.config.json"), """
                { "updater": { "endpoints": ["https://e.test/l.json"], "pubkey": "a2V5" } }
                """);
        assertEquals("stable", SugrConfig.load(appDir).updater.channelOrDefault());
    }

    @Test
    void writesTheEmbeddedMetadataResource() throws IOException {
        Files.writeString(appDir.resolve("sugr.config.json"), """
                { "name": "sql-client", "appVersion": "1.0.3", "identifier": "sugr.examples.sqlclient",
                  "updater": { "endpoints": ["https://example.test/latest.json"], "pubkey": "a2V5" } }
                """);
        Path libDir = Files.createDirectory(appDir.resolve("lib"));

        Path written = SugrConfig.load(appDir).writeAppMetadata(libDir, null, null);

        assertEquals(libDir.resolve(SugrConfig.APP_METADATA_RESOURCE).normalize(), written.normalize());
        String json = Files.readString(written);
        assertTrue(json.contains("\"version\":\"1.0.3\""));
        assertTrue(json.contains("\"identifier\":\"sugr.examples.sqlclient\""));
        assertTrue(json.contains("\"pubkey\":\"a2V5\""));
    }

    @Test
    void commandLineOverridesWinOverTheConfigFile() throws IOException {
        Files.writeString(appDir.resolve("sugr.config.json"),
                "{ \"name\": \"from-file\", \"appVersion\": \"1.0.0\" }");
        Path libDir = Files.createDirectory(appDir.resolve("lib"));

        Path written = SugrConfig.load(appDir).writeAppMetadata(libDir, "from-flag", "9.9.9");

        String json = Files.readString(written);
        assertTrue(json.contains("\"name\":\"from-flag\""));
        assertTrue(json.contains("\"version\":\"9.9.9\""));
    }

    @Test
    void metadataOmitsUpdaterKeysThatWereNeverSet() throws IOException {
        // The runtime owns the defaults; writing them out here would freeze
        // whatever they happened to be at packaging time into the binary.
        Files.writeString(appDir.resolve("sugr.config.json"), """
                { "name": "app", "appVersion": "1.0.0",
                  "updater": { "endpoints": ["https://e.test/l.json"], "pubkey": "a2V5" } }
                """);
        Path libDir = Files.createDirectory(appDir.resolve("lib"));

        String json = Files.readString(SugrConfig.load(appDir).writeAppMetadata(libDir, null, null));

        assertFalse(json.contains("autoCheck"));
        assertFalse(json.contains("channel"));
        assertFalse(json.contains("windowsInstallMode"));
    }

    @Test
    void metadataIsWrittenEvenWithNoConfigFileAtAll() throws IOException {
        Path libDir = Files.createDirectory(appDir.resolve("lib"));

        String json = Files.readString(SugrConfig.load(appDir).writeAppMetadata(libDir, null, null));

        assertTrue(json.contains("\"name\":\"" + SugrConfig.DEFAULT_NAME + "\""));
        assertTrue(json.contains("\"version\":\"" + SugrConfig.DEFAULT_VERSION + "\""));
        assertFalse(json.contains("updater"));
    }
}
