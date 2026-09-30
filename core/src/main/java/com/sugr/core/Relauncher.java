package com.sugr.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts a fresh copy of the running app and exits this one - the last step of
 * applying an update, and the only way to restart a sugr app (the native event
 * loop owns the process, and {@code Window.reload()} deliberately only reloads
 * web content, not Java state).
 *
 * <p>The new process is fully detached: its stdio goes to the null device and
 * it is never waited on, so it survives this process exiting a few
 * milliseconds later.
 */
final class Relauncher {

    private Relauncher() {
    }

    /** Spawns a replacement process and calls {@code System.exit(0)}. Does not return. */
    static void relaunch() {
        AppInstall install = AppInstall.current();
        if (install.layout() == AppInstall.Layout.UNPACKAGED) {
            throw new IllegalStateException(
                    "relaunch() needs a packaged app - this process isn't a native-image binary");
        }
        try {
            spawn(install);
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't relaunch " + install.executable(), e);
        }
        System.exit(0);
    }

    /**
     * Starts the replacement without exiting, so callers that need to run their
     * own shutdown work first can sequence it themselves.
     */
    static void spawn(AppInstall install) throws IOException {
        List<String> command = new ArrayList<>();
        if (install.layout() == AppInstall.Layout.APP_BUNDLE) {
            // `open -n` hands the bundle to LaunchServices, which is what gives the
            // new instance a proper Dock entry and activation - launching
            // Contents/MacOS/<exe> directly produces a process macOS treats as
            // a background helper.
            command.add("open");
            command.add("-n");
            command.add(install.target().toString());
        } else {
            command.add(install.executable().toString());
        }

        Path workingDir = install.targetDir();
        new ProcessBuilder(command)
                .directory(workingDir != null ? workingDir.toFile() : null)
                .redirectOutput(ProcessBuilder.Redirect.to(nullDevice()))
                .redirectError(ProcessBuilder.Redirect.to(nullDevice()))
                .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                .start();
    }

    private static File nullDevice() {
        return new File(Os.isWindows() ? "NUL" : "/dev/null");
    }
}
