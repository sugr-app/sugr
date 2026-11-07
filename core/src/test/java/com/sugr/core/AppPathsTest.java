package com.sugr.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPathsTest {

    @Test
    void sanitizesUnsafeCharactersAndHandlesBlankNames() {
        assertEquals("com.sugr.sql-client", AppPaths.sanitize("com.sugr.sql-client"));
        assertEquals("my_app_v1_0", AppPaths.sanitize("my/app:v1*0"));
        assertEquals("sugr-app", AppPaths.sanitize("   "));
        assertEquals("sugr-app", AppPaths.sanitize(null));
    }

    @Test
    void resolvesWindowsDataCacheAndLogDirsFromEnv() {
        Path home = Path.of("C:\\Users\\alice");
        Map<String, String> env = Map.of(
                "APPDATA", "C:\\Users\\alice\\AppData\\Roaming",
                "LOCALAPPDATA", "C:\\Users\\alice\\AppData\\Local"
        );

        assertEquals(
                Path.of("C:\\Users\\alice\\AppData\\Roaming").resolve("com.example.app"),
                AppPaths.resolveDataDir("com.example.app", true, false, env::get, home));
        assertEquals(
                Path.of("C:\\Users\\alice\\AppData\\Local").resolve("com.example.app").resolve("Cache"),
                AppPaths.resolveCacheDir("com.example.app", true, false, env::get, home));
        assertEquals(
                Path.of("C:\\Users\\alice\\AppData\\Local").resolve("com.example.app").resolve("Logs"),
                AppPaths.resolveLogDir("com.example.app", true, false, env::get, home));
    }

    @Test
    void resolvesMacDirsUnderLibrary() {
        Path home = Path.of("/Users/alice");
        Map<String, String> env = Map.of();

        assertEquals(
                home.resolve("Library").resolve("Application Support").resolve("com.example.app"),
                AppPaths.resolveDataDir("com.example.app", false, true, env::get, home));
        assertEquals(
                home.resolve("Library").resolve("Caches").resolve("com.example.app"),
                AppPaths.resolveCacheDir("com.example.app", false, true, env::get, home));
        assertEquals(
                home.resolve("Library").resolve("Logs").resolve("com.example.app"),
                AppPaths.resolveLogDir("com.example.app", false, true, env::get, home));
    }

    @Test
    void resolvesLinuxDirsWithXdgDefaultsAndOverrides() {
        Path home = Path.of("/home/alice");
        Map<String, String> emptyEnv = Map.of();

        assertEquals(
                home.resolve(".config").resolve("com.example.app"),
                AppPaths.resolveDataDir("com.example.app", false, false, emptyEnv::get, home));
        assertEquals(
                home.resolve(".cache").resolve("com.example.app"),
                AppPaths.resolveCacheDir("com.example.app", false, false, emptyEnv::get, home));
        assertEquals(
                home.resolve(".local").resolve("state").resolve("com.example.app").resolve("logs"),
                AppPaths.resolveLogDir("com.example.app", false, false, emptyEnv::get, home));

        Map<String, String> xdgEnv = Map.of(
                "XDG_CONFIG_HOME", "/tmp/xdg-config",
                "XDG_CACHE_HOME", "/tmp/xdg-cache",
                "XDG_STATE_HOME", "/tmp/xdg-state"
        );
        assertEquals(
                Path.of("/tmp/xdg-config").resolve("com.example.app"),
                AppPaths.resolveDataDir("com.example.app", false, false, xdgEnv::get, home));
        assertEquals(
                Path.of("/tmp/xdg-cache").resolve("com.example.app"),
                AppPaths.resolveCacheDir("com.example.app", false, false, xdgEnv::get, home));
        assertEquals(
                Path.of("/tmp/xdg-state").resolve("com.example.app").resolve("logs"),
                AppPaths.resolveLogDir("com.example.app", false, false, xdgEnv::get, home));
    }

    @Test
    void createsDirectoriesOnFirstAccess() {
        String testApp = "sugr.test.apppaths";
        assertTrue(Files.isDirectory(AppPaths.dataDir(testApp)));
        assertTrue(Files.isDirectory(AppPaths.cacheDir(testApp)));
        assertTrue(Files.isDirectory(AppPaths.logDir(testApp)));
    }
}
