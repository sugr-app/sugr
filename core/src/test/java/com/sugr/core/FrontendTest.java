package com.sugr.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class FrontendTest {

    @Test
    void devServerCarriesItsUrl() {
        Frontend frontend = Frontend.devServer("http://localhost:5173");
        Frontend.DevServer devServer = assertInstanceOf(Frontend.DevServer.class, frontend);
        assertEquals("http://localhost:5173", devServer.url());
    }

    @Test
    void embeddedCarriesItsResourceRoot() {
        Frontend frontend = Frontend.embedded("/frontend");
        Frontend.Embedded embedded = assertInstanceOf(Frontend.Embedded.class, frontend);
        assertEquals("/frontend", embedded.resourceRoot());
    }

    @Test
    void autoFallsBackToEmbeddedWhenDevUrlIsNotSet() {
        // SUGR_DEV_URL is only ever set on a dev-launched app's own process environment
        // (see DevCommand.restartApp) - a plain test run never has it set.
        Frontend frontend = Frontend.auto("/custom-root");
        Frontend.Embedded embedded = assertInstanceOf(Frontend.Embedded.class, frontend);
        assertEquals("/custom-root", embedded.resourceRoot());
    }

    @Test
    void noArgAutoDefaultsToSlashFrontend() {
        Frontend frontend = Frontend.auto();
        Frontend.Embedded embedded = assertInstanceOf(Frontend.Embedded.class, frontend);
        assertEquals("/frontend", embedded.resourceRoot());
    }
}
