package com.sugr.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The updater's "is this newer?" decision is entirely {@link SemVer#compareTo},
 * so the ordering rules - especially prerelease precedence - are worth pinning
 * down: getting them wrong either strands users on an old build or pushes a
 * beta at everyone.
 */
class SemVerTest {

    @Test
    void parsesAFullVersion() {
        SemVer v = SemVer.parse("1.2.3");
        assertEquals(1, v.major());
        assertEquals(2, v.minor());
        assertEquals(3, v.patch());
        assertNull(v.prerelease());
        assertNull(v.build());
    }

    @Test
    void padsShortVersions() {
        assertEquals(SemVer.parse("1.0.0"), SemVer.parse("1"));
        assertEquals(SemVer.parse("1.2.0"), SemVer.parse("1.2"));
    }

    @Test
    void ignoresALeadingV() {
        assertEquals(SemVer.parse("1.2.3"), SemVer.parse("v1.2.3"));
    }

    @Test
    void parsesPrereleaseAndBuildMetadata() {
        SemVer v = SemVer.parse("1.0.0-beta.2+build.77");
        assertEquals("beta.2", v.prerelease());
        assertEquals("build.77", v.build());
        assertTrue(v.isPrerelease());
    }

    @Test
    void comparesNumericallyNotLexically() {
        assertTrue(SemVer.parse("1.0.10").compareTo(SemVer.parse("1.0.9")) > 0);
        assertTrue(SemVer.parse("1.10.0").compareTo(SemVer.parse("1.9.0")) > 0);
        assertTrue(SemVer.parse("2.0.0").compareTo(SemVer.parse("10.0.0")) < 0);
    }

    @Test
    void aPrereleaseSortsBeforeItsRelease() {
        assertTrue(SemVer.parse("1.0.0-beta").compareTo(SemVer.parse("1.0.0")) < 0);
        assertTrue(SemVer.parse("1.0.0").compareTo(SemVer.parse("1.0.0-rc.1")) > 0);
    }

    @Test
    void ordersPrereleaseIdentifiersBySemverRules() {
        // The canonical example from semver.org's rule 11.
        String[] ascending = {
                "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta",
                "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0"
        };
        for (int i = 0; i < ascending.length - 1; i++) {
            SemVer lower = SemVer.parse(ascending[i]);
            SemVer higher = SemVer.parse(ascending[i + 1]);
            assertTrue(lower.compareTo(higher) < 0, ascending[i] + " should sort before " + ascending[i + 1]);
            assertTrue(higher.compareTo(lower) > 0, ascending[i + 1] + " should sort after " + ascending[i]);
        }
    }

    @Test
    void numericPrereleaseIdentifiersCompareNumerically() {
        assertTrue(SemVer.parse("1.0.0-2").compareTo(SemVer.parse("1.0.0-10")) < 0);
    }

    @Test
    void handlesPrereleaseIdentifiersTooLongForAnInt() {
        // A build-number identifier can outgrow Integer.MAX_VALUE; comparing
        // these must not overflow or throw.
        assertTrue(SemVer.parse("1.0.0-99999999999999999999")
                .compareTo(SemVer.parse("1.0.0-100000000000000000000")) < 0);
    }

    @Test
    void buildMetadataDoesNotAffectOrdering() {
        assertEquals(0, SemVer.parse("1.0.0+a").compareTo(SemVer.parse("1.0.0+b")));
    }

    @Test
    void rejectsGarbage() {
        assertNull(SemVer.parseOrNull("not-a-version"));
        assertNull(SemVer.parseOrNull("1.2.3.4"));
        assertNull(SemVer.parseOrNull("1..3"));
        assertNull(SemVer.parseOrNull("1.0.0-"));
        assertNull(SemVer.parseOrNull(""));
        assertNull(SemVer.parseOrNull(null));
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse("nope"));
    }

    @Test
    void roundTripsThroughToString() {
        for (String text : new String[]{"1.2.3", "1.0.0-beta.2", "2.0.0-rc.1+sha.abc"}) {
            assertEquals(text, SemVer.parse(text).toString());
        }
    }

    @Test
    void theUnknownVersionIsAPrereleaseOfZero() {
        // Anything a real manifest publishes must beat a build with no embedded
        // metadata, so an unconfigured app still updates itself.
        assertTrue(SemVer.UNKNOWN.compareTo(SemVer.parse("0.0.1")) < 0);
        assertTrue(SemVer.UNKNOWN.compareTo(SemVer.parse("0.0.0")) < 0);
        assertFalse(SemVer.UNKNOWN.toString().isEmpty());
    }
}
