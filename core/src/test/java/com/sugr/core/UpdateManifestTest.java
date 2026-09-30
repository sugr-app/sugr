package com.sugr.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parsing and the "does this release apply to me?" decision - the two things
 * that stand between a published manifest and replacing a user's application.
 */
class UpdateManifestTest {

    private static final String TARGET = "windows-x86_64";

    private static String manifest(String extraTopLevel) {
        return """
                {
                  "version": "1.2.0",
                  "pubDate": "2026-09-09T10:00:00Z",
                  "notes": "Fixes things",
                  %s
                  "platforms": {
                    "windows-x86_64": {
                      "format": "binary",
                      "url": "https://example.test/app-1.2.0-windows-x86_64.zip",
                      "sha256": "abc123",
                      "size": 1024,
                      "signature": "c2ln"
                    },
                    "darwin-aarch64": {
                      "format": "appbundle",
                      "url": "https://example.test/app-1.2.0-darwin-aarch64.tar.gz",
                      "signature": "c2ln"
                    }
                  }
                }
                """.formatted(extraTopLevel);
    }

    @Test
    void parsesAPlatformKeyedManifest() {
        UpdateManifest m = UpdateManifest.parse(manifest(""), TARGET);

        assertEquals(SemVer.parse("1.2.0"), m.version());
        assertEquals("Fixes things", m.notes());
        assertEquals("2026-09-09T10:00:00Z", m.pubDate());
        assertEquals(UpdateManifest.DEFAULT_ROLLOUT, m.rolloutPercentage());
        assertEquals(2, m.platforms().size());

        UpdateManifest.Artifact windows = m.artifactFor(TARGET);
        assertNotNull(windows);
        assertEquals(UpdateManifest.Format.BINARY, windows.format());
        assertEquals(1024, windows.size());
        assertEquals("abc123", windows.sha256());
        assertEquals(UpdateManifest.Format.APP_BUNDLE, m.artifactFor("darwin-aarch64").format());
    }

    @Test
    void platformKeysAreCaseInsensitive() {
        UpdateManifest m = UpdateManifest.parse(manifest(""), TARGET);
        assertNotNull(m.artifactFor("Windows-X86_64"));
    }

    @Test
    void acceptsTheFlatSinglePlatformFormADynamicServerReturns() {
        String flat = """
                {"version":"1.2.0","url":"https://example.test/a.zip","signature":"c2ln"}
                """;
        UpdateManifest m = UpdateManifest.parse(flat, TARGET);
        assertNotNull(m.artifactFor(TARGET));
        assertNull(m.artifactFor("linux-x86_64"));
    }

    @Test
    void dropsArtifactsWithNoSignature() {
        // An unsigned entry could never be installed, so it must not be
        // reported as an available update that then always fails.
        String unsigned = """
                {"version":"1.2.0","platforms":{"windows-x86_64":{"url":"https://example.test/a.zip"}}}
                """;
        assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(unsigned, TARGET));
    }

    @Test
    void rejectsAManifestWithNoUsableVersion() {
        assertThrows(IllegalArgumentException.class, () ->
                UpdateManifest.parse("{\"platforms\":{}}", TARGET));
        assertThrows(IllegalArgumentException.class, () ->
                UpdateManifest.parse("{\"version\":\"banana\",\"url\":\"u\",\"signature\":\"s\"}", TARGET));
    }

    @Test
    void appliesWhenNewer() {
        UpdateManifest m = UpdateManifest.parse(manifest(""), TARGET);
        assertEquals(UpdateManifest.Verdict.APPLIES,
                m.appliesTo(SemVer.parse("1.1.0"), TARGET, false, 0));
    }

    @Test
    void reportsUpToDateForTheSameVersion() {
        UpdateManifest m = UpdateManifest.parse(manifest(""), TARGET);
        assertEquals(UpdateManifest.Verdict.UP_TO_DATE,
                m.appliesTo(SemVer.parse("1.2.0"), TARGET, false, 0));
    }

    @Test
    void refusesToDowngradeUnlessAllowed() {
        UpdateManifest m = UpdateManifest.parse(manifest(""), TARGET);
        assertEquals(UpdateManifest.Verdict.WOULD_DOWNGRADE,
                m.appliesTo(SemVer.parse("2.0.0"), TARGET, false, 0));
        assertEquals(UpdateManifest.Verdict.APPLIES,
                m.appliesTo(SemVer.parse("2.0.0"), TARGET, true, 0));
    }

    @Test
    void reportsNoArtifactForAnUnlistedPlatform() {
        UpdateManifest m = UpdateManifest.parse(manifest(""), TARGET);
        assertEquals(UpdateManifest.Verdict.NO_ARTIFACT,
                m.appliesTo(SemVer.parse("1.0.0"), "linux-x86_64", false, 0));
    }

    @Test
    void gatesOnMinVersion() {
        UpdateManifest m = UpdateManifest.parse(manifest("\"minVersion\": \"1.1.0\","), TARGET);
        assertEquals(UpdateManifest.Verdict.BELOW_MIN_VERSION,
                m.appliesTo(SemVer.parse("1.0.0"), TARGET, false, 0));
        assertEquals(UpdateManifest.Verdict.APPLIES,
                m.appliesTo(SemVer.parse("1.1.0"), TARGET, false, 0));
    }

    @Test
    void stagedRolloutIncludesLowBucketsOnly() {
        UpdateManifest m = UpdateManifest.parse(manifest("\"rolloutPercentage\": 10,"), TARGET);
        SemVer current = SemVer.parse("1.0.0");

        assertEquals(UpdateManifest.Verdict.APPLIES, m.appliesTo(current, TARGET, false, 0));
        assertEquals(UpdateManifest.Verdict.APPLIES, m.appliesTo(current, TARGET, false, 9));
        assertEquals(UpdateManifest.Verdict.NOT_IN_ROLLOUT, m.appliesTo(current, TARGET, false, 10));
        assertEquals(UpdateManifest.Verdict.NOT_IN_ROLLOUT, m.appliesTo(current, TARGET, false, 99));
    }

    @Test
    void rolloutBoundariesAreAllOrNothing() {
        SemVer current = SemVer.parse("1.0.0");

        UpdateManifest none = UpdateManifest.parse(manifest("\"rolloutPercentage\": 0,"), TARGET);
        assertEquals(UpdateManifest.Verdict.NOT_IN_ROLLOUT, none.appliesTo(current, TARGET, false, 0));

        UpdateManifest all = UpdateManifest.parse(manifest("\"rolloutPercentage\": 100,"), TARGET);
        assertEquals(UpdateManifest.Verdict.APPLIES, all.appliesTo(current, TARGET, false, 99));
    }

    @Test
    void clampsOutOfRangeRolloutValues() {
        assertEquals(100, UpdateManifest.parse(manifest("\"rolloutPercentage\": 500,"), TARGET)
                .rolloutPercentage());
        assertEquals(0, UpdateManifest.parse(manifest("\"rolloutPercentage\": -20,"), TARGET)
                .rolloutPercentage());
    }

    @Test
    void describesTheCurrentPlatformInTheDocumentedShape() {
        String target = UpdateManifest.currentTarget();
        assertEquals(UpdateManifest.currentOs() + "-" + UpdateManifest.currentArch(), target);
        assertEquals(2, target.split("-", 2).length);
    }

    @Test
    void installerFallbackIsOnlyOfferedWhenFullySpecified() {
        String withInstaller = """
                {"version":"1.2.0","platforms":{"windows-x86_64":{
                  "url":"https://example.test/a.zip","signature":"c2ln",
                  "installerUrl":"https://example.test/Setup.exe","installerSignature":"c2ln2"}}}
                """;
        assertEquals(true, UpdateManifest.parse(withInstaller, TARGET).artifactFor(TARGET).hasInstaller());
        assertEquals(false, UpdateManifest.parse(manifest(""), TARGET).artifactFor(TARGET).hasInstaller());
    }
}
