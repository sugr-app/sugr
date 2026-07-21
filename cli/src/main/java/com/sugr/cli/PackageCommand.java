package com.sugr.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * `sugr package` - builds a native-image binary via Gradle, then wraps it
 * directly into an OS installer (no JVM/jlink runtime involved, since
 * native-image already produces a standalone executable). jpackage isn't
 * used here - it's designed around bundling a Java runtime image, and has
 * no supported way to wrap an arbitrary non-JVM executable, so each OS gets
 * its own native packaging tool instead: NSIS (Windows), a hand-built .app
 * bundle + hdiutil (macOS), and a hand-built control file + dpkg-deb (Linux).
 *
 * <p>Windows is exercised end-to-end on this checkout (native-image build via
 * a scripted Visual Studio dev environment - see {@link WindowsDevEnvironment} -
 * plus NSIS packaging). The macOS and Linux paths follow the same shape using
 * their platforms' standard, well-documented tools, but haven't been run on
 * those OSes yet - treat them as a solid starting point, not verified output.
 */
@Command(name = "package", description = "Build a native-image binary and package it as an OS installer")
final class PackageCommand implements Callable<Integer> {

    @Option(names = {"-l", "--lib-dir"}, description = "Path to the Java module dir (containing build.gradle.kts)",
            defaultValue = "lib")
    String libDir;

    @Option(names = {"-t", "--task"}, description = "Gradle task to run for the native build "
            + "(auto-detected by default - see GradleProjectLocator)")
    String gradleTask;

    @Option(names = {"-g", "--gradle-dir"}, description = "Directory to run gradle from "
            + "(auto-detected by default - see GradleProjectLocator)")
    String gradleDir;

    @Option(names = {"-n", "--name"}, description = "App/installer name", defaultValue = "sugr-app")
    String name;

    @Option(names = {"--app-version"}, description = "App version", defaultValue = "0.1.0")
    String appVersion;

    @Option(names = {"--icon"}, description = "Path to an icon file (.ico on Windows, .icns on macOS)")
    String iconPath;

    @Option(names = {"-d", "--dest"}, description = "Where to write the installer", defaultValue = "dist")
    String destDir;

    @Override
    public Integer call() throws Exception {
        Path libPath = Path.of(libDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(libPath)) {
            System.err.println("[sugr] lib directory not found: " + libPath);
            return 1;
        }

        System.out.println("[sugr] building native-image binary ...");
        int buildExit = runNativeCompile();
        if (buildExit != 0) {
            System.err.println("[sugr] native-image build failed (exit " + buildExit + ")");
            return buildExit;
        }

        Path nativeOutDir = libPath.resolve("build").resolve("native").resolve("nativeCompile");
        Path binary = findBinary(nativeOutDir);
        if (binary == null) {
            System.err.println("[sugr] couldn't find the native-image output in " + nativeOutDir);
            return 1;
        }
        System.out.println("[sugr] native binary: " + binary);

        Path dest = Path.of(destDir).toAbsolutePath().normalize();
        Files.createDirectories(dest);

        if (ProcessUtil.isWindows()) {
            return packageWindows(binary, dest);
        } else if (isMac()) {
            return packageMac(binary, dest);
        } else {
            return packageLinux(binary, dest);
        }
    }

    private int runNativeCompile() throws IOException, InterruptedException {
        GradleProjectLocator.Result located = GradleProjectLocator.resolve(gradleTask, gradleDir, "nativeCompile");
        List<String> gradleCmd = List.of("gradle", located.task(), "--console=plain");

        List<String> command;
        if (ProcessUtil.isWindows()) {
            Optional<Path> vcvars = WindowsDevEnvironment.findVcvars64();
            if (vcvars.isEmpty()) {
                System.err.println("[sugr] couldn't find vcvars64.bat - install the \"Desktop development with "
                        + "C++\" workload for Visual Studio (native-image needs cl.exe/link.exe on Windows).");
                return 1;
            }
            System.out.println("[sugr] using Visual Studio dev environment: " + vcvars.get());
            command = WindowsDevEnvironment.wrap(vcvars.get(), gradleCmd);
        } else {
            command = gradleCmd;
        }

        System.out.println("[sugr] running gradle " + located.task() + " (from " + located.gradleDir() + ") ...");
        Process p = new ProcessBuilder(command)
                .directory(located.gradleDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        return p.waitFor();
    }

    /** The only real output in nativeCompile's dir besides logged {@code .args} build-invocation files. */
    private static Path findBinary(Path nativeOutDir) throws IOException {
        if (!Files.isDirectory(nativeOutDir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(nativeOutDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".args"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    // ---- Windows: NSIS ----

    private int packageWindows(Path binary, Path dest) throws IOException, InterruptedException {
        Path makensis = findMakensis();
        if (makensis == null) {
            System.err.println("[sugr] couldn't find makensis.exe - install NSIS (https://nsis.sourceforge.io/) "
                    + "and make sure it's on PATH.");
            return 1;
        }

        Path stagingExe = dest.resolve(name + ".exe");
        Files.copy(binary, stagingExe, StandardCopyOption.REPLACE_EXISTING);

        Path script = Files.createTempFile("sugr-package-", ".nsi");
        Files.writeString(script, nsisScript(stagingExe, dest));
        System.out.println("[sugr] running makensis ...");
        Process p = new ProcessBuilder(makensis.toString(), script.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        int exit = p.waitFor();
        Files.deleteIfExists(script);
        Files.deleteIfExists(stagingExe);
        if (exit == 0) {
            System.out.println("[sugr] installer written to " + dest.resolve(name + "-" + appVersion + "-Setup.exe"));
        }
        return exit;
    }

    private static Path findMakensis() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(dir, "makensis.exe");
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        Path fallback = Path.of("C:\\Program Files (x86)\\NSIS\\makensis.exe");
        return Files.isRegularFile(fallback) ? fallback : null;
    }

    private String nsisScript(Path stagingExe, Path dest) {
        String iconLine = iconPath != null ? "Icon \"" + Path.of(iconPath).toAbsolutePath() + "\"" : "";
        String outFile = dest.resolve(name + "-" + appVersion + "-Setup.exe").toString();
        return """
                Name "%s"
                OutFile "%s"
                InstallDir "$PROGRAMFILES64\\%s"
                RequestExecutionLevel admin
                %s

                Page directory
                Page instfiles
                UninstPage uninstConfirm
                UninstPage instfiles

                Section "Install"
                    SetOutPath "$INSTDIR"
                    File "%s"
                    CreateDirectory "$SMPROGRAMS\\%s"
                    CreateShortcut "$SMPROGRAMS\\%s\\%s.lnk" "$INSTDIR\\%s.exe"
                    CreateShortcut "$DESKTOP\\%s.lnk" "$INSTDIR\\%s.exe"
                    WriteUninstaller "$INSTDIR\\Uninstall.exe"
                SectionEnd

                Section "Uninstall"
                    Delete "$INSTDIR\\%s.exe"
                    Delete "$INSTDIR\\Uninstall.exe"
                    Delete "$SMPROGRAMS\\%s\\%s.lnk"
                    RMDir "$SMPROGRAMS\\%s"
                    Delete "$DESKTOP\\%s.lnk"
                    RMDir "$INSTDIR"
                SectionEnd
                """.formatted(
                name, outFile, name, iconLine,
                stagingExe, name, name, name, name, name, name,
                name, name, name, name, name);
    }

    // ---- macOS: .app bundle + hdiutil (unverified on this checkout - see class javadoc) ----

    private int packageMac(Path binary, Path dest) throws IOException, InterruptedException {
        Path appBundle = dest.resolve(name + ".app");
        Path macosDir = appBundle.resolve("Contents").resolve("MacOS");
        Path resourcesDir = appBundle.resolve("Contents").resolve("Resources");
        Files.createDirectories(macosDir);
        Files.createDirectories(resourcesDir);

        Path executable = macosDir.resolve(name);
        Files.copy(binary, executable, StandardCopyOption.REPLACE_EXISTING);
        executable.toFile().setExecutable(true, false);

        if (iconPath != null) {
            Files.copy(Path.of(iconPath), resourcesDir.resolve("icon.icns"), StandardCopyOption.REPLACE_EXISTING);
        }

        Files.writeString(appBundle.resolve("Contents").resolve("Info.plist"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>CFBundleName</key><string>%s</string>
                    <key>CFBundleExecutable</key><string>%s</string>
                    <key>CFBundleIdentifier</key><string>com.sugr.%s</string>
                    <key>CFBundleVersion</key><string>%s</string>
                    <key>CFBundleIconFile</key><string>icon.icns</string>
                    <key>CFBundlePackageType</key><string>APPL</string>
                </dict>
                </plist>
                """.formatted(name, name, name, appVersion));

        Path dmg = dest.resolve(name + "-" + appVersion + ".dmg");
        Files.deleteIfExists(dmg);
        System.out.println("[sugr] running hdiutil ...");
        Process p = new ProcessBuilder("hdiutil", "create", "-volname", name, "-srcfolder",
                appBundle.toString(), "-ov", "-format", "UDZO", dmg.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        int exit = p.waitFor();
        if (exit == 0) {
            System.out.println("[sugr] disk image written to " + dmg);
        }
        return exit;
    }

    // ---- Linux: .deb via dpkg-deb (unverified on this checkout - see class javadoc) ----

    private int packageLinux(Path binary, Path dest) throws IOException, InterruptedException {
        Path stagingRoot = dest.resolve(name + "-" + appVersion + "-amd64");
        Path binDir = stagingRoot.resolve("usr").resolve("bin");
        Path debianDir = stagingRoot.resolve("DEBIAN");
        Files.createDirectories(binDir);
        Files.createDirectories(debianDir);

        Path executable = binDir.resolve(name);
        Files.copy(binary, executable, StandardCopyOption.REPLACE_EXISTING);
        executable.toFile().setExecutable(true, false);

        Files.writeString(debianDir.resolve("control"), """
                Package: %s
                Version: %s
                Architecture: amd64
                Maintainer: unspecified
                Description: %s (built with sugr)
                """.formatted(name, appVersion, name));

        Path debFile = dest.resolve(name + "_" + appVersion + "_amd64.deb");
        System.out.println("[sugr] running dpkg-deb ...");
        Process p = new ProcessBuilder("dpkg-deb", "--build", stagingRoot.toString(), debFile.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        int exit = p.waitFor();
        FileTrees.deleteRecursively(stagingRoot);
        if (exit == 0) {
            System.out.println("[sugr] .deb written to " + debFile);
        }
        return exit;
    }
}
