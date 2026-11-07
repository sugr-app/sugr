package com.sugr.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemThemeTest {

    @Test
    void parsesWindowsPersonalizeRegistryKey() {
        String darkOutput = """

                HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize
                    AppsUseLightTheme    REG_DWORD    0x0
                    SystemUsesLightTheme    REG_DWORD    0x0
                """;
        assertEquals(SystemTheme.DARK,
                SystemTheme.fromWindowsPersonalize(WindowsRegistry.parse(darkOutput)));
        assertTrue(SystemTheme.fromWindowsPersonalize(WindowsRegistry.parse(darkOutput)).isDark());
        assertEquals("dark", SystemTheme.DARK.wireName());

        String lightOutput = """

                HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize
                    AppsUseLightTheme    REG_DWORD    0x1
                """;
        assertEquals(SystemTheme.LIGHT,
                SystemTheme.fromWindowsPersonalize(WindowsRegistry.parse(lightOutput)));
        assertFalse(SystemTheme.fromWindowsPersonalize(WindowsRegistry.parse(lightOutput)).isDark());
        assertEquals("light", SystemTheme.LIGHT.wireName());
    }

    @Test
    void defaultsToLightWhenKeyIsMissingOrMalformed() {
        assertEquals(SystemTheme.LIGHT, SystemTheme.fromWindowsPersonalize(Map.of()));
        assertEquals(SystemTheme.LIGHT, SystemTheme.fromWindowsPersonalize(
                WindowsRegistry.parse("    AppsUseLightTheme    REG_SZ    0")));
    }

    @Test
    void parsesMacAndLinuxOutputs() {
        assertEquals(SystemTheme.DARK, SystemTheme.fromMacDefaultsOutput("Dark\n"));
        assertEquals(SystemTheme.LIGHT, SystemTheme.fromMacDefaultsOutput(null));
        assertEquals(SystemTheme.LIGHT, SystemTheme.fromMacDefaultsOutput(""));

        assertEquals(SystemTheme.DARK, SystemTheme.fromGsettingsOutput("'prefer-dark'\n"));
        assertEquals(SystemTheme.LIGHT, SystemTheme.fromGsettingsOutput("'default'\n"));
        assertEquals(SystemTheme.LIGHT, SystemTheme.fromGsettingsOutput(null));
    }
}
