package com.sugr.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence of the window-position/size preference ({@code Application.Builder.restoreWindowState}).
 * Everything here is plain file I/O - no native window involved - so it runs on every OS.
 */
final class WindowStatePersistorTest {

    @TempDir
    Path tmp;

    private WindowStatePersistor persistor() {
        return new WindowStatePersistor(tmp.resolve("window-state.properties"));
    }

    @Test
    void loadReturnsNullBeforeAnythingIsSaved() {
        assertNull(persistor().load());
    }

    @Test
    void savedStateRoundTrips() {
        WindowStatePersistor p = persistor();
        p.save(120, 90, 1024, 640, false);

        WindowStatePersistor.State state = p.load();
        assertNotNull(state);
        assertEquals(120, state.x());
        assertEquals(90, state.y());
        assertEquals(1024, state.width());
        assertEquals(640, state.height());
        assertFalse(state.maximized());
    }

    @Test
    void maximizedFlagRoundTrips() {
        WindowStatePersistor p = persistor();
        p.save(0, 0, 800, 600, true);
        assertTrue(p.load().maximized());
    }

    @Test
    void negativeCoordinatesRoundTrip() {
        // The user parked the window on a monitor to the left of the primary one.
        WindowStatePersistor p = persistor();
        p.save(-1920, 40, 900, 700, false);

        WindowStatePersistor.State state = p.load();
        assertEquals(-1920, state.x());
        assertEquals(40, state.y());
    }

    @Test
    void savingAgainReplacesTheEarlierState() {
        WindowStatePersistor p = persistor();
        p.save(10, 10, 400, 300, false);
        p.save(50, 60, 1200, 800, true);

        WindowStatePersistor.State state = p.load();
        assertEquals(50, state.x());
        assertEquals(60, state.y());
        assertEquals(1200, state.width());
        assertEquals(800, state.height());
        assertTrue(state.maximized());
    }

    @Test
    void saveCreatesMissingParentDirectories() {
        Path nested = tmp.resolve("a").resolve("b").resolve("window-state.properties");
        WindowStatePersistor p = new WindowStatePersistor(nested);
        p.save(1, 2, 3, 4, false);

        assertTrue(Files.exists(nested));
        assertNotNull(p.load());
    }

    @Test
    void aFileMissingSomeBoundsIsIgnored() throws IOException {
        Path file = tmp.resolve("window-state.properties");
        Files.writeString(file, "x=10\ny=20\nwidth=800\n"); // no height
        assertNull(new WindowStatePersistor(file).load());
    }

    @Test
    void nonNumericBoundsAreIgnored() throws IOException {
        Path file = tmp.resolve("window-state.properties");
        Files.writeString(file, "x=10\ny=20\nwidth=wide\nheight=600\n");
        assertNull(new WindowStatePersistor(file).load());
    }

    @Test
    void whitespaceAroundNumbersIsTolerated() throws IOException {
        Path file = tmp.resolve("window-state.properties");
        Files.writeString(file, "x = 10 \ny=\t20\nwidth = 800\nheight = 600 \n");

        WindowStatePersistor.State state = new WindowStatePersistor(file).load();
        assertNotNull(state);
        assertEquals(10, state.x());
        assertEquals(20, state.y());
        assertEquals(800, state.width());
        assertEquals(600, state.height());
    }

    @Test
    void aMissingMaximizedKeyDefaultsToFalse() throws IOException {
        Path file = tmp.resolve("window-state.properties");
        Files.writeString(file, "x=0\ny=0\nwidth=800\nheight=600\n");
        assertFalse(new WindowStatePersistor(file).load().maximized());
    }

    @Test
    void garbageContentIsTreatedAsNoSavedState() throws IOException {
        Path file = tmp.resolve("window-state.properties");
        Files.writeString(file, "not a properties file at all !!! \0\0");
        assertNull(new WindowStatePersistor(file).load());
    }

    @Test
    void persistorFaithfullyRoundTripsAnOffScreenRect() {
        // WindowStatePersistor itself does no sanity-checking - a minimized window's bogus
        // {-32000,-32000} rect survives a round-trip. Rejecting it is Window#open's job
        // (isRestorableWindowState); this test pins down that the split of responsibility
        // is intentional.
        WindowStatePersistor p = persistor();
        p.save(-32000, -32000, 160, 28, false);

        WindowStatePersistor.State state = p.load();
        assertEquals(-32000, state.x());
        assertEquals(160, state.width());
    }
}
