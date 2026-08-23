package com.sugr.core;

import java.nio.file.Path;

/**
 * Cross-platform standard directories for app data, derived from the OS
 * conventions of each platform (the same per-OS layout {@code sugr package}
 * produces for a packaged app). Used by anything that needs a stable place
 * to persist app-owned files (window-state, caches, logs, ...) - centralizing
 * the lookup here means every consumer agrees on one location and the logic
 * isn't duplicated per feature:
 *
 * <ul>
 *   <li><b>Windows</b>: {@code %APPDATA%\&lt;AppName&gt;}</li>
 *   <li><b>macOS</b>: {@code ~/Library/Application Support/&lt;AppName&gt;}</li>
 *   <li><b>Linux</b>: {@code $XDG_CONFIG_HOME|~/.config/&lt;AppName&gt;}</li>
 * </ul>
 *
 * <p>The associated XDG cache directory ({@code ~/.cache/&lt;AppName&gt;} on Linux,
 * {@code ~/Library/Caches/&lt;AppName&gt;} on macOS) is chosen for cache data. The
 * app's directory is {@code create}d on first use so callers can write straight
 * into the returned path.
 */
final class AppPaths {

    private AppPaths() {
    }

    /**
     * Resolves (and creates) the app's config/data directory for the given
     * app name - e.g. {@code "sugr.examples.sqlclient"}. Safe to call at any
     * point; the directory is created if it doesn't exist yet.
     */
    static Path dataDir(String appName) {
        return ensureDir(baseDataDir().resolve(sanitize(appName)));
    }

    /** The OS's cache directory for the given app name - created on first use. */
    static Path cacheDir(String appName) {
        Path cache = Os.isMac()
                ? Path.of(System.getProperty("user.home"), "Library", "Caches")
                : Path.of(System.getProperty("user.home"), ".cache");
        return ensureDir(cache.resolve(sanitize(appName)));
    }

    private static Path baseDataDir() {
        if (Os.isWindows()) {
            String appdata = System.getenv("APPDATA");
            return appdata != null && !appdata.isBlank()
                    ? Path.of(appdata)
                    : Path.of(System.getProperty("user.home"), "AppData", "Roaming");
        }
        if (Os.isMac()) {
            return Path.of(System.getProperty("user.home"), "Library", "Application Support");
        }
        // Linux / other: XDG config dir, falling back to ~/.config
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return xdg != null && !xdg.isBlank()
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
    }

    /** Replaces anything that could be invalid in a directory name (slashes, colons, ...). */
    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path ensureDir(Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create app data dir " + dir, e);
        }
        return dir;
    }
}