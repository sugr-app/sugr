package com.sugr.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only registry access through {@code reg.exe}, for the handful of
 * settings an administrator controls (update policy). Shelling out matches how
 * the rest of {@code core} reaches OS features it has no binding for, and keeps
 * this off the FFM surface native-image needs registered.
 *
 * <p>Every failure - no such key, {@code reg.exe} missing, a timeout - reads as
 * "nothing set". Policy is opt-in, so the absence of an answer must never be
 * mistaken for one.
 */
final class WindowsRegistry {

    private static final long TIMEOUT_MS = 3_000;

    /**
     * One value line of {@code reg query}'s output:
     * {@code <4 spaces>Name<4 spaces>REG_TYPE<4 spaces>data}. The name may contain
     * spaces and the data may be empty, hence the lazy name and optional data.
     */
    private static final Pattern VALUE_LINE = Pattern.compile("^\\s+(.+?)\\s+(REG_[A-Z0-9_]+)(?:\\s+(.*))?$");

    private WindowsRegistry() {
    }

    /** A registry value as {@code reg query} prints it - type name and textual data. */
    record Value(String type, String data) {

        /** True for a {@code REG_DWORD} that's set to anything but 0 - how a policy says "on". */
        boolean isEnabledDword() {
            if (!"REG_DWORD".equals(type) || data == null) {
                return false;
            }
            try {
                return Long.decode(data.trim()) != 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    /**
     * The values directly under {@code key} (e.g.
     * {@code HKLM\Software\Policies\com.example.app}), keyed by lowercased name.
     * Empty when the key doesn't exist or anything goes wrong.
     */
    static Map<String, Value> readKey(String key) {
        if (!Os.isWindows()) {
            return Map.of();
        }
        try {
            Process process = new ProcessBuilder("reg", "query", key)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), Charset.defaultCharset());
            }
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Map.of();
            }
            return process.exitValue() == 0 ? parse(output) : Map.of();
        } catch (IOException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    /** Visible for testing - parses {@code reg query} output for a single key. */
    static Map<String, Value> parse(String output) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            Matcher m = VALUE_LINE.matcher(line);
            if (m.matches()) {
                values.put(m.group(1).toLowerCase(Locale.ROOT),
                        new Value(m.group(2), m.group(3) == null ? "" : m.group(3)));
            }
        }
        return values;
    }
}
