package com.sugr.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Checks that a downloaded update is the exact file the developer published.
 *
 * <p>Three separate checks, doing different jobs:
 *
 * <ul>
 *   <li>{@link #verifyManifest} runs first, before anything in the manifest
 *       is believed. Its Ed25519 signature covers the version, the URLs, the
 *       checksum and the release notes (see {@link UpdateManifest#signedPayload}),
 *       so an endpoint can't relabel an old signed build as a newer one to roll
 *       users back, or put its own text in the update dialog.</li>
 *   <li>{@link #sha256} catches <em>accidents</em> - a truncated download, a
 *       corrupted proxy cache - and lets the updater report "the file is
 *       damaged, retry" instead of a confusing signature failure.</li>
 *   <li>{@link #verify} checks a second Ed25519 signature over the artifact's
 *       own bytes, so what gets installed is exactly what was signed regardless
 *       of what served it. This is why the updater refuses to run at all
 *       without a configured public key.</li>
 * </ul>
 *
 * <p>Ed25519 has been in the JDK since 15 ({@code Signature.getInstance("Ed25519")}),
 * so this needs no third-party crypto library and works in a native-image
 * binary with no extra configuration.
 */
final class UpdateVerifier {

    /**
     * DER prefix of an X.509 {@code SubjectPublicKeyInfo} wrapping a raw
     * Ed25519 key: {@code SEQUENCE { SEQUENCE { OID 1.3.101.112 }, BIT STRING }}.
     * Prepending it lets a bare 32-byte key be accepted alongside a full
     * encoded one, so hand-copied keys work either way.
     */
    private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private static final int RAW_PUBLIC_KEY_LENGTH = 32;

    private UpdateVerifier() {
    }

    /** Thrown when an artifact isn't what the manifest says it is. Never recoverable by retrying. */
    static final class VerificationException extends Exception {
        private final String code;

        VerificationException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /**
     * Runs every check the manifest makes possible, in cheapest-first order so
     * a truncated download is reported as such rather than as a bad signature.
     *
     * @param expectedSha256 optional - skipped when the manifest omits it
     * @param expectedSize   optional - skipped when 0
     */
    static void verify(Path artifact, String expectedSha256, long expectedSize,
                       String signatureBase64, String publicKeyBase64) throws VerificationException, IOException {
        long actualSize = Files.size(artifact);
        if (expectedSize > 0 && actualSize != expectedSize) {
            throw new VerificationException("INTEGRITY_FAILED",
                    "Downloaded " + actualSize + " bytes but the manifest declares " + expectedSize);
        }

        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            String actual = sha256(artifact);
            if (!constantTimeEquals(actual, expectedSha256.trim().toLowerCase(java.util.Locale.ROOT))) {
                throw new VerificationException("INTEGRITY_FAILED",
                        "SHA-256 mismatch: expected " + expectedSha256 + " but got " + actual);
            }
        }

        if (signatureBase64 == null || signatureBase64.isBlank()) {
            throw new VerificationException("SIGNATURE_INVALID",
                    "The manifest carries no signature for this artifact");
        }
        if (!verifySignature(artifact, signatureBase64, publicKeyBase64)) {
            throw new VerificationException("SIGNATURE_INVALID",
                    "The artifact's Ed25519 signature doesn't match the configured public key");
        }
    }

    /**
     * Checks the {@code manifestSignature} of {@code manifest}'s entry for
     * {@code target}. Must pass before the manifest's version, notes or URLs
     * are used for anything - including deciding whether it's an update.
     *
     * @param identifier this app's identifier, which the signature is bound to
     */
    static void verifyManifest(UpdateManifest manifest, String target, String identifier,
                               String publicKeyBase64) throws VerificationException {
        UpdateManifest.Artifact artifact = manifest.artifactFor(target);
        if (artifact == null) {
            throw new VerificationException("MANIFEST_SIGNATURE_INVALID", "The manifest has no entry for " + target);
        }
        String signature = artifact.manifestSignature();
        if (signature == null || signature.isBlank()) {
            throw new VerificationException("MANIFEST_SIGNATURE_INVALID",
                    "The manifest entry for " + target + " carries no manifestSignature - "
                            + "re-publish it with a current `sugr package`");
        }
        if (!verifySignature(manifest.signedPayload(identifier, target), signature, publicKeyBase64)) {
            throw new VerificationException("MANIFEST_SIGNATURE_INVALID",
                    "The manifest's Ed25519 signature doesn't match the configured public key - "
                            + "it was altered, or published for a different app");
        }
    }

    /** {@link #verifySignature(Path, String, String)} over an in-memory payload. */
    static boolean verifySignature(byte[] payload, String signatureBase64, String publicKeyBase64) {
        try {
            PublicKey key = parsePublicKey(publicKeyBase64);
            byte[] signature = Base64.getDecoder().decode(signatureBase64.trim());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            return verifier.verify(signature);
        } catch (RuntimeException | GeneralSecurityException e) {
            return false;
        }
    }

    /** Lowercase hex SHA-256 of a file, streamed so a 100MB artifact doesn't land in memory. */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * True when {@code signatureBase64} is a valid Ed25519 signature over
     * {@code artifact}'s bytes under {@code publicKeyBase64}. Any malformed
     * input is a failed verification, not an exception - a corrupt key or
     * signature must never be distinguishable from a wrong one.
     */
    static boolean verifySignature(Path artifact, String signatureBase64, String publicKeyBase64) {
        PublicKey key;
        byte[] signature;
        try {
            key = parsePublicKey(publicKeyBase64);
            signature = Base64.getDecoder().decode(signatureBase64.trim());
        } catch (RuntimeException | GeneralSecurityException e) {
            return false;
        }

        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(artifact)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    verifier.update(buffer, 0, read);
                }
            }
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IOException e) {
            return false;
        }
    }

    /**
     * Accepts either a full base64 X.509 encoded key (what {@code sugr keygen}
     * prints) or a bare base64 32-byte key, so a key copied from another tool
     * still works.
     */
    static PublicKey parsePublicKey(String publicKeyBase64) throws GeneralSecurityException {
        if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
            throw new java.security.InvalidKeyException("No public key configured");
        }
        byte[] decoded = Base64.getDecoder().decode(publicKeyBase64.trim());
        byte[] spki;
        if (decoded.length == RAW_PUBLIC_KEY_LENGTH) {
            spki = new byte[ED25519_SPKI_PREFIX.length + decoded.length];
            System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
            System.arraycopy(decoded, 0, spki, ED25519_SPKI_PREFIX.length, decoded.length);
        } else {
            spki = decoded;
        }
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
