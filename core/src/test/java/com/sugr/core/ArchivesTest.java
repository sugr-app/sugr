package com.sugr.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivesTest {

    private static Path zip(Path dir, String name, String... entries) throws IOException {
        Path zip = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (int i = 0; i < entries.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entries[i]));
                if (!entries[i].endsWith("/")) {
                    zos.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void extractsFilesAndDirectories(@TempDir Path dir) throws Exception {
        Path archive = zip(dir, "a.zip", "top.txt", "one", "nested/deep.txt", "two");
        Path dest = dir.resolve("out");

        List<Path> written = Archives.unzip(archive, dest);

        assertEquals(2, written.size());
        assertEquals("one", Files.readString(dest.resolve("top.txt")));
        assertEquals("two", Files.readString(dest.resolve("nested/deep.txt")));
    }

    @Test
    void refusesEntriesThatEscapeTheDestination(@TempDir Path dir) throws Exception {
        // Zip-slip. The signature check runs before extraction, so a valid
        // artifact can't reach here - but a defence that only holds while an
        // earlier check holds isn't a defence.
        Path archive = zip(dir, "evil.zip", "../escaped.txt", "pwned");
        Path dest = dir.resolve("out");

        IOException failure = assertThrows(IOException.class, () -> Archives.unzip(archive, dest));
        assertTrue(failure.getMessage().contains("escapes"));
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
    }

    @Test
    void refusesDeeplyNestedTraversal(@TempDir Path dir) throws Exception {
        Path archive = zip(dir, "evil2.zip", "a/b/../../../escaped.txt", "pwned");
        assertThrows(IOException.class, () -> Archives.unzip(archive, dir.resolve("out")));
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
    }

    @Test
    void extractsTheSingleFileOfABinaryArtifact(@TempDir Path dir) throws Exception {
        Path archive = zip(dir, "single.zip", "app.exe", "binary bytes");

        Path extracted = Archives.unzipSingleFile(archive, dir.resolve("out"));

        assertEquals("app.exe", extracted.getFileName().toString());
        assertEquals("binary bytes", Files.readString(extracted));
    }

    @Test
    void rejectsABinaryArtifactWithTheWrongNumberOfFiles(@TempDir Path dir) throws Exception {
        Path two = zip(dir, "two.zip", "a", "1", "b", "2");
        assertThrows(IOException.class, () -> Archives.unzipSingleFile(two, dir.resolve("out1")));

        Path none = zip(dir, "none.zip");
        assertThrows(IOException.class, () -> Archives.unzipSingleFile(none, dir.resolve("out2")));
    }

    @Test
    void readsASingleEntryWithoutExtracting(@TempDir Path dir) throws Exception {
        Path archive = zip(dir, "meta.zip", "info.json", "{\"a\":1}");

        assertEquals("{\"a\":1}", new String(Archives.readEntry(archive, "info.json"), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> Archives.readEntry(archive, "nope.json"));
    }
}
