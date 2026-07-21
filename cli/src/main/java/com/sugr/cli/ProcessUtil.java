package com.sugr.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small ProcessBuilder helpers shared by the dev/build/doctor commands. */
final class ProcessUtil {

    private ProcessUtil() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
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
