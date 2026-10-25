package com.sugr.cli;

import com.sugr.bridge.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Ed25519 release keys: generating them, storing the private half encrypted at
 * rest, and signing update artifacts with it.
 *
 * <p>The public half is embedded in every build; the private half signs each
 * release and never leaves the developer's machine or CI secret store. Losing
 * it means no further updates can be published for that app - there is no
 * recovery path by design, because a recovery path would be a second way in.
 *
 * <p>The key file is encrypted with AES-256-GCM under a PBKDF2 key derived from
 * a passphrase, so a private key committed or backed up by accident isn't
 * immediately usable. An empty passphrase is allowed (CI often holds the whole
 * file in a secret) and simply means the file's own protection is whatever the
 * storage provides.
 */
final class SigningKeys {

    /** Env var holding the private key - a file path, or the file's contents inline (as CI secrets usually are). */
    static final String KEY_ENV = "SUGR_SIGNING_KEY";
    /** Env var holding the passphrase the key file was encrypted with. */
    static final String KEY_PASSWORD_ENV = "SUGR_SIGNING_KEY_PASSWORD";

    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BITS = 256;

    private SigningKeys() {
    }

    /** A freshly generated release keypair: the file to save, and the public key to publish. */
    record Generated(String keyFileContent, String publicKeyBase64) {
    }

    /** Generates a keypair and encrypts the private half under {@code passphrase} (may be empty). */
    static Generated generate(String passphrase) throws GeneralSecurityException {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        random.nextBytes(salt);
        random.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] sealed = cipher.doFinal(pair.getPrivate().getEncoded());

        Map<String, Json> file = new LinkedHashMap<>();
        file.put("format", Json.of("sugr-signing-key-v1"));
        file.put("algorithm", Json.of("Ed25519"));
        file.put("publicKey", Json.of(publicKeyBase64));
        file.put("kdf", Json.of("PBKDF2WithHmacSHA256"));
        file.put("iterations", Json.of(PBKDF2_ITERATIONS));
        file.put("salt", Json.of(Base64.getEncoder().encodeToString(salt)));
        file.put("nonce", Json.of(Base64.getEncoder().encodeToString(nonce)));
        file.put("privateKey", Json.of(Base64.getEncoder().encodeToString(sealed)));

        return new Generated(Json.object(file).encode() + System.lineSeparator(), publicKeyBase64);
    }

    /**
     * Loads the private key from {@link #KEY_ENV}, which may hold either a path
     * to the key file or its contents directly.
     *
     * @throws IOException with an actionable message when the key isn't
     *                     available or can't be decrypted - a release build that
     *                     silently produced unsigned artifacts would be worse
     *                     than one that fails
     */
    static PrivateKey loadFromEnvironment() throws IOException, GeneralSecurityException {
        String passphrase = System.getenv(KEY_PASSWORD_ENV);
        return decrypt(keyFileFromEnvironment(), passphrase == null ? "" : passphrase);
    }

    /**
     * The key file's contents from {@link #KEY_ENV}, which may hold either a
     * path to it or the file itself (CI secrets usually hold the whole thing).
     */
    static String keyFileFromEnvironment() throws IOException {
        String raw = System.getenv(KEY_ENV);
        if (raw == null || raw.isBlank()) {
            throw new IOException("No signing key: set " + KEY_ENV + " to your key file's path or contents "
                    + "(create one with `sugr keygen`).");
        }
        String content = raw.trim();
        if (content.startsWith("{")) {
            return content;
        }
        Path path = Path.of(content);
        if (!Files.isRegularFile(path)) {
            throw new IOException(KEY_ENV + " points at " + path + ", which isn't a file.");
        }
        return Files.readString(path);
    }

    /** Decrypts a key file's contents. Package-private so tests can round-trip {@link #generate}. */
    static PrivateKey decrypt(String keyFileContent, String passphrase)
            throws IOException, GeneralSecurityException {
        Map<String, Json> file;
        try {
            file = Json.parse(keyFileContent).asObject();
        } catch (RuntimeException e) {
            throw new IOException("The signing key isn't a valid sugr key file: " + e.getMessage());
        }
        Json format = file.get("format");
        if (format == null || !"sugr-signing-key-v1".equals(format.asString())) {
            throw new IOException("Unrecognised signing key format - expected sugr-signing-key-v1.");
        }

        byte[] salt = Base64.getDecoder().decode(file.get("salt").asString());
        byte[] nonce = Base64.getDecoder().decode(file.get("nonce").asString());
        byte[] sealed = Base64.getDecoder().decode(file.get("privateKey").asString());

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] pkcs8;
        try {
            pkcs8 = cipher.doFinal(sealed);
        } catch (GeneralSecurityException e) {
            // GCM authentication failing means the passphrase is wrong or the
            // file was altered; those are indistinguishable and both are "you
            // can't sign with this".
            throw new IOException("Couldn't decrypt the signing key - is " + KEY_PASSWORD_ENV + " correct?");
        }
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    /** The public key recorded inside a key file, so {@code sugr package} can check it matches the config. */
    static String publicKeyOf(String keyFileContent) {
        return Json.parse(keyFileContent).asObject().get("publicKey").asString();
    }

    /** Signs a file's bytes, returning the base64 signature to publish in the manifest. */
    static String sign(Path file, PrivateKey privateKey) throws IOException, GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        byte[] buffer = new byte[64 * 1024];
        try (var in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                signer.update(buffer, 0, read);
            }
        }
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    /** Signs an in-memory payload - the manifest's canonical fields - returning base64. */
    static String sign(byte[] payload, PrivateKey privateKey) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(payload);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static SecretKey deriveKey(String passphrase, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
        byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(derived, "AES");
    }

    /** Lowercase hex SHA-256 of a file - the checksum published alongside each artifact. */
    static String sha256(Path file) throws IOException {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (var in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** Reads a key file, for commands that only need the public half. */
    static String read(Path keyFile) throws IOException {
        return new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8);
    }
}
