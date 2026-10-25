package com.sugr.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The updater's state machine: what a check records, and what that does and
 * doesn't permit. The work is queued on an executor the test never runs, so
 * "in flight" is something a test can hold still and look at.
 */
class UpdaterTest {

    private static final String TARGET = "windows-x86_64";
    private static KeyPair keyPair;
    private static String publicKeyBase64;

    @BeforeAll
    static void generateKeys() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private final List<Runnable> queued = new ArrayList<>();
    private final List<String> events = new ArrayList<>();

    private Updater updater() {
        AppMetadata metadata = AppMetadata.parse("""
                {"name":"app","version":"1.0.0","identifier":"com.example.app",
                 "updater":{"endpoints":["https://e.test/latest.json"],"pubkey":"%s"}}
                """.formatted(publicKeyBase64));
        Updater updater = new Updater(metadata, metadata.updater(), queued::add);
        updater.addEventSink((event, payload) -> events.add(event));
        return updater;
    }

    private static UpdateManifest signedManifest(String version) throws Exception {
        String unsigned = manifestJson(version, "");
        byte[] payload = UpdateManifest.parse(unsigned, TARGET).signedPayload("com.example.app", TARGET);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload);
        return UpdateManifest.parse(manifestJson(version, Base64.getEncoder().encodeToString(signer.sign())),
                TARGET);
    }

    private static String manifestJson(String version, String manifestSignature) {
        return """
                {"version":"%s","platforms":{"%s":{"url":"https://e.test/a.zip","sha256":"abc",
                  "size":10,"signature":"c2ln","manifestSignature":"%s"}}}
                """.formatted(version, TARGET, manifestSignature);
    }

    @Test
    void aCheckAloneIsNotReadyToInstall() throws Exception {
        Updater updater = updater();
        Updater.UpdateInfo info = updater.evaluate(signedManifest("1.2.0"), SemVer.parse("1.0.0"), TARGET,
                UpdateInstaller.Strategy.BINARY_SWAP);

        assertNotNull(info);
        assertEquals("1.2.0", updater.availableInfo().version());
        assertTrue(updater.canSelfUpdate());
        // Found is not the same as downloaded: installing now would apply nothing.
        assertFalse(updater.isReadyToInstall());
        assertThrows(IllegalStateException.class, updater::applyStagedUpdate);
        assertTrue(events.contains(Updater.EVENT_AVAILABLE));
    }

    @Test
    void aCheckDuringADownloadDoesNotReplaceWhatIsBeingDownloaded() throws Exception {
        Updater updater = updater();
        updater.evaluate(signedManifest("1.2.0"), SemVer.parse("1.0.0"), TARGET,
                UpdateInstaller.Strategy.BINARY_SWAP);

        CompletableFuture<Void> download = updater.download();
        assertFalse(download.isDone());
        assertEquals(1, queued.size());

        CompletableFuture<Updater.UpdateInfo> during = updater.check();
        assertTrue(during.isDone(), "a check mid-download answers from the release being downloaded");
        assertEquals("1.2.0", during.join().version());
        assertEquals(1, queued.size(), "no manifest fetch was scheduled behind the download");
    }

    @Test
    void anUnverifiedManifestRecordsNothing() throws Exception {
        Updater updater = updater();
        UpdateManifest tampered = UpdateManifest.parse(manifestJson("9.9.9",
                signedManifest("1.2.0").artifactFor(TARGET).manifestSignature()), TARGET);

        assertNull(updater.evaluate(tampered, SemVer.parse("1.0.0"), TARGET, UpdateInstaller.Strategy.BINARY_SWAP));
        assertNull(updater.availableInfo());
        assertTrue(events.contains(Updater.EVENT_ERROR));
        assertFalse(events.contains(Updater.EVENT_AVAILABLE));
    }

    @Test
    void aManagedInstallIsReportedButNeverSelfUpdates() throws Exception {
        Updater updater = updater();
        Updater.UpdateInfo info = updater.evaluate(signedManifest("1.2.0"), SemVer.parse("1.0.0"), TARGET,
                UpdateInstaller.Strategy.MANAGED);

        assertNotNull(info, "the release is still reported");
        assertFalse(updater.canSelfUpdate());
        assertTrue(events.contains(Updater.EVENT_MANUAL_REQUIRED));
        assertFalse(events.contains(Updater.EVENT_AVAILABLE));
    }
}
