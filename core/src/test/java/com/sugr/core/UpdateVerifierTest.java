package com.sugr.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signature verification is the only thing standing between an update endpoint
 * and arbitrary code execution on a user's machine, so these tests care as much
 * about what must <em>fail</em> as about what must pass.
 */
class UpdateVerifierTest {

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;
    private static String publicKeyBase64;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        keyPair = generator.generateKeyPair();
        otherKeyPair = generator.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static String sign(KeyPair pair, byte[] payload) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(payload);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static Path artifact(Path dir, String content) throws Exception {
        Path file = dir.resolve("artifact.bin");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void acceptsAGenuineArtifact(@TempDir Path dir) throws Exception {
        byte[] payload = "the real release".getBytes(StandardCharsets.UTF_8);
        Path file = artifact(dir, "the real release");
        String signature = sign(keyPair, payload);

        assertTrue(UpdateVerifier.verifySignature(file, signature, publicKeyBase64));
        UpdateVerifier.verify(file, UpdateVerifier.sha256(file), payload.length, signature, publicKeyBase64);
    }

    @Test
    void rejectsATamperedArtifact(@TempDir Path dir) throws Exception {
        String signature = sign(keyPair, "the real release".getBytes(StandardCharsets.UTF_8));
        Path file = artifact(dir, "the real releasf");

        assertFalse(UpdateVerifier.verifySignature(file, signature, publicKeyBase64));
        UpdateVerifier.VerificationException failure = assertThrows(UpdateVerifier.VerificationException.class,
                () -> UpdateVerifier.verify(file, null, 0, signature, publicKeyBase64));
        assertEquals("SIGNATURE_INVALID", failure.code());
    }

    @Test
    void rejectsASignatureFromADifferentKey(@TempDir Path dir) throws Exception {
        byte[] payload = "the real release".getBytes(StandardCharsets.UTF_8);
        Path file = artifact(dir, "the real release");

        assertFalse(UpdateVerifier.verifySignature(file, sign(otherKeyPair, payload), publicKeyBase64));
    }

    @Test
    void rejectsAMissingSignature(@TempDir Path dir) throws Exception {
        Path file = artifact(dir, "anything");
        for (String signature : new String[]{null, "", "   "}) {
            UpdateVerifier.VerificationException failure = assertThrows(UpdateVerifier.VerificationException.class,
                    () -> UpdateVerifier.verify(file, null, 0, signature, publicKeyBase64));
            assertEquals("SIGNATURE_INVALID", failure.code());
        }
    }

    @Test
    void treatsMalformedInputAsAFailureNotAnError(@TempDir Path dir) throws Exception {
        Path file = artifact(dir, "anything");
        // Garbage must be indistinguishable from a wrong signature - never an
        // exception that a caller might accidentally treat as "inconclusive".
        assertFalse(UpdateVerifier.verifySignature(file, "not base64 !!", publicKeyBase64));
        assertFalse(UpdateVerifier.verifySignature(file, "aGk=", "also not a key"));
        assertFalse(UpdateVerifier.verifySignature(file, "aGk=", ""));
    }

    @Test
    void acceptsABareThirtyTwoByteKeyAsWellAsAnEncodedOne(@TempDir Path dir) throws Exception {
        byte[] payload = "release".getBytes(StandardCharsets.UTF_8);
        Path file = artifact(dir, "release");
        String signature = sign(keyPair, payload);

        // The last 32 bytes of an X.509 Ed25519 key are the raw key itself.
        byte[] encoded = keyPair.getPublic().getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, raw, 0, 32);

        assertTrue(UpdateVerifier.verifySignature(file, signature, Base64.getEncoder().encodeToString(raw)));
    }

    @Test
    void reportsASizeMismatchAsCorruptionNotForgery(@TempDir Path dir) throws Exception {
        Path file = artifact(dir, "short");
        UpdateVerifier.VerificationException failure = assertThrows(UpdateVerifier.VerificationException.class,
                () -> UpdateVerifier.verify(file, null, 9999, "c2ln", publicKeyBase64));
        assertEquals("INTEGRITY_FAILED", failure.code());
    }

    @Test
    void reportsAChecksumMismatchAsCorruption(@TempDir Path dir) throws Exception {
        Path file = artifact(dir, "content");
        UpdateVerifier.VerificationException failure = assertThrows(UpdateVerifier.VerificationException.class,
                () -> UpdateVerifier.verify(file, "0".repeat(64), 0, "c2ln", publicKeyBase64));
        assertEquals("INTEGRITY_FAILED", failure.code());
    }

    @Test
    void checksumComparisonIgnoresCaseAndSurroundingSpace(@TempDir Path dir) throws Exception {
        byte[] payload = "content".getBytes(StandardCharsets.UTF_8);
        Path file = artifact(dir, "content");
        String upper = UpdateVerifier.sha256(file).toUpperCase(java.util.Locale.ROOT);

        UpdateVerifier.verify(file, "  " + upper + " ", payload.length,
                sign(keyPair, payload), publicKeyBase64);
    }

    // ---- manifest signature ----

    private static final String TARGET = "windows-x86_64";
    private static final String APP_ID = "com.example.app";

    private static String manifestJson(String version, String notes, String url, String manifestSignature) {
        return """
                {"version":"%s","notes":"%s","platforms":{"%s":{"format":"binary","url":"%s",
                  "sha256":"abc","size":10,"signature":"c2ln","manifestSignature":"%s"}}}
                """.formatted(version, notes, TARGET, url, manifestSignature);
    }

    /** A manifest signed by {@code pair} the way {@code sugr package} signs one. */
    private static String signedManifest(KeyPair pair, String version, String notes, String url) throws Exception {
        UpdateManifest unsigned = UpdateManifest.parse(manifestJson(version, notes, url, ""), TARGET);
        return manifestJson(version, notes, url, sign(pair, unsigned.signedPayload(APP_ID, TARGET)));
    }

    private static UpdateVerifier.VerificationException manifestFailure(String json) {
        UpdateManifest manifest = UpdateManifest.parse(json, TARGET);
        return assertThrows(UpdateVerifier.VerificationException.class,
                () -> UpdateVerifier.verifyManifest(manifest, TARGET, APP_ID, publicKeyBase64));
    }

    @Test
    void acceptsAGenuinelySignedManifest() throws Exception {
        String json = signedManifest(keyPair, "1.2.0", "Fixes things", "https://e.test/a.zip");
        UpdateVerifier.verifyManifest(UpdateManifest.parse(json, TARGET), TARGET, APP_ID, publicKeyBase64);
    }

    @Test
    void rejectsAManifestWhoseVersionWasRaised() throws Exception {
        // The rollback attack: an old, validly signed artifact relabelled as a
        // newer version so it gets offered - and installed - as an upgrade.
        String json = signedManifest(keyPair, "1.0.0", "Fixes things", "https://e.test/a.zip")
                .replace("\"1.0.0\"", "\"9.9.9\"");
        assertEquals("MANIFEST_SIGNATURE_INVALID", manifestFailure(json).code());
    }

    @Test
    void rejectsAManifestWhoseNotesWereRewritten() throws Exception {
        // Notes land in a native dialog the user trusts as coming from the app.
        String json = signedManifest(keyPair, "1.2.0", "Fixes things", "https://e.test/a.zip")
                .replace("Fixes things", "Call this number for support");
        assertEquals("MANIFEST_SIGNATURE_INVALID", manifestFailure(json).code());
    }

    @Test
    void rejectsAManifestWhoseUrlWasSwapped() throws Exception {
        String json = signedManifest(keyPair, "1.2.0", "n", "https://e.test/a.zip")
                .replace("https://e.test/a.zip", "https://evil.test/a.zip");
        assertEquals("MANIFEST_SIGNATURE_INVALID", manifestFailure(json).code());
    }

    @Test
    void rejectsAnUnsignedOrForeignlySignedManifest() throws Exception {
        assertEquals("MANIFEST_SIGNATURE_INVALID",
                manifestFailure(manifestJson("1.2.0", "n", "https://e.test/a.zip", "")).code());
        assertEquals("MANIFEST_SIGNATURE_INVALID",
                manifestFailure(signedManifest(otherKeyPair, "1.2.0", "n", "https://e.test/a.zip")).code());
    }

    @Test
    void rejectsAManifestSignedForADifferentApp() throws Exception {
        UpdateManifest manifest = UpdateManifest.parse(
                signedManifest(keyPair, "1.2.0", "n", "https://e.test/a.zip"), TARGET);
        assertThrows(UpdateVerifier.VerificationException.class,
                () -> UpdateVerifier.verifyManifest(manifest, TARGET, "com.example.other", publicKeyBase64));
    }

    @Test
    void aValidlySignedOlderReleaseIsStillNotADowngradeTarget() throws Exception {
        // Replaying a genuine old manifest passes the signature check (it *was*
        // signed) - the version comparison is what refuses it.
        UpdateManifest old = UpdateManifest.parse(
                signedManifest(keyPair, "1.0.0", "n", "https://e.test/a.zip"), TARGET);
        UpdateVerifier.verifyManifest(old, TARGET, APP_ID, publicKeyBase64);
        assertEquals(UpdateManifest.Verdict.WOULD_DOWNGRADE,
                old.appliesTo(SemVer.parse("1.1.0"), TARGET, false, 0));
        assertEquals(UpdateManifest.Verdict.UP_TO_DATE,
                old.appliesTo(SemVer.parse("1.0.0"), TARGET, false, 0));
    }

    @Test
    void sha256MatchesAKnownValue(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.bin");
        Files.write(file, new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                UpdateVerifier.sha256(file));
    }
}
