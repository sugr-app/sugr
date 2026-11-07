package com.sugr.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShellTest {

    @Test
    void acceptsValidHttpHttpsAndMailtoUrls() {
        assertEquals("https://sugr-app.github.io/sugr/",
                Shell.validateUrl("https://sugr-app.github.io/sugr/").toString());
        assertEquals("http://localhost:5173/docs?a=1&b=2",
                Shell.validateUrl("http://localhost:5173/docs?a=1&b=2").toString());
        assertEquals("mailto:support@example.com",
                Shell.validateUrl("mailto:support@example.com").toString());
    }

    @Test
    void rejectsDisallowedOrDangerousUrlSchemes() {
        for (String bad : new String[] {
                null,
                "",
                "   ",
                "sugr-app.github.io/sugr",
                "javascript:alert(1)",
                "file:///C:/Windows/System32/calc.exe",
                "cmd:///c/calc.exe",
                "ms-msdt:/id PCWDiagnostic",
                "ftp://example.com/file.zip",
                "https://",
                "http:///path-only",
                "mailto:"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Shell.validateUrl(bad),
                    "Expected URL to be rejected: " + bad);
        }
    }

    @Test
    void validatesExistingLocalPathsAndRejectsMissingOnes(@TempDir Path tempDir) throws Exception {
        Path existingDir = Files.createDirectories(tempDir.resolve("logs"));
        assertEquals(existingDir.toAbsolutePath().normalize(), Shell.validatePath(existingDir));

        Path missing = tempDir.resolve("does-not-exist");
        assertThrows(IllegalArgumentException.class, () -> Shell.validatePath(missing));
        assertThrows(IllegalArgumentException.class, () -> Shell.validatePath(null));
        assertThrows(IllegalArgumentException.class, () -> Shell.openPath("   "));
    }
}
