package com.sugr.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing {@code reg query} output, and the update-policy decision built on it. */
class WindowsRegistryTest {

    private static final String POLICY_KEY_OUTPUT = """

            HKEY_LOCAL_MACHINE\\Software\\Policies\\com.example.app
                DisableUpdates    REG_DWORD    0x1
                Some Other Value    REG_SZ    hello world
                Empty    REG_SZ

            """;

    @Test
    void parsesValueLinesIncludingSpacesAndEmptyData() {
        Map<String, WindowsRegistry.Value> values = WindowsRegistry.parse(POLICY_KEY_OUTPUT);

        assertEquals(new WindowsRegistry.Value("REG_DWORD", "0x1"), values.get("disableupdates"));
        assertEquals("hello world", values.get("some other value").data());
        assertEquals("", values.get("empty").data());
        assertEquals(3, values.size(), "the key header line is not a value");
    }

    @Test
    void aDwordOfOneDisablesUpdates() {
        assertTrue(Updater.policyDisables(WindowsRegistry.parse(POLICY_KEY_OUTPUT)));
    }

    @Test
    void zeroAWrongTypeOrNothingLeavesUpdatesOn() {
        assertFalse(Updater.policyDisables(WindowsRegistry.parse("    DisableUpdates    REG_DWORD    0x0")));
        assertFalse(Updater.policyDisables(WindowsRegistry.parse("    DisableUpdates    REG_SZ    1")));
        assertFalse(Updater.policyDisables(WindowsRegistry.parse("ERROR: The system was unable to find the "
                + "specified registry key or value.")));
        assertFalse(Updater.policyDisables(Map.of()));
    }
}
