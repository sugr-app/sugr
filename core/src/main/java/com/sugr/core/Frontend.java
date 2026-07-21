package com.sugr.core;

/**
 * Where the window's web content comes from: a live dev server (for HMR
 * during development) or assets embedded in the app's resources (for
 * production / native-image builds - no external server dependency).
 */
public sealed interface Frontend {

    static Frontend devServer(String url) {
        return new DevServer(url);
    }

    /** resourceRoot is a classpath location, e.g. "/frontend" for src/main/resources/frontend/. */
    static Frontend embedded(String resourceRoot) {
        return new Embedded(resourceRoot);
    }

    record DevServer(String url) implements Frontend {
    }

    record Embedded(String resourceRoot) implements Frontend {
    }
}
