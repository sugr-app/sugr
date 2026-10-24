package com.sugr.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file mechanics of applying an update. These run against a temp directory
 * rather than a real installation, which is exactly the point: the swap has to
 * be correct in the failure cases (interrupted halfway, nothing staged, stale
 * leftovers) and those are impossible to provoke on demand in a real install.
 */
class UpdateInstallerTest {

    private static Path zipContaining(Path dir, String entryName, String content) throws IOException {
        Path zip = dir.resolve("artifact.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zip;
    }

    // ---- strategy selection ----

    @Test
    void aWritableInstallSwapsBinariesInPlace() {
        assertEquals(UpdateInstaller.Strategy.BINARY_SWAP,
                UpdateInstaller.strategyFor(AppInstall.Layout.EXECUTABLE, true, false));
        assertEquals(UpdateInstaller.Strategy.BINARY_SWAP,
                UpdateInstaller.strategyFor(AppInstall.Layout.APP_BUNDLE, true, false));
    }

    @Test
    void anUnpackagedRunCanNeverBeUpdated() {
        // Running from Gradle or a JVM classpath - there is no installed
        // artifact to replace, so offering to replace one would be a lie.
        assertEquals(UpdateInstaller.Strategy.MANUAL,
                UpdateInstaller.strategyFor(AppInstall.Layout.UNPACKAGED, true, true));
    }

    @Test
    void aReadOnlyWindowsInstallIsManagedNotReinstalled() {
        // A Program Files install belongs to whoever deployed it. Re-running the
        // per-user installer would create a second copy and leave this one stale.
        assertEquals(UpdateInstaller.Strategy.MANAGED,
                UpdateInstaller.strategyFor(AppInstall.Layout.EXECUTABLE, false, true));
    }

    @Test
    void aReadOnlyInstallElsewhereIsManual() {
        assertEquals(UpdateInstaller.Strategy.MANUAL,
                UpdateInstaller.strategyFor(AppInstall.Layout.EXECUTABLE, false, false));
        assertEquals(UpdateInstaller.Strategy.MANUAL,
                UpdateInstaller.strategyFor(AppInstall.Layout.APP_BUNDLE, false, false));
    }

    // ---- staging ----

    @Test
    void stagingUnpacksBesideTheTarget(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("app.exe");
        Files.writeString(target, "old");
        Path zip = zipContaining(dir, "app.exe", "new");

        Path staged = UpdateInstaller.stage(target, zip, UpdateManifest.Format.BINARY);

        assertEquals(dir.resolve("app.exe" + UpdateInstaller.STAGED_SUFFIX), staged);
        assertEquals("new", Files.readString(staged));
        // The target is untouched until applyStaged runs.
        assertEquals("old", Files.readString(target));
    }

    @Test
    void stagingReplacesALeftoverFromAnEarlierAttempt(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("app.exe");
        Files.writeString(target, "old");
        Files.writeString(dir.resolve("app.exe" + UpdateInstaller.STAGED_SUFFIX), "stale");

        UpdateInstaller.stage(target, zipContaining(dir, "app.exe", "new"), UpdateManifest.Format.BINARY);

        assertEquals("new", Files.readString(dir.resolve("app.exe" + UpdateInstaller.STAGED_SUFFIX)));
    }

    @Test
    void stagingLeavesNoScratchDirectoriesBehind(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("app.exe");
        Files.writeString(target, "old");

        UpdateInstaller.stage(target, zipContaining(dir, "app.exe", "new"), UpdateManifest.Format.BINARY);

        try (var entries = Files.list(dir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().startsWith(".sugr-unpack-")),
                    "the unpack scratch directory should be cleaned up");
        }
    }

    @Test
    void aBinaryArtifactMustHoldExactlyOneFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("app.exe");
        Files.writeString(target, "old");

        Path zip = dir.resolve("two.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (String name : new String[]{"a", "b"}) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write(name.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        assertThrows(IOException.class, () -> UpdateInstaller.stage(target, zip, UpdateManifest.Format.BINARY));
    }

    // ---- applying ----

    @Test
    void applyingSwapsTheStagedVersionIn(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("app.exe");
        Files.writeString(target, "old");
        UpdateInstaller.stage(target, zipContaining(dir, "app.exe", "new"), UpdateManifest.Format.BINARY);

        UpdateInstaller.applyStaged(target);

        assertEquals("new", Files.readString(target));
        assertEquals("old", Files.readString(dir.resolve("app.exe" + UpdateInstaller.BACKUP_SUFFIX)));
        assertFalse(Files.exists(dir.resolve("app.exe" + UpdateInstaller.STAGED_SUFFIX)));
    }

    @Test
    void applyingWithNothingStagedFails(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("app.exe");
        Files.writeString(target, "old");

        assertThrows(IOException.class, () -> UpdateInstaller.applyStaged(target));
        assertEquals("old", Files.readString(target), "a failed apply must not disturb the install");
    }

    @Test
    void applyingTwiceInARowDiscardsTheOlderBackup(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("app.exe");
        Files.writeString(target, "v1");

        UpdateInstaller.stage(target, zipContaining(dir, "app.exe", "v2"), UpdateManifest.Format.BINARY);
        UpdateInstaller.applyStaged(target);
        Files.delete(dir.resolve("artifact.zip"));
        UpdateInstaller.stage(target, zipContaining(dir, "app.exe", "v3"), UpdateManifest.Format.BINARY);
        UpdateInstaller.applyStaged(target);

        assertEquals("v3", Files.readString(target));
        assertEquals("v2", Files.readString(dir.resolve("app.exe" + UpdateInstaller.BACKUP_SUFFIX)));
    }

    // ---- cleanup and crash recovery ----

    @Test
    void cleanupRemovesTheDisplacedVersion(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("app.exe");
        Files.writeString(target, "new");
        Files.writeString(dir.resolve("app.exe" + UpdateInstaller.BACKUP_SUFFIX), "old");

        UpdateInstaller.cleanupAfterUpdate(target);

        assertFalse(Files.exists(dir.resolve("app.exe" + UpdateInstaller.BACKUP_SUFFIX)));
        assertEquals("new", Files.readString(target));
    }

    @Test
    void cleanupFinishesASwapInterruptedBetweenItsTwoRenames(@TempDir Path dir) throws Exception {
        // The crash window: the old version was moved aside, the new one hadn't
        // landed yet, and the process died. There is no app at the expected path.
        Path target = dir.resolve("app.exe");
        Files.writeString(dir.resolve("app.exe" + UpdateInstaller.BACKUP_SUFFIX), "old");
        Files.writeString(dir.resolve("app.exe" + UpdateInstaller.STAGED_SUFFIX), "new");

        UpdateInstaller.cleanupAfterUpdate(target);

        assertTrue(Files.exists(target), "the staged version should have been moved into place");
        assertEquals("new", Files.readString(target));
        assertFalse(Files.exists(dir.resolve("app.exe" + UpdateInstaller.BACKUP_SUFFIX)));
    }

    @Test
    void cleanupIsANoOpOnAHealthyInstall(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("app.exe");
        Files.writeString(target, "current");

        UpdateInstaller.cleanupAfterUpdate(target);

        assertEquals("current", Files.readString(target));
        try (var entries = Files.list(dir)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void cleanupNeverThrowsWhenTheInstallDirectoryIsGone(@TempDir Path dir) {
        // Startup must not be able to fail here, whatever it finds on disk.
        UpdateInstaller.cleanupAfterUpdate(dir.resolve("missing").resolve("app.exe"));
    }
}
