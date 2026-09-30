package com.sugr.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Applies a verified update to the installed app.
 *
 * <p>Three strategies, picked by {@link #strategyFor}:
 *
 * <ul>
 *   <li>{@link Strategy#BINARY_SWAP} - rename the new file over the old one and
 *       relaunch. No elevation, no installer UI, no UAC prompt. This is what a
 *       per-user install gets, and why {@code sugr package} now installs into
 *       {@code %LOCALAPPDATA%\Programs} by default. Windows permits renaming a
 *       <em>running</em> executable (it only forbids deleting or overwriting
 *       one), which is the trick that makes this work at all - the same one
 *       Squirrel and Wails use.</li>
 *   <li>{@link Strategy#RUN_INSTALLER} - download the platform installer and
 *       re-run it. Needed when the install directory isn't writable by this
 *       user (a machine-wide {@code C:\Program Files} install), and costs a UAC
 *       prompt. This is Tauri's only Windows path.</li>
 *   <li>{@link Strategy#MANUAL} - nothing safe can be done automatically (a
 *       {@code .deb} that only {@code apt} should touch, an app in a read-only
 *       {@code /Applications}); the user is pointed at a download instead.</li>
 * </ul>
 *
 * <p>The macOS and Linux paths follow the same shape as the Windows one using
 * their platforms' normal mechanics, but - like the rest of {@code sugr}'s
 * non-Windows support - haven't been exercised on those OSes yet.
 */
final class UpdateInstaller {

    /** Suffix for the staged replacement, kept in the install dir so the final move is a same-volume rename. */
    static final String STAGED_SUFFIX = ".sugr-new";
    /** Suffix for the displaced old version, deleted on the next launch. */
    static final String BACKUP_SUFFIX = ".sugr-old";

    /** Windows can hold a brief lock on a just-replaced file (antivirus, indexer); a few retries clears it. */
    private static final int MOVE_ATTEMPTS = 5;
    private static final long MOVE_RETRY_DELAY_MS = 200;

    enum Strategy {
        BINARY_SWAP,
        RUN_INSTALLER,
        MANUAL
    }

    private UpdateInstaller() {
    }

    /** Picks how an update would be applied to this installation. */
    static Strategy strategyFor(AppInstall install, boolean hasInstaller) {
        return strategyFor(install.layout(), install.isWritable(), hasInstaller);
    }

    /**
     * The strategy decision on its own terms, so it can be reasoned about (and
     * tested) without a real installed app.
     *
     * @param hasInstaller whether the manifest offers an installer to fall back to
     */
    static Strategy strategyFor(AppInstall.Layout layout, boolean writable, boolean hasInstaller) {
        if (layout == AppInstall.Layout.UNPACKAGED) {
            return Strategy.MANUAL;
        }
        if (writable) {
            return Strategy.BINARY_SWAP;
        }
        // Not writable: only Windows has a silent-capable installer to re-run.
        // A .deb's /usr/bin/<app> belongs to the package manager, and macOS has
        // no unattended install we can trigger without a privileged helper.
        return hasInstaller && Os.isWindows() ? Strategy.RUN_INSTALLER : Strategy.MANUAL;
    }

    /**
     * Unpacks a verified artifact next to the installed app, ready for
     * {@link #applyStaged}. Kept separate from applying so a download can be
     * prepared in the background and committed later - and so the expensive,
     * failure-prone part happens well before the point of no return.
     *
     * @param target the file or {@code .app} bundle the update will replace
     * @return the staged path that will replace {@code target}
     */
    static Path stage(Path target, Path artifact, UpdateManifest.Format format)
            throws IOException, InterruptedException {
        Path staged = stagedPath(target);
        deleteRecursively(staged);

        Path scratch = Files.createTempDirectory(target.getParent(), ".sugr-unpack-");
        try {
            switch (format) {
                case BINARY -> {
                    Path binary = Archives.unzipSingleFile(artifact, scratch);
                    Files.move(binary, staged, StandardCopyOption.REPLACE_EXISTING);
                    Archives.makeExecutable(staged);
                }
                case APP_BUNDLE -> {
                    Archives.untarGz(artifact, scratch);
                    Path bundle = findAppBundle(scratch);
                    Files.move(bundle, staged, StandardCopyOption.REPLACE_EXISTING);
                    clearQuarantine(staged);
                }
            }
        } finally {
            deleteRecursively(scratch);
        }
        return staged;
    }

    /**
     * Swaps the staged version into place. The two renames run back to back
     * with nothing between them, so the window in which the app has no
     * executable at its expected path is microseconds wide - and
     * {@link #cleanupAfterUpdate} repairs even that case on the next launch.
     *
     * <p>If the second rename fails, the first is undone, leaving the working
     * installation exactly as it was.
     */
    static void applyStaged(Path target) throws IOException {
        Path staged = stagedPath(target);
        if (!Files.exists(staged)) {
            throw new IOException("No staged update at " + staged);
        }
        Path backup = Path.of(target + BACKUP_SUFFIX);
        deleteRecursively(backup);

        moveWithRetries(target, backup);
        try {
            moveWithRetries(staged, target);
        } catch (IOException e) {
            try {
                moveWithRetries(backup, target);
            } catch (IOException restoreFailure) {
                e.addSuppressed(restoreFailure);
                throw new IOException("Update failed and the original could not be restored - "
                        + target + " may need a reinstall", e);
            }
            throw e;
        }
    }

    /**
     * Housekeeping for the process that starts up after an update: removes the
     * displaced old version, and completes a swap that was interrupted between
     * its two renames (which would otherwise leave a staged file and no app).
     *
     * <p>Safe to call unconditionally on every launch; it does nothing when
     * there's nothing to clean.
     */
    static void cleanupAfterUpdate() {
        AppInstall install = AppInstall.current();
        if (install.layout() == AppInstall.Layout.UNPACKAGED) {
            return;
        }
        cleanupAfterUpdate(install.target());
    }

    /** {@link #cleanupAfterUpdate()} against an explicit target, so it can be exercised in a temp directory. */
    static void cleanupAfterUpdate(Path target) {
        Path staged = stagedPath(target);

        try {
            // Crash recovery: the old version was moved aside but the new one
            // never landed. We're running from *somewhere*, so finish the job.
            if (!Files.exists(target) && Files.exists(staged)) {
                moveWithRetries(staged, target);
            }
        } catch (IOException e) {
            System.err.println("[sugr] couldn't complete an interrupted update: " + e.getMessage());
        }

        Path dir = target.getParent();
        if (dir == null) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*" + BACKUP_SUFFIX)) {
            for (Path leftover : entries) {
                deleteRecursively(leftover);
            }
        } catch (IOException e) {
            // A leftover backup wastes disk but breaks nothing - never let it
            // stop the app from starting.
            System.err.println("[sugr] couldn't remove a previous version's files: " + e.getMessage());
        }
    }

    /**
     * Runs a downloaded installer after this process exits, then starts the app
     * again.
     *
     * <p>The indirection through a detached shell is required, not stylistic:
     * an installer cannot overwrite the executable of a running process, so the
     * app has to be gone before it starts - which means something other than
     * the app has to be the thing that starts it.
     *
     * @param installMode {@code passive} (progress bar, no prompts),
     *                    {@code quiet} (nothing at all) or {@code basicUi} -
     *                    the same vocabulary Tauri uses
     */
    static void runInstaller(Path executable, Path installer, String installMode) throws IOException {
        if (!Os.isWindows()) {
            throw new IOException("Running an installer is only supported on Windows");
        }
        String silentFlag = switch (installMode == null ? "" : installMode.toLowerCase(Locale.ROOT)) {
            case "basicui" -> "";
            // NSIS takes /S for silent; there's no separate passive mode, so
            // passive and quiet both run silently and differ only in that
            // passive lets the installer's own progress window show.
            default -> "/S";
        };

        long pid = ProcessHandle.current().pid();
        List<String> command = new ArrayList<>(List.of(
                "cmd", "/c", "start", "", "/min", "cmd", "/c",
                // Wait for this process to disappear before touching its files.
                "waitfor /t 30 sugrUpdate 2>nul & "
                        + "taskkill /pid " + pid + " /f >nul 2>&1 & "
                        + "\"" + installer + "\" " + silentFlag + " & "
                        + "start \"\" \"" + executable + "\""));

        new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.to(new File("NUL")))
                .redirectError(ProcessBuilder.Redirect.to(new File("NUL")))
                .start();
    }

    /** Where a staged replacement lives - always beside the target, so the final move never crosses volumes. */
    static Path stagedPath(Path target) {
        return Path.of(target + STAGED_SUFFIX);
    }

    private static void moveWithRetries(Path from, Path to) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
                if (attempt < MOVE_ATTEMPTS) {
                    try {
                        Thread.sleep(MOVE_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while moving " + from, interrupted);
                    }
                }
            }
        }
        throw new IOException("Couldn't move " + from + " to " + to, last);
    }

    /** The single {@code *.app} directory inside a freshly extracted bundle archive. */
    private static Path findAppBundle(Path dir) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.app")) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    return entry;
                }
            }
        }
        throw new IOException("No .app bundle found in " + dir);
    }

    /**
     * Strips the quarantine attribute macOS would otherwise use to gate the new
     * bundle behind a Gatekeeper prompt. Best-effort: files we downloaded
     * ourselves usually aren't quarantined in the first place.
     */
    private static void clearQuarantine(Path bundle) {
        try {
            new ProcessBuilder("xattr", "-dr", "com.apple.quarantine", bundle.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Deletes a file or a whole directory tree, ignoring anything that isn't there. */
    static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                List<Path> children = new ArrayList<>();
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                    entries.forEach(children::add);
                }
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("[sugr] couldn't delete " + path + ": " + e.getMessage());
        }
    }
}
