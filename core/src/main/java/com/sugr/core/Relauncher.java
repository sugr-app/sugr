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

    /**
     * How long {@link #relaunchSoon} waits before exiting - enough for a bridge
     * reply queued on the UI thread to reach the webview first.
     */
    private static final long REPLY_GRACE_MS = 300;

    /** Spawns a replacement process and calls {@code System.exit(0)}. Does not return. */
    static void relaunch() {
        AppInstall install = requirePackaged();
        try {
            spawn(install);
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't relaunch " + install.executable(), e);
        }
        System.exit(0);
    }

    /**
     * {@link #relaunch()} from a separate thread after a short grace period, so
     * a caller on a bridge handler can return its reply first. Exiting from
     * inside the webview's native callback on the UI thread would drop that
     * reply and leave the frontend's promise hanging as the window vanishes.
     *
     * @throws IllegalStateException immediately, rather than on the later
     *                               thread, when this isn't a packaged app
     */
    static void relaunchSoon() {
        requirePackaged();
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(REPLY_GRACE_MS);
                relaunch();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                System.err.println("[sugr] couldn't relaunch: " + e.getMessage());
            }
        }, "sugr-relaunch");
        thread.start();
    }

    private static AppInstall requirePackaged() {
        AppInstall install = AppInstall.current();
        if (install.layout() == AppInstall.Layout.UNPACKAGED) {
            throw new IllegalStateException(
                    "relaunch() needs a packaged app - this process isn't a native-image binary");
        }
        return install;
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
