package com.sugr.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small ProcessBuilder helpers shared by the dev/build/doctor commands. */
final class ProcessUtil {

    private ProcessUtil() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /**
     * Env var(s) that make the app's webview expose a Chrome DevTools Protocol
     * endpoint on {@code port}, so an IDE (or chrome://inspect) can attach a real
     * debugger to it - the same window the user sees, not a separate browser tab.
     * Windows (WebView2) honors {@code WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS} -
     * verified end to end (see docs/guide/build.md). Linux (WebKitGTK) has the
     * equivalent {@code WEBKIT_INSPECTOR_SERVER}, unverified on this checkout like
     * the rest of the Linux webview path. macOS's WKWebView has no such env var -
     * remote debugging there needs Safari's own Develop menu instead.
     */
    static Map<String, String> cdpDebugEnv(int port) {
        if (isWindows()) {
            return Map.of("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", "--remote-debugging-port=" + port);
        } else if (!isMac()) {
            return Map.of("WEBKIT_INSPECTOR_SERVER", "127.0.0.1:" + port);
        }
        return Map.of();
    }

    /** Wraps a command through cmd /c on Windows so .cmd/.bat launchers (pnpm, gradle) resolve correctly. */
    static List<String> shellCommand(String... args) {
        List<String> cmd = new ArrayList<>();
        if (isWindows()) {
            cmd.add("cmd");
            cmd.add("/c");
        }
        for (String a : args) {
            cmd.add(a);
        }
        return cmd;
    }

    /** Runs a command to completion, returns its combined stdout+stderr, or null if it couldn't even start. */
    static String runCapture(String... args) {
        try {
            Process p = new ProcessBuilder(shellCommand(args))
                    .redirectErrorStream(true)
                    .start();
            String output = readAll(p);
            p.waitFor();
            return output;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    static String readAll(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
