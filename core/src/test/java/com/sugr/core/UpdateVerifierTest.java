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

    @Test
    void sha256MatchesAKnownValue(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.bin");
        Files.write(file, new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                UpdateVerifier.sha256(file));
    }
}
