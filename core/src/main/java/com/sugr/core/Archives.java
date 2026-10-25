package com.sugr.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacking for update artifacts. Zip is handled with the JDK's own reader;
 * {@code .tar.gz} (the macOS {@code .app} bundle format) is delegated to the
 * system {@code tar}, which is present on every macOS and Linux install and
 * preserves the symlinks and executable bits a bundle depends on - things a
 * hand-rolled tar reader would have to get right and this doesn't need to.
 */
final class Archives {

    private Archives() {
    }

    /**
     * Extracts {@code zip} into {@code destDir}, refusing entries that would
     * escape it.
     *
     * <p>The escape check isn't theoretical hygiene here: the archive arrives
     * over the network, and an entry named {@code ../../../../windows/system32/...}
     * in an unchecked extractor writes wherever it likes. The signature check
     * runs before this, so a valid artifact can't contain such an entry - but
     * defence that only works when an earlier check worked isn't defence.
     *
     * @return every file written, in archive order
     */
    static List<Path> unzip(Path zip, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        Path root = destDir.toRealPath();
        List<Path> written = new ArrayList<>();

        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path resolved = root.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(root)) {
                    throw new IOException("Refusing zip entry that escapes the destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                if (resolved.getParent() != null) {
                    Files.createDirectories(resolved.getParent());
                }
                Files.copy(in, resolved, StandardCopyOption.REPLACE_EXISTING);
                written.add(resolved);
            }
        }
        return written;
    }

    /**
     * The single regular file inside {@code zip}, extracted into {@code destDir} -
     * how a {@code format: "binary"} artifact is shaped (one executable, nothing else).
     *
     * @throws IOException if the archive holds anything other than exactly one file
     */
    static Path unzipSingleFile(Path zip, Path destDir) throws IOException {
        List<Path> files = unzip(zip, destDir);
        if (files.size() != 1) {
            throw new IOException("Expected exactly one file in " + zip.getFileName()
                    + " but found " + files.size());
        }
        Path file = files.get(0);
        makeExecutable(file);
        return file;
    }

    /** Extracts a {@code .tar.gz} via the system {@code tar}. macOS/Linux only. */
    static void untarGz(Path archive, Path destDir) throws IOException, InterruptedException {
        Files.createDirectories(destDir);
        Process process = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", destDir.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("tar failed to extract " + archive + " (exit " + exit + ")");
        }
    }

    /**
     * Sets the owner-executable bit on platforms that have one. Zip entries
     * carry POSIX permissions that {@link ZipInputStream} doesn't expose, so a
     * freshly extracted binary would otherwise come out non-executable on
     * macOS and Linux.
     */
    static void makeExecutable(Path file) {
        if (Os.isWindows()) {
            return;
        }
        file.toFile().setExecutable(true, false);
    }

    /** Reads a single entry's bytes without extracting the archive. Used to peek at small metadata files. */
    static byte[] readEntry(Path zip, String entryName) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equals(entryName)) {
                    return readAll(in);
                }
            }
        }
        throw new IOException("No entry named " + entryName + " in " + zip);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }
}
