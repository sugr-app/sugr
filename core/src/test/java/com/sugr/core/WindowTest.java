package com.sugr.core;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Window#resolveDevTools} - whether libwebview's {@code debug} flag (devtools)
 * should be on, given an explicit {@link Window.Builder#devTools} setting (if any) and
 * the process environment. Pure function of its inputs, so env is passed as a plain
 * {@code Map} here instead of touching real environment variables.
 */
final class WindowTest {

    private static Function<String, String> env(Map<String, String> vars) {
        return vars::get;
    }

    @Test
    void offByDefaultWithNoEnvSet() {
        assertFalse(Window.resolveDevTools(null, env(Map.of())));
    }

    @Test
    void onWhenDevUrlIsSet() {
        assertTrue(Window.resolveDevTools(null, env(Map.of("SUGR_DEV_URL", "http://localhost:5173"))));
    }

    @Test
    void offWhenDevUrlIsBlank() {
        assertFalse(Window.resolveDevTools(null, env(Map.of("SUGR_DEV_URL", "  "))));
    }

    @Test
    void onWhenSugrEnvIsDev() {
        assertTrue(Window.resolveDevTools(null, env(Map.of("SUGR_ENV", "dev"))));
    }

    @Test
    void offWhenSugrEnvIsProd() {
        assertFalse(Window.resolveDevTools(null, env(Map.of("SUGR_ENV", "prod"))));
    }

    @Test
    void devtoolsEnvForcesOnRegardlessOfOtherEnv() {
        assertTrue(Window.resolveDevTools(null, env(Map.of("SUGR_ENV", "prod", "SUGR_DEVTOOLS", "1"))));
    }

    @Test
    void devtoolsEnvForcesOffRegardlessOfOtherEnv() {
        assertFalse(Window.resolveDevTools(null,
                env(Map.of("SUGR_DEV_URL", "http://localhost:5173", "SUGR_DEVTOOLS", "0"))));
    }

    @Test
    void explicitTrueWinsOverEverything() {
        assertTrue(Window.resolveDevTools(true, env(Map.of("SUGR_DEVTOOLS", "0"))));
    }

    @Test
    void explicitFalseWinsOverEverything() {
        assertFalse(Window.resolveDevTools(false,
                env(Map.of("SUGR_DEV_URL", "http://localhost:5173", "SUGR_DEVTOOLS", "1"))));
    }
}
