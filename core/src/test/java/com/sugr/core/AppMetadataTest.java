package com.sugr.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the {@code /sugr/app.json} resource the CLI generates from {@code sugr.config.json}. */
class AppMetadataTest {

    @Test
    void readsTheGeneratedDocument() {
        AppMetadata metadata = AppMetadata.parse("""
                {"name":"sql-client","version":"1.0.3","identifier":"sugr.examples.sqlclient"}
                """);

        assertEquals("sql-client", metadata.name());
        assertEquals("1.0.3", metadata.version().toString());
        assertEquals("sugr.examples.sqlclient", metadata.identifier());
        assertNull(metadata.updater());
    }

    @Test
    void identifierFallsBackToTheName() {
        assertEquals("thing", AppMetadata.parse("{\"name\":\"thing\",\"version\":\"1.0.0\"}").identifier());
    }

    @Test
    void anUnparseableVersionBecomesTheDevPlaceholder() {
        // A bad version must not crash startup - the app just reports that it
        // doesn't know, and the updater treats every release as newer.
        assertEquals(SemVer.UNKNOWN, AppMetadata.parse("{\"name\":\"a\",\"version\":\"banana\"}").version());
        assertEquals(SemVer.UNKNOWN, AppMetadata.parse("{\"name\":\"a\"}").version());
    }

    @Test
    void readsUpdaterSettingsWithDefaults() {
        AppMetadata.UpdaterSettings updater = AppMetadata.parse("""
                {"name":"a","version":"1.0.0","updater":{
                  "endpoints":["https://example.test/latest.json"],
                  "pubkey":"a2V5"}}
                """).updater();

        assertNotNull(updater);
        assertEquals(1, updater.endpoints().size());
        assertEquals("a2V5", updater.pubkey());
        assertEquals("stable", updater.channel());
        assertTrue(updater.autoCheck());
        assertTrue(updater.prompt());
        assertFalse(updater.allowDowngrade());
        assertFalse(updater.allowInsecure());
        assertTrue(updater.isUsable());
        assertNull(updater.unusableReason());
    }

    @Test
    void honoursExplicitUpdaterSettings() {
        AppMetadata.UpdaterSettings updater = AppMetadata.parse("""
                {"name":"a","version":"1.0.0","updater":{
                  "endpoints":["https://example.test/beta.json"],
                  "pubkey":"a2V5","channel":"beta","autoCheck":false,"prompt":false,
                  "allowDowngrade":true,"allowInsecure":true}}
                """).updater();

        assertEquals("beta", updater.channel());
        assertFalse(updater.autoCheck());
        assertFalse(updater.prompt());
        assertTrue(updater.allowDowngrade());
        assertTrue(updater.allowInsecure());
    }

    @Test
    void endpointsWithoutAPublicKeyAreRefused() {
        // The whole safety model rests on the signature, so "endpoints but no
        // key" must be treated as misconfiguration rather than quietly
        // downgraded to an unverified download.
        AppMetadata.UpdaterSettings updater = AppMetadata.parse("""
                {"name":"a","version":"1.0.0","updater":{"endpoints":["https://example.test/l.json"]}}
                """).updater();

        assertFalse(updater.isUsable());
        assertNotNull(updater.unusableReason());
        assertTrue(updater.unusableReason().contains("pubkey"));
    }

    @Test
    void aKeyWithNoEndpointsIsAlsoUnusable() {
        AppMetadata.UpdaterSettings updater = AppMetadata.parse("""
                {"name":"a","version":"1.0.0","updater":{"pubkey":"a2V5"}}
                """).updater();

        assertFalse(updater.isUsable());
        assertTrue(updater.unusableReason().contains("endpoints"));
    }

    @Test
    void expandsEndpointPlaceholders() {
        String expanded = Updater.expandPlaceholders(
                "https://x.test/{{target}}/{{os}}/{{arch}}/{{currentVersion}}/latest-{{channel}}.json",
                SemVer.parse("1.2.3"), "windows-x86_64", "beta");

        assertEquals("https://x.test/windows-x86_64/" + UpdateManifest.currentOs() + "/"
                + UpdateManifest.currentArch() + "/1.2.3/latest-beta.json", expanded);
    }

    @Test
    void alsoAcceptsTauriSpellingOfTheVersionPlaceholder() {
        assertEquals("https://x.test/1.2.3",
                Updater.expandPlaceholders("https://x.test/{{current_version}}",
                        SemVer.parse("1.2.3"), "windows-x86_64", "stable"));
    }

    @Test
    void allowInsecureIsIgnoredInPackagedBuilds() {
        assertTrue(Updater.effectiveAllowInsecure(true, AppInstall.Layout.UNPACKAGED));
        assertFalse(Updater.effectiveAllowInsecure(true, AppInstall.Layout.EXECUTABLE));
        assertFalse(Updater.effectiveAllowInsecure(true, AppInstall.Layout.APP_BUNDLE));
        assertFalse(Updater.effectiveAllowInsecure(false, AppInstall.Layout.UNPACKAGED));
    }
}
