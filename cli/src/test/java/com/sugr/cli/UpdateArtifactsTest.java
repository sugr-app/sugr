package com.sugr.cli;

import com.sugr.bridge.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The release side of updates: keys, signatures, and the manifest that ties
 * them together. The end-to-end assertion that matters is
 * {@link #theSignatureInTheManifestVerifiesAgainstThePublishedPublicKey} - if
 * that ever breaks, every installed app rejects every update.
 */
class UpdateArtifactsTest {

    private static Map<String, Json> manifestOf(Path file) throws Exception {
        return Json.parse(Files.readString(file)).asObject();
    }

    private static Map<String, Json> platformEntry(Path manifest) throws Exception {
        Map<String, Json> platforms = manifestOf(manifest).get("platforms").asObject();
        assertEquals(1, platforms.size(), "a per-host build describes exactly one target");
        return platforms.values().iterator().next().asObject();
    }

    private static SigningKeys.Generated keyPair(String passphrase) throws Exception {
        return SigningKeys.generate(passphrase);
    }

    // ---- key files ----

    @Test
    void generatesAKeyFileThatRoundTrips() throws Exception {
        SigningKeys.Generated generated = keyPair("hunter2");

        assertEquals(generated.publicKeyBase64(), SigningKeys.publicKeyOf(generated.keyFileContent()));
        assertNotNull(SigningKeys.decrypt(generated.keyFileContent(), "hunter2"));
    }

    @Test
    void refusesTheWrongPassphrase() throws Exception {
        SigningKeys.Generated generated = keyPair("hunter2");

        Exception failure = assertThrows(Exception.class,
                () -> SigningKeys.decrypt(generated.keyFileContent(), "wrong"));
        assertTrue(failure.getMessage().contains(SigningKeys.KEY_PASSWORD_ENV));
    }

    @Test
    void supportsAnEmptyPassphraseForCiSecrets() throws Exception {
        SigningKeys.Generated generated = keyPair("");
        assertNotNull(SigningKeys.decrypt(generated.keyFileContent(), ""));
    }

    @Test
    void neverStoresThePrivateKeyInTheClear() throws Exception {
        SigningKeys.Generated generated = keyPair("pw");
        PrivateKey key = SigningKeys.decrypt(generated.keyFileContent(), "pw");

        String encodedPrivate = Base64.getEncoder().encodeToString(key.getEncoded());
        assertFalse(generated.keyFileContent().contains(encodedPrivate),
                "the key file must not contain the unencrypted private key");
    }

    @Test
    void rejectsAnUnrecognisedKeyFile() {
        assertThrows(Exception.class, () -> SigningKeys.decrypt("{\"format\":\"something-else\"}", ""));
        assertThrows(Exception.class, () -> SigningKeys.decrypt("not json at all", ""));
    }

    @Test
    void twoGeneratedKeysDiffer() throws Exception {
        assertFalse(keyPair("").publicKeyBase64().equals(keyPair("").publicKeyBase64()));
    }

    // ---- artifacts and manifest ----

    @Test
    void writesArtifactSignatureAndManifest(@TempDir Path dir) throws Exception {
        SigningKeys.Generated generated = keyPair("");
        Path binary = dir.resolve("sql-client.exe");
        Files.writeString(binary, "pretend this is a native binary");
        Path dest = Files.createDirectory(dir.resolve("dist"));

        UpdateArtifacts.Result result = UpdateArtifacts.write(dest, "sql-client", "1.0.4", "stable",
                binary, "sql-client.exe", null, "https://example.test/download", "Fixes things",
                null, null, SigningKeys.decrypt(generated.keyFileContent(), ""));

        assertTrue(Files.isRegularFile(result.artifact()));
        assertTrue(Files.isRegularFile(result.signature()));
        assertEquals("latest.json", result.manifest().getFileName().toString());
        assertEquals("sql-client-1.0.4-" + UpdateArtifacts.hostTarget() + ".zip",
                result.artifact().getFileName().toString());

        Map<String, Json> manifest = manifestOf(result.manifest());
        assertEquals("1.0.4", manifest.get("version").asString());
        assertEquals("Fixes things", manifest.get("notes").asString());
        assertNotNull(manifest.get("pubDate"));

        Map<String, Json> entry = platformEntry(result.manifest());
        assertEquals("binary", entry.get("format").asString());
        assertEquals("https://example.test/download/" + result.artifact().getFileName(),
                entry.get("url").asString());
        assertEquals(Files.size(result.artifact()), (long) entry.get("size").asNumber());
        assertEquals(64, entry.get("sha256").asString().length());
    }

    @Test
    void theSignatureInTheManifestVerifiesAgainstThePublishedPublicKey(@TempDir Path dir) throws Exception {
        SigningKeys.Generated generated = keyPair("pw");
        Path binary = dir.resolve("app.exe");
        Files.writeString(binary, "release bytes");
        Path dest = Files.createDirectory(dir.resolve("dist"));

        UpdateArtifacts.Result result = UpdateArtifacts.write(dest, "app", "2.0.0", "stable",
                binary, "app.exe", null, "https://e.test", null, null, null,
                SigningKeys.decrypt(generated.keyFileContent(), "pw"));

        String signature = platformEntry(result.manifest()).get("signature").asString();
        assertTrue(verify(result.artifact(), signature, generated.publicKeyBase64()),
                "the published signature must verify against the published public key");

        // ...and must not verify once a byte changes.
        byte[] bytes = Files.readAllBytes(result.artifact());
        bytes[bytes.length - 1] ^= 0x01;
        Path tampered = dir.resolve("tampered.zip");
        Files.write(tampered, bytes);
        assertFalse(verify(tampered, signature, generated.publicKeyBase64()));
    }

    @Test
    void theSidecarSignatureFileMatchesTheManifest(@TempDir Path dir) throws Exception {
        SigningKeys.Generated generated = keyPair("");
        Path binary = dir.resolve("app.exe");
        Files.writeString(binary, "bytes");
        Path dest = Files.createDirectory(dir.resolve("dist"));

        UpdateArtifacts.Result result = UpdateArtifacts.write(dest, "app", "1.0.0", "stable",
                binary, "app.exe", null, "https://e.test", null, null, null,
                SigningKeys.decrypt(generated.keyFileContent(), ""));

        assertEquals(platformEntry(result.manifest()).get("signature").asString(),
                Files.readString(result.signature()).trim());
    }

    @Test
    void theArtifactHoldsTheBinaryUnderItsInstalledName(@TempDir Path dir) throws Exception {
        // native-image may name its output differently from the installed file;
        // the runtime renames the entry over the installed executable, so the
        // entry name has to be the installed one.
        SigningKeys.Generated generated = keyPair("");
        Path binary = dir.resolve("weird-imagename");
        byte[] content = "the binary".getBytes(StandardCharsets.UTF_8);
        Files.write(binary, content);
        Path dest = Files.createDirectory(dir.resolve("dist"));

        UpdateArtifacts.Result result = UpdateArtifacts.write(dest, "app", "1.0.0", "stable",
                binary, "app.exe", null, "https://e.test", null, null, null,
                SigningKeys.decrypt(generated.keyFileContent(), ""));

        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(result.artifact()))) {
            ZipEntry entry = in.getNextEntry();
            assertEquals("app.exe", entry.getName());
            assertArrayEquals(content, in.readAllBytes());
            assertNull(in.getNextEntry(), "a binary artifact holds exactly one file");
        }
    }

    @Test
    void recordsAnInstallerFallbackWhenOneWasBuilt(@TempDir Path dir) throws Exception {
        SigningKeys.Generated generated = keyPair("");
        Path binary = dir.resolve("app.exe");
        Files.writeString(binary, "bytes");
        Path dest = Files.createDirectory(dir.resolve("dist"));
        Path installer = dest.resolve("app-1.0.0-Setup.exe");
        Files.writeString(installer, "installer bytes");

        UpdateArtifacts.Result result = UpdateArtifacts.write(dest, "app", "1.0.0", "stable",
                binary, "app.exe", installer, "https://e.test", null, null, null,
                SigningKeys.decrypt(generated.keyFileContent(), ""));

        Map<String, Json> entry = platformEntry(result.manifest());
        assertEquals("https://e.test/app-1.0.0-Setup.exe", entry.get("installerUrl").asString());
        assertTrue(verify(installer, entry.get("installerSignature").asString(),
                generated.publicKeyBase64()), "the installer fallback must be signed too");
    }

    @Test
    void omitsTheInstallerFallbackWhenThereIsNone(@TempDir Path dir) throws Exception {
        SigningKeys.Generated generated = keyPair("");
        Path binary = dir.resolve("app.exe");
        Files.writeString(binary, "bytes");
        Path dest = Files.createDirectory(dir.resolve("dist"));

        UpdateArtifacts.Result result = UpdateArtifacts.write(dest, "app", "1.0.0", "stable",
                binary, "app.exe", null, "https://e.test", null, null, null,
                SigningKeys.decrypt(generated.keyFileContent(), ""));

        assertNull(platformEntry(result.manifest()).get("installerUrl"));
    }

    @Test
    void recordsMinVersionAndRolloutOnlyWhenGiven(@TempDir Path dir) throws Exception {
        SigningKeys.Generated generated = keyPair("");
        Path binary = dir.resolve("app.exe");
        Files.writeString(binary, "bytes");
        PrivateKey key = SigningKeys.decrypt(generated.keyFileContent(), "");

        Path withBoth = Files.createDirectory(dir.resolve("a"));
        UpdateArtifacts.Result gated = UpdateArtifacts.write(withBoth, "app", "1.0.0", "stable",
                binary, "app.exe", null, "https://e.test", null, "0.9.0", 25, key);
        assertEquals("0.9.0", manifestOf(gated.manifest()).get("minVersion").asString());
        assertEquals(25, (int) manifestOf(gated.manifest()).get("rolloutPercentage").asNumber());

        Path without = Files.createDirectory(dir.resolve("b"));
        UpdateArtifacts.Result plain = UpdateArtifacts.write(without, "app", "1.0.0", "stable",
                binary, "app.exe", null, "https://e.test", null, null, null, key);
        assertNull(manifestOf(plain.manifest()).get("minVersion"));
        assertNull(manifestOf(plain.manifest()).get("rolloutPercentage"));
    }

    @Test
    void namesTheManifestAfterTheChannel() {
        assertEquals("latest.json", UpdateArtifacts.manifestName("stable"));
        assertEquals("latest.json", UpdateArtifacts.manifestName(null));
        assertEquals("latest.json", UpdateArtifacts.manifestName(""));
        assertEquals("latest-beta.json", UpdateArtifacts.manifestName("beta"));
    }

    @Test
    void fallsBackToBareFilenamesWithNoBaseUrl(@TempDir Path dir) throws Exception {
        SigningKeys.Generated generated = keyPair("");
        Path binary = dir.resolve("app.exe");
        Files.writeString(binary, "bytes");
        Path dest = Files.createDirectory(dir.resolve("dist"));

        UpdateArtifacts.Result result = UpdateArtifacts.write(dest, "app", "1.0.0", "stable",
                binary, "app.exe", null, null, null, null, null,
                SigningKeys.decrypt(generated.keyFileContent(), ""));

        assertEquals(result.artifact().getFileName().toString(),
                platformEntry(result.manifest()).get("url").asString());
    }

    @Test
    void toleratesATrailingSlashOnTheBaseUrl(@TempDir Path dir) throws Exception {
        SigningKeys.Generated generated = keyPair("");
        Path binary = dir.resolve("app.exe");
        Files.writeString(binary, "bytes");
        Path dest = Files.createDirectory(dir.resolve("dist"));

        UpdateArtifacts.Result result = UpdateArtifacts.write(dest, "app", "1.0.0", "stable",
                binary, "app.exe", null, "https://e.test/dl/", null, null, null,
                SigningKeys.decrypt(generated.keyFileContent(), ""));

        assertFalse(platformEntry(result.manifest()).get("url").asString().contains("//app-"));
    }

    @Test
    void hostTargetLooksLikeAManifestPlatformKey() {
        assertTrue(UpdateArtifacts.hostTarget().matches("(windows|darwin|linux)-(x86_64|aarch64|i686)"),
                "got " + UpdateArtifacts.hostTarget());
    }

    @Test
    void theManifestIsWrittenForPeopleToRead(@TempDir Path dir) throws Exception {
        SigningKeys.Generated generated = keyPair("");
        Path binary = dir.resolve("app.exe");
        Files.writeString(binary, "bytes");
        Path dest = Files.createDirectory(dir.resolve("dist"));

        UpdateArtifacts.Result result = UpdateArtifacts.write(dest, "app", "1.0.0", "stable",
                binary, "app.exe", null, "https://e.test", null, null, null,
                SigningKeys.decrypt(generated.keyFileContent(), ""));

        String text = Files.readString(result.manifest());
        assertTrue(text.lines().count() > 5, "the manifest should be pretty-printed, not one long line");
        // Still valid JSON after formatting.
        assertNotNull(Json.parse(text).asObject().get("version"));
    }

    @Test
    void prettyPrintLeavesStringContentAlone() {
        String compact = "{\"notes\":\"a, b: c {d}\",\"n\":1}";
        String pretty = UpdateArtifacts.prettyPrint(compact);

        assertEquals("a, b: c {d}", Json.parse(pretty).asObject().get("notes").asString());
    }

    private static boolean verify(Path file, String signatureBase64, String publicKeyBase64) throws Exception {
        PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(Files.readAllBytes(file));
        return verifier.verify(Base64.getDecoder().decode(signatureBase64));
    }
}
