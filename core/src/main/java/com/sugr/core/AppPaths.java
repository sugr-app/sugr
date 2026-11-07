package com.sugr.core;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Cross-platform standard directories for app data, caches, and logs, derived
 * from the OS conventions of each platform (the same per-OS layout
 * {@code sugr package} produces for a packaged app). Centralizing the lookup
 * here means every consumer (window-state persistence, updater staging, app
 * logs, and user code) agrees on one location:
 *
 * <ul>
 *   <li><b>Data / config ({@link #dataDir})</b>:
 *     <ul>
 *       <li>Windows: {@code %APPDATA%\&lt;AppName&gt;}</li>
 *       <li>macOS: {@code ~/Library/Application Support/&lt;AppName&gt;}</li>
 *       <li>Linux: {@code $XDG_CONFIG_HOME|~/.config/&lt;AppName&gt;}</li>
 *     </ul>
 *   </li>
 *   <li><b>Cache ({@link #cacheDir})</b>:
 *     <ul>
 *       <li>Windows: {@code %LOCALAPPDATA%\&lt;AppName&gt;\Cache}</li>
 *       <li>macOS: {@code ~/Library/Caches/&lt;AppName&gt;}</li>
 *       <li>Linux: {@code $XDG_CACHE_HOME|~/.cache/&lt;AppName&gt;}</li>
 *     </ul>
 *   </li>
 *   <li><b>Logs ({@link #logDir})</b>:
 *     <ul>
 *       <li>Windows: {@code %LOCALAPPDATA%\&lt;AppName&gt;\Logs}</li>
 *       <li>macOS: {@code ~/Library/Logs/&lt;AppName&gt;}</li>
 *       <li>Linux: {@code $XDG_STATE_HOME|~/.local/state/&lt;AppName&gt;/logs}</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Each directory is created on first use so callers can write straight into
 * the returned path. The zero-arg overloads ({@link #dataDir()},
 * {@link #cacheDir()}, {@link #logDir()}) use the app's configured
 * {@code identifier} from {@code sugr.config.json} (see {@link AppMetadata}).
 */
public final class AppPaths {

    private AppPaths() {
    }

    /**
     * Resolves (and creates) this app's config/data directory using the
     * {@code identifier} from {@code sugr.config.json}.
     */
    public static Path dataDir() {
        return dataDir(AppMetadata.get().identifier());
    }

    /**
     * Resolves (and creates) the app's config/data directory for the given
     * app name - e.g. {@code "sugr.examples.sqlclient"}. Safe to call at any
     * point; the directory is created if it doesn't exist yet.
     */
    public static Path dataDir(String appName) {
        return ensureDir(resolveDataDir(appName, Os.isWindows(), Os.isMac(), System::getenv, userHome()));
    }

    /**
     * Resolves (and creates) this app's cache directory using the
     * {@code identifier} from {@code sugr.config.json}.
     */
    public static Path cacheDir() {
        return cacheDir(AppMetadata.get().identifier());
    }

    /** The OS's cache directory for the given app name - created on first use. */
    public static Path cacheDir(String appName) {
        return ensureDir(resolveCacheDir(appName, Os.isWindows(), Os.isMac(), System::getenv, userHome()));
    }

    /**
     * Resolves (and creates) this app's log directory using the
     * {@code identifier} from {@code sugr.config.json}.
     */
    public static Path logDir() {
        return logDir(AppMetadata.get().identifier());
    }

    /** The OS's log directory for the given app name - created on first use. */
    public static Path logDir(String appName) {
        return ensureDir(resolveLogDir(appName, Os.isWindows(), Os.isMac(), System::getenv, userHome()));
    }

    static Path resolveDataDir(String appName, boolean isWindows, boolean isMac,
                               Function<String, String> env, Path userHome) {
        String safeName = sanitize(appName);
        if (isWindows) {
            return windowsRoamingAppData(env, userHome).resolve(safeName);
        }
        if (isMac) {
            return userHome.resolve("Library").resolve("Application Support").resolve(safeName);
        }
        String xdg = env.apply("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : userHome.resolve(".config");
        return base.resolve(safeName);
    }

    static Path resolveCacheDir(String appName, boolean isWindows, boolean isMac,
                                Function<String, String> env, Path userHome) {
        String safeName = sanitize(appName);
        if (isWindows) {
            return windowsLocalAppData(env, userHome).resolve(safeName).resolve("Cache");
        }
        if (isMac) {
            return userHome.resolve("Library").resolve("Caches").resolve(safeName);
        }
        String xdg = env.apply("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : userHome.resolve(".cache");
        return base.resolve(safeName);
    }

    static Path resolveLogDir(String appName, boolean isWindows, boolean isMac,
                              Function<String, String> env, Path userHome) {
        String safeName = sanitize(appName);
        if (isWindows) {
            return windowsLocalAppData(env, userHome).resolve(safeName).resolve("Logs");
        }
        if (isMac) {
            return userHome.resolve("Library").resolve("Logs").resolve(safeName);
        }
        String xdg = env.apply("XDG_STATE_HOME");
        Path base = xdg != null && !xdg.isBlank()
                ? Path.of(xdg)
                : userHome.resolve(".local").resolve("state");
        return base.resolve(safeName).resolve("logs");
    }

    private static Path windowsRoamingAppData(Function<String, String> env, Path userHome) {
        String appdata = env.apply("APPDATA");
        return appdata != null && !appdata.isBlank()
                ? Path.of(appdata)
                : userHome.resolve("AppData").resolve("Roaming");
    }

    private static Path windowsLocalAppData(Function<String, String> env, Path userHome) {
        String local = env.apply("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Path.of(local);
        }
        String roaming = env.apply("APPDATA");
        if (roaming != null && !roaming.isBlank()) {
            return Path.of(roaming);
        }
        return userHome.resolve("AppData").resolve("Local");
    }

    private static Path userHome() {
        return Path.of(System.getProperty("user.home"));
    }

    /** Replaces anything that could be invalid in a directory name (slashes, colons, ...). */
    static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "sugr-app";
        }
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path ensureDir(Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create app directory " + dir, e);
        }
        return dir;
    }
}
