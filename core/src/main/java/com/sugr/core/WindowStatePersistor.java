package com.sugr.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persists a window's position/size (and state) across app restarts, so a
 * cold launch reopens at the same spot the user last left it rather than
 * jumping back to the configured default. Backed by a small properties file
 * under the app's data dir ({@link AppPaths#dataDir}) - deliberately a
 * well-known, human-editable format instead of a bespoke binary blob.
 *
 * <p>Consumed by {@code Window} when state restoration is enabled
 * ({@code Application.Builder.restoreWindowState}); the low-level serialization
 * lives here so the caller never touches the file format.
 */
final class WindowStatePersistor {

    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_MAXIMIZED = "maximized";

    private final Path file;

    /**
     * @param appName stable app identifier - the directory under which the
     *                state file is stored ({@link AppPaths#dataDir})
     */
    WindowStatePersistor(String appName) {
        this(AppPaths.dataDir(appName).resolve("window-state.properties"));
    }

    /** Visible for tests - persistence to an explicit path. */
    WindowStatePersistor(Path file) {
        this.file = file;
    }

    /** The last saved window bounds + state, or {@code null} if nothing has been saved yet. */
    State load() {
        if (!Files.exists(file)) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            // Corrupt/unreadable state shouldn't crash startup - treat as no saved state.
            return null;
        }
        Integer x = parse(props, KEY_X);
        Integer y = parse(props, KEY_Y);
        Integer width = parse(props, KEY_WIDTH);
        Integer height = parse(props, KEY_HEIGHT);
        if (x == null || y == null || width == null || height == null) {
            return null; // incomplete - not worth restoring
        }
        boolean maximized = Boolean.parseBoolean(props.getProperty(KEY_MAXIMIZED, "false"));
        return new State(x, y, width, height, maximized);
    }

    /** Saves the given bounds + maximized flag, replacing any previous state. */
    void save(int x, int y, int width, int height, boolean maximized) {
        Properties props = new Properties();
        props.setProperty(KEY_X, Integer.toString(x));
        props.setProperty(KEY_Y, Integer.toString(y));
        props.setProperty(KEY_WIDTH, Integer.toString(width));
        props.setProperty(KEY_HEIGHT, Integer.toString(height));
        props.setProperty(KEY_MAXIMIZED, Boolean.toString(maximized));
        Path parent = file.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "sugr window state");
            }
        } catch (IOException e) {
            System.err.println("[sugr] failed to persist window state to " + file + ": " + e.getMessage());
        }
    }

    private static Integer parse(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Immutable snapshot of a saved window bounds + maximized flag. */
    record State(int x, int y, int width, int height, boolean maximized) {
    }
}