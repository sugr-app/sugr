package com.sugr.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Per-environment app configuration: layered {@code .properties} files
 * (common defaults + one override file per environment), selected by an
 * environment variable read at startup - same convention as
 * {@link Frontend#auto(String)}'s {@code SUGR_DEV_URL} check, just for
 * arbitrary app config instead of the frontend source.
 *
 * <p>Looks for {@code /application.properties} on the classpath (shared
 * defaults) and {@code /application-<env>.properties} (per-environment
 * overrides, e.g. {@code application-staging.properties}) - both are plain
 * {@code src/main/resources} files, embedded into every build/binary
 * regardless of the target environment. Don't put secrets in them.
 */
public final class AppConfig {

    private final String env;
    private final Properties properties;

    private AppConfig(String env, Properties properties) {
        this.env = env;
        this.properties = properties;
    }

    /** {@link #load(String, String)} reading {@code SUGR_ENV}, defaulting to "dev". */
    public static AppConfig auto() {
        return load("SUGR_ENV", "dev");
    }

    /**
     * Resolves the active environment from {@code System.getenv(envVarName)},
     * falling back to {@code defaultEnv} if unset/blank, then loads
     * {@code /application.properties} overlaid with
     * {@code /application-<env>.properties} (either file may be absent).
     */
    public static AppConfig load(String envVarName, String defaultEnv) {
        String value = System.getenv(envVarName);
        String env = (value == null || value.isBlank()) ? defaultEnv : value;

        Properties common = new Properties();
        readInto(common, "/application.properties");

        Properties merged = new Properties(common);
        readInto(merged, "/application-" + env + ".properties");

        return new AppConfig(env, merged);
    }

    private static void readInto(Properties target, String resourcePath) {
        try (InputStream in = AppConfig.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                target.load(in);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + resourcePath, e);
        }
    }

    /** The resolved environment name, e.g. "dev", "staging", "prod". */
    public String env() {
        return env;
    }

    public boolean isDevelopment() {
        return "dev".equals(env);
    }

    public boolean isStaging() {
        return "staging".equals(env);
    }

    public boolean isProduction() {
        return "prod".equals(env);
    }

    public String get(String key) {
        return properties.getProperty(key);
    }

    public String getOrDefault(String key, String fallback) {
        return properties.getProperty(key, fallback);
    }
}
