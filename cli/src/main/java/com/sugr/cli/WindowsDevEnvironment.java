package com.sugr.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Locates Visual Studio's {@code vcvars64.bat} (puts cl.exe/link.exe on PATH)
 * and wraps commands to run after sourcing it - the scripted equivalent of
 * manually opening a "Developer Command Prompt for VS" before running
 * native-image, which is otherwise a required manual step on Windows.
 */
final class WindowsDevEnvironment {

    private WindowsDevEnvironment() {
    }

    // Matches a standard release install path segment like "\2022\" - preview/insider
    // builds use a different scheme (e.g. "\18\") that native-image's own environment
    // detection doesn't recognize, even though cl.exe/link.exe still resolve fine.
    private static final Pattern RELEASE_YEAR = Pattern.compile("[\\\\/](19|20)\\d{2}[\\\\/]");

    /** Searches the usual Visual Studio install roots for vcvars64.bat, preferring standard release installs. */
    static Optional<Path> findVcvars64() {
        List<Path> roots = List.of(
                Path.of("C:\\Program Files\\Microsoft Visual Studio"),
                Path.of("C:\\Program Files (x86)\\Microsoft Visual Studio"));
        List<Path> candidates = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root, 6)) {
                stream.filter(p -> p.getFileName() != null
                                && p.getFileName().toString().equalsIgnoreCase("vcvars64.bat"))
                        .forEach(candidates::add);
            } catch (IOException ignored) {
                // inaccessible subtree (permissions, junctions) - keep searching other roots
            }
        }
        return candidates.stream()
                .max(Comparator.comparing(p -> RELEASE_YEAR.matcher(p.toString()).find()));
    }

    /** Wraps {@code command} to run in a cmd.exe session that has already sourced vcvars64.bat. */
    static List<String> wrap(Path vcvars64, List<String> command) {
        StringBuilder joined = new StringBuilder();
        for (String arg : command) {
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            joined.append(arg.indexOf(' ') >= 0 ? '"' + arg + '"' : arg);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("cmd");
        cmd.add("/c");
        cmd.add("call \"" + vcvars64 + "\" >nul && " + joined);
        return cmd;
    }
}
