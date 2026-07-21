package com.sugr.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/** `sugr doctor` - checks the environment for the tools sugr apps need to build and run. */
@Command(name = "doctor", description = "Check your environment (GraalVM, Node, pnpm, ...)")
final class DoctorCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        boolean allOk = true;
        allOk &= check("Java", this::checkJava);
        allOk &= check("GraalVM native-image", this::checkNativeImage);
        allOk &= check("Node.js", () -> firstLine(ProcessUtil.runCapture("node", "--version")));
        allOk &= check("pnpm", () -> firstLine(ProcessUtil.runCapture("pnpm", "--version")));
        allOk &= check("Gradle", () -> firstLineContaining(ProcessUtil.runCapture("gradle", "-v"), "Gradle "));
        if (ProcessUtil.isWindows()) {
            allOk &= check("WebView2 Runtime", this::checkWebView2);
        }

        System.out.println();
        System.out.println(allOk ? "All checks passed." : "Some checks failed - see above.");
        return allOk ? 0 : 1;
    }

    private interface Check {
        String run();
    }

    private boolean check(String name, Check check) {
        String result;
        try {
            result = check.run();
        } catch (Exception e) {
            result = null;
        }
        boolean ok = result != null && !result.isBlank();
        System.out.printf("[%s] %-24s %s%n", ok ? "OK" : "MISSING", name, ok ? result.trim() : "not found on PATH");
        return ok;
    }

    private String checkJava() {
        String version = System.getProperty("java.version");
        // Oracle GraalVM reports itself via java.vendor.version (java.vm.name stays
        // the generic "Java HotSpot(TM)..." string), Community edition via java.vm.name.
        String signal = (System.getProperty("java.vendor.version", "") + " "
                + System.getProperty("java.vm.name", "")).toLowerCase();
        boolean isGraal = signal.contains("graalvm") || signal.contains("substrate");
        return version + (isGraal ? " (GraalVM)" : " (not GraalVM - native-image builds need a GraalVM JDK)");
    }

    private String checkNativeImage() {
        String out = ProcessUtil.runCapture("native-image", "--version");
        return firstLine(out);
    }

    private String checkWebView2() {
        // The Edge WebView2 runtime registers this key when installed.
        String out = ProcessUtil.runCapture("reg", "query",
                "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\EdgeUpdate\\Clients\\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
                "/v", "pv");
        return firstLineContaining(out, "pv");
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) return null;
        return output.lines().findFirst().orElse(null);
    }

    private static String firstLineContaining(String output, String needle) {
        if (output == null) return null;
        return output.lines().filter(l -> l.contains(needle)).findFirst().orElse(null);
    }
}
