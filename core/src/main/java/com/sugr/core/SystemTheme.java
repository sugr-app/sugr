package com.sugr.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Queries the OS's current color scheme ({@link #LIGHT} or {@link #DARK}).
 *
 * <ul>
 *   <li><b>Windows</b>: reads {@code AppsUseLightTheme} under
 *       {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize}
 *       via {@link WindowsRegistry} ({@code 0x0} = dark, {@code 0x1} or absent = light).
 *   <li><b>macOS</b>: reads {@code defaults read -g AppleInterfaceStyle}
 *       ({@code "Dark"} = dark, absent = light).
 *   <li><b>Linux</b>: reads {@code gsettings get org.gnome.desktop.interface color-scheme}
 *       ({@code 'prefer-dark'} = dark, otherwise light).
 * </ul>
 *
 * <p>To react to live OS theme switches while the app is open, register
 * {@link Window.Builder#onThemeChanged} or {@link Window.Builder#followSystemTheme}
 * (or {@code app.onThemeChanged} in {@code @sugr/runtime}), which are driven by
 * {@code WM_SETTINGCHANGE} on Windows.
 */
public enum SystemTheme {
    LIGHT("light"),
    DARK("dark");

    static final String WINDOWS_PERSONALIZE_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    static final String APPS_USE_LIGHT_THEME = "appsuselighttheme";

    private final String wireName;

    SystemTheme(String wireName) {
        this.wireName = wireName;
    }

    /** True when this theme is {@link #DARK}. */
    public boolean isDark() {
        return this == DARK;
    }

    /** Lowercase wire value ({@code "light"} or {@code "dark"}) sent over the JS bridge. */
    public String wireName() {
        return wireName;
    }

    /** Detects the OS's current color scheme, defaulting to {@link #LIGHT} when unknown. */
    public static SystemTheme current() {
        if (Os.isWindows()) {
            return fromWindowsPersonalize(WindowsRegistry.readKey(WINDOWS_PERSONALIZE_KEY));
        }
        if (Os.isMac()) {
            return fromMacDefaultsOutput(runCommand("defaults", "read", "-g", "AppleInterfaceStyle"));
        }
        return fromGsettingsOutput(
                runCommand("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"));
    }

    /** Convenience shorthand for {@code SystemTheme.current().isDark()}. */
    public static boolean isDarkTheme() {
        return current().isDark();
    }

    /**
     * Visible for testing - interprets the {@code Personalize} registry key.
     * {@code AppsUseLightTheme} is a {@code REG_DWORD} where {@code 0} means
     * dark mode and {@code 1} (or absent) means light mode.
     */
    static SystemTheme fromWindowsPersonalize(Map<String, WindowsRegistry.Value> values) {
        WindowsRegistry.Value v = values.get(APPS_USE_LIGHT_THEME);
        if (v == null || !"REG_DWORD".equals(v.type()) || v.data() == null) {
            return LIGHT;
        }
        return v.isEnabledDword() ? LIGHT : DARK;
    }

    static SystemTheme fromMacDefaultsOutput(String output) {
        if (output != null && output.strip().equalsIgnoreCase("dark")) {
            return DARK;
        }
        return LIGHT;
    }

    static SystemTheme fromGsettingsOutput(String output) {
        if (output != null && output.toLowerCase(Locale.ROOT).contains("dark")) {
            return DARK;
        }
        return LIGHT;
    }

    private static String runCommand(String... command) {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            p.getOutputStream().close();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(2_000, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
