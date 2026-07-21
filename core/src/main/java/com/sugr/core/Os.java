package com.sugr.core;

/** Tiny OS-detection helper shared by {@link Dialogs} and {@link Clipboard}. */
final class Os {

    private Os() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
