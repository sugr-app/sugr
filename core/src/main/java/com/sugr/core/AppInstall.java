package com.sugr.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Answers "where and how is this app installed?" - the questions the updater
 * has to settle before it can decide whether it's allowed to replace anything.
 *
 * <p>The interesting one is {@link #isWritable()}. A per-user install
 * ({@code %LOCALAPPDATA%\Programs\...}, {@code ~/Applications}) can be updated
 * by swapping files in place with no elevation at all, which is what
 * {@code sugr package} now produces by default. A machine-wide install
 * ({@code C:\Program Files}, {@code /usr/bin} from a {@code .deb}) cannot, and
 * has to fall back to re-running an installer or telling the user to download
 * one - so the answer here picks the install strategy.
 */
final class AppInstall {

    /**
     * Set to {@code "runtime"} inside a native-image binary. Checking the
     * property rather than {@code org.graalvm.nativeimage.ImageInfo} keeps
     * {@code core} free of a compile-time dependency on the SVM API, which it
     * otherwise doesn't need.
     */
    private static final String IMAGE_CODE_PROPERTY = "org.graalvm.nativeimage.imagecode";

    /** What kind of thing has to be replaced to apply an update. */
    enum Layout {
        /** A single self-contained executable - Windows and Linux. Replace the file. */
        EXECUTABLE,
        /** A macOS {@code .app} directory. Replace the whole bundle. */
        APP_BUNDLE,
        /** Not a packaged app at all (running from Gradle, a JVM classpath, or a dev server). */
        UNPACKAGED
    }

    private final Layout layout;
    private final Path executable;
    private final Path target;

    private AppInstall(Layout layout, Path executable, Path target) {
        this.layout = layout;
        this.executable = executable;
        this.target = target;
    }

    private static volatile AppInstall cached;

    static AppInstall current() {
        AppInstall local = cached;
        if (local == null) {
            synchronized (AppInstall.class) {
                local = cached;
                if (local == null) {
                    local = detect();
                    cached = local;
                }
            }
        }
        return local;
    }

    Layout layout() {
        return layout;
    }

    /** The running executable, or null when {@link #layout()} is {@link Layout#UNPACKAGED}. */
    Path executable() {
        return executable;
    }

    /**
     * What an update has to replace: the executable file itself, or the
     * {@code .app} bundle directory on macOS. Null when unpackaged.
     */
    Path target() {
        return target;
    }

    /** The directory the replacement gets written into - always on the same volume as {@link #target()}. */
    Path targetDir() {
        return target == null ? null : target.getParent();
    }

    /** True when this process is a native-image binary rather than a JVM run. */
    static boolean isNativeImage() {
        return "runtime".equals(System.getProperty(IMAGE_CODE_PROPERTY));
    }

    /** True when {@code sugr dev} launched us - the updater stays off so it can't fight the dev loop. */
    static boolean isDevMode() {
        String devUrl = System.getenv("SUGR_DEV_URL");
        return devUrl != null && !devUrl.isBlank();
    }

    /**
     * Whether the current user can replace {@link #target()} in place. On
     * Windows this is the difference between a silent update and a UAC prompt;
     * everywhere it's the difference between swapping files and deferring to a
     * package manager.
     *
     * <p>{@link Files#isWritable} on the directory is checked as well as the
     * target, because replacing a file means creating and renaming siblings
     * next to it, not writing through the existing handle.
     */
    boolean isWritable() {
        if (target == null) {
            return false;
        }
        Path dir = targetDir();
        return dir != null && Files.isWritable(dir) && Files.isWritable(target);
    }

    private static AppInstall detect() {
        Optional<String> command = ProcessHandle.current().info().command();
        if (command.isEmpty() || !isNativeImage()) {
            // A JVM run reports the java launcher, not the app - there's nothing
            // meaningful to replace, so don't pretend otherwise.
            return new AppInstall(Layout.UNPACKAGED, null, null);
        }

        Path executable;
        try {
            executable = Path.of(command.get()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return new AppInstall(Layout.UNPACKAGED, null, null);
        }
        if (!Files.isRegularFile(executable)) {
            return new AppInstall(Layout.UNPACKAGED, null, null);
        }

        Path bundle = macAppBundle(executable);
        return bundle != null
                ? new AppInstall(Layout.APP_BUNDLE, executable, bundle)
                : new AppInstall(Layout.EXECUTABLE, executable, executable);
    }

    /**
     * Walks up from {@code <Something>.app/Contents/MacOS/<exe>} to
     * {@code <Something>.app}, or returns null when the executable isn't inside
     * a bundle (a bare binary on the PATH, say).
     */
    private static Path macAppBundle(Path executable) {
        Path macos = executable.getParent();
        if (macos == null || !"MacOS".equals(fileName(macos))) {
            return null;
        }
        Path contents = macos.getParent();
        if (contents == null || !"Contents".equals(fileName(contents))) {
            return null;
        }
        Path app = contents.getParent();
        return app != null && fileName(app).endsWith(".app") ? app : null;
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }
}
