package com.sugr.core;

/** Tiny OS-detection/scripting helpers shared by {@link Dialogs}, {@link Clipboard}, and {@link Tray}. */
final class Os {

    private Os() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /** Wraps a string as a single-quoted PowerShell literal (only ' needs escaping - doubled). */
    static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
