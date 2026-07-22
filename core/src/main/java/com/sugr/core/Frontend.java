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

    /** Like {@link #auto(String)}, defaulting to "/frontend" - the resource root every
     * {@code sugr build}/`sugr init` app uses unless explicitly reconfigured. */
    static Frontend auto() {
        return auto("/frontend");
    }

    /**
     * {@link #devServer} if {@code SUGR_DEV_URL} is set (as {@code sugr dev}
     * sets it), otherwise {@link #embedded}. Every app was hand-rolling this
     * exact env check itself - it's a convention the framework should own,
     * not each app's Main.java.
     */
    static Frontend auto(String embeddedResourceRoot) {
        String devUrl = System.getenv("SUGR_DEV_URL");
        return (devUrl == null || devUrl.isBlank())
                ? embedded(embeddedResourceRoot)
                : devServer(devUrl);
    }

    record DevServer(String url) implements Frontend {
    }

    record Embedded(String resourceRoot) implements Frontend {
    }
}
