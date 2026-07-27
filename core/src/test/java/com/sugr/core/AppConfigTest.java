package com.sugr.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AppConfigTest {

    @Test
    void fallsBackToDefaultEnvWhenVarIsUnset() {
        // A var name that's never set in any test runner's environment.
        AppConfig config = AppConfig.load("SUGR_APP_CONFIG_TEST_UNSET", "dev");
        assertEquals("dev", config.env());
    }

    @Test
    void loadsCommonPropertiesWhenNoEnvOverrideExists() {
        AppConfig config = AppConfig.load("SUGR_APP_CONFIG_TEST_UNSET", "prod");
        assertEquals("test-app", config.get("app.name"));
        assertEquals("INFO", config.get("app.logLevel"));
    }

    @Test
    void envOverrideWinsOverCommonDefaults() {
        AppConfig config = AppConfig.load("SUGR_APP_CONFIG_TEST_UNSET", "staging");
        assertEquals("staging", config.env());
        assertEquals("DEBUG", config.get("app.logLevel"));
        // app.name isn't redefined in application-staging.properties - falls through to common.
        assertEquals("test-app", config.get("app.name"));
    }

    @Test
    void getOrDefaultFallsBackForMissingKeys() {
        AppConfig config = AppConfig.load("SUGR_APP_CONFIG_TEST_UNSET", "dev");
        assertEquals("fallback", config.getOrDefault("does.not.exist", "fallback"));
    }

    @Test
    void getReturnsNullForMissingKeys() {
        AppConfig config = AppConfig.load("SUGR_APP_CONFIG_TEST_UNSET", "dev");
        assertNull(config.get("does.not.exist"));
    }

    @Test
    void isDevelopmentIsTrueOnlyForDevEnv() {
        AppConfig config = AppConfig.load("SUGR_APP_CONFIG_TEST_UNSET", "dev");
        assertTrue(config.isDevelopment());
        assertFalse(config.isStaging());
        assertFalse(config.isProduction());
    }

    @Test
    void isStagingIsTrueOnlyForStagingEnv() {
        AppConfig config = AppConfig.load("SUGR_APP_CONFIG_TEST_UNSET", "staging");
        assertFalse(config.isDevelopment());
        assertTrue(config.isStaging());
        assertFalse(config.isProduction());
    }

    @Test
    void isProductionIsTrueOnlyForProdEnv() {
        AppConfig config = AppConfig.load("SUGR_APP_CONFIG_TEST_UNSET", "prod");
        assertFalse(config.isDevelopment());
        assertFalse(config.isStaging());
        assertTrue(config.isProduction());
    }
}
