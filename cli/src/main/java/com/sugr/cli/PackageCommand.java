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

    @Option(names = {"-n", "--name"}, description = "App/installer name "
            + "(defaults to sugr.config.json's \"name\", or \"sugr-app\")")
    String name;

    @Option(names = {"--app-version"}, description = "App version "
            + "(defaults to sugr.config.json's \"appVersion\", or \"0.1.0\")")
    String appVersion;

    @Option(names = {"--icon"}, description = "Path to an icon file, .ico on Windows / .icns on macOS "
            + "(defaults to sugr.config.json's \"icon\")")
    String iconPath;

    @Option(names = {"-d", "--dest"}, description = "Where to write the installer", defaultValue = "dist")
    String destDir;

    @Option(names = {"--base-url"}, description = "Public URL the update files will be served from - used to "
            + "build absolute URLs in latest.json (e.g. a GitHub release's download URL)")
    String baseUrl;

    @Option(names = {"--channel"}, description = "Release channel; writes latest-<channel>.json instead of "
            + "latest.json (defaults to sugr.config.json's updater.channel)")
    String channel;

    @Option(names = {"--notes"}, description = "Release notes to put in the update manifest")
    String notes;

    @Option(names = {"--min-version"}, description = "Oldest version that can update to this one automatically; "
            + "anything older is told to reinstall")
    String minVersion;

    @Option(names = {"--rollout"}, description = "Staged rollout percentage (0-100) to record in the manifest")
    Integer rollout;

    @Option(names = {"--no-update-artifacts"}, description = "Build the installer only, skipping the signed "
            + "update artifact and manifest")
    boolean noUpdateArtifacts;

    /** Loaded in {@link #call}, then read by the per-OS packaging methods. */
    private SugrConfig config;

    /** Set by the per-OS packaging methods to the installer they produced, for the update manifest to reference. */
    private Path producedInstaller;

    @Override
    public Integer call() throws Exception {
        Path libPath = Path.of(libDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(libPath)) {
            System.err.println("[sugr] lib directory not found: " + libPath);
            return 1;
        }

        config = SugrConfig.load(Path.of(".").toAbsolutePath().normalize());
        name = name != null ? name : config.nameOrDefault();
        appVersion = appVersion != null ? appVersion : config.versionOrDefault();
        iconPath = iconPath != null ? iconPath : config.icon;

        // Bake name/version/identifier/updater settings into the binary before
        // native-image runs - this is the only way the packaged app learns its
        // own version, which the updater needs to compare against a release.
        config.writeAppMetadata(libPath, name, appVersion);
        System.out.println("[sugr] app metadata embedded (" + name + " " + appVersion
                + ", id " + config.identifierOrDefault() + ")");

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

        int exit;
        if (ProcessUtil.isWindows()) {
            exit = packageWindows(binary, dest);
        } else if (ProcessUtil.isMac()) {
            exit = packageMac(binary, dest);
        } else {
            exit = packageLinux(binary, dest);
        }
        if (exit != 0) {
            return exit;
        }
        return writeUpdateArtifacts(binary, dest);
    }

    /**
     * Writes the signed update artifact and {@code latest.json} alongside the
     * installer, when the app has configured an updater.
     *
     * <p>A configured {@code pubkey} with no signing key available is a hard
     * failure rather than a warning: quietly shipping a release with no update
     * artifact means every existing install stops seeing updates, and nothing
     * about the build output would say so.
     */
    private int writeUpdateArtifacts(Path binary, Path dest) {
        if (noUpdateArtifacts) {
            return 0;
        }
        SugrConfig.Updater updater = config.updater;
        if (updater == null || updater.pubkey() == null || updater.pubkey().isBlank()) {
            return 0;
        }

        try {
            String keyFile = SigningKeys.keyFileFromEnvironment();
            String keyPublic = SigningKeys.publicKeyOf(keyFile);
            if (!keyPublic.equals(updater.pubkey().trim())) {
                // Signing with the wrong key produces artifacts every installed
                // app will reject - and it would only be discovered by a user
                // whose update silently failed.
                System.err.println("[sugr] the signing key doesn't match \"pubkey\" in sugr.config.json.");
                System.err.println("[sugr]   config:  " + updater.pubkey().trim());
                System.err.println("[sugr]   key has: " + keyPublic);
                return 1;
            }

            String passphrase = System.getenv(SigningKeys.KEY_PASSWORD_ENV);
            java.security.PrivateKey privateKey =
                    SigningKeys.decrypt(keyFile, passphrase == null ? "" : passphrase);

            if (baseUrl == null || baseUrl.isBlank()) {
                System.out.println("[sugr] no --base-url given - latest.json will use bare filenames, "
                        + "fill in the URLs before publishing.");
            }

            UpdateArtifacts.Result result = UpdateArtifacts.write(dest, name, appVersion,
                    channel != null ? channel : updater.channelOrDefault(),
                    binary, installedFileName(), producedInstaller, baseUrl, notes, minVersion, rollout,
                    privateKey);

            System.out.println("[sugr] update artifact -> " + result.artifact());
            System.out.println("[sugr] signature       -> " + result.signature());
            System.out.println("[sugr] manifest        -> " + result.manifest() + " (" + result.target() + ")");
            return 0;
        } catch (IOException | java.security.GeneralSecurityException e) {
            System.err.println("[sugr] couldn't build update artifacts: " + e.getMessage());
            System.err.println("[sugr] (pass --no-update-artifacts to build just the installer)");
            return 1;
        }
    }

    /** What the executable is called once installed - the name the update artifact has to replace. */
    private String installedFileName() {
        return ProcessUtil.isWindows() ? name + ".exe" : name;
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
            producedInstaller = dest.resolve(name + "-" + appVersion + "-Setup.exe");
            System.out.println("[sugr] installer written to " + producedInstaller);
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

    // Registry key the WebView2 Runtime registers when installed - same key
    // DoctorCommand checks for. Evergreen bootstrapper link is Microsoft's
    // stable, permanent redirect (documented at
    // https://learn.microsoft.com/microsoft-edge/webview2/concepts/distribution#evergreen-bootstrapper) -
    // ~2MB, downloads the real runtime itself, so nothing large gets bundled.
    private static final String WEBVIEW2_REG_KEY =
            "SOFTWARE\\WOW6432Node\\Microsoft\\EdgeUpdate\\Clients\\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
    private static final String WEBVIEW2_BOOTSTRAPPER_URL = "https://go.microsoft.com/fwlink/p/?LinkId=2124703";

    /**
     * A per-user installer.
     *
     * <p>This installs into {@code %LOCALAPPDATA%\Programs\<name>} with
     * {@code RequestExecutionLevel user} rather than into {@code Program Files}
     * as administrator, which is what makes silent auto-updates possible: the
     * app can replace its own executable in a directory it owns, with no UAC
     * prompt and no installer re-run. It's the same choice VS Code's "User
     * Installer" and every Squirrel-based Electron app make, and the reason
     * {@code UpdateInstaller} can use its no-elevation binary-swap path.
     *
     * <p>Consequences that follow from it, all handled below: shell folders
     * must be resolved per-user ({@code SetShellVarContext current}), and the
     * uninstall entry goes in {@code HKCU} rather than {@code HKLM}.
     */
    private String nsisScript(Path stagingExe, Path dest) {
        String iconLine = iconPath != null ? "Icon \"" + Path.of(iconPath).toAbsolutePath() + "\"" : "";
        String outFile = dest.resolve(name + "-" + appVersion + "-Setup.exe").toString();
        String uninstallKey = "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + name;
        return """
                !include "LogicLib.nsh"

                Name "%s"
                OutFile "%s"
                InstallDir "$LOCALAPPDATA\\Programs\\%s"
                InstallDirRegKey HKCU "%s" "InstallLocation"
                RequestExecutionLevel user
                SetCompressor /SOLID lzma
                %s

                VIProductVersion "%s"
                VIAddVersionKey "ProductName" "%s"
                VIAddVersionKey "ProductVersion" "%s"
                VIAddVersionKey "FileVersion" "%s"
                VIAddVersionKey "FileDescription" "%s installer"
                VIAddVersionKey "LegalCopyright" ""

                Page directory
                Page instfiles
                UninstPage uninstConfirm
                UninstPage instfiles

                Section "Install"
                    SetShellVarContext current

                    ; WebView2 ships with Windows 11 and is installed per-machine or
                    ; per-user; check both before pulling down the bootstrapper.
                    ReadRegStr $0 HKLM "%s" "pv"
                    ${If} $0 == ""
                        ReadRegStr $0 HKCU "%s" "pv"
                    ${EndIf}
                    ${If} $0 == ""
                        DetailPrint "WebView2 Runtime not found - downloading bootstrapper..."
                        NSISdl::download "%s" "$TEMP\\MicrosoftEdgeWebview2Setup.exe"
                        Pop $1
                        ${If} $1 == "success"
                            ; No /silent: a per-user install can't elevate, and the
                            ; bootstrapper needs to prompt if it wants machine-wide.
                            ExecWait '"$TEMP\\MicrosoftEdgeWebview2Setup.exe" /install'
                            Delete "$TEMP\\MicrosoftEdgeWebview2Setup.exe"
                        ${Else}
                            MessageBox MB_OK "Couldn't download the WebView2 Runtime installer ($1). Install it manually from https://developer.microsoft.com/microsoft-edge/webview2/ before running %s."
                        ${EndIf}
                    ${EndIf}

                    SetOutPath "$INSTDIR"
                    File "%s"
                    CreateDirectory "$SMPROGRAMS\\%s"
                    CreateShortcut "$SMPROGRAMS\\%s\\%s.lnk" "$INSTDIR\\%s.exe"
                    CreateShortcut "$DESKTOP\\%s.lnk" "$INSTDIR\\%s.exe"
                    WriteUninstaller "$INSTDIR\\Uninstall.exe"

                    WriteRegStr HKCU "%s" "DisplayName" "%s"
                    WriteRegStr HKCU "%s" "DisplayVersion" "%s"
                    WriteRegStr HKCU "%s" "InstallLocation" "$INSTDIR"
                    WriteRegStr HKCU "%s" "DisplayIcon" "$INSTDIR\\%s.exe"
                    WriteRegStr HKCU "%s" "UninstallString" "$INSTDIR\\Uninstall.exe"
                    WriteRegDWORD HKCU "%s" "NoModify" 1
                    WriteRegDWORD HKCU "%s" "NoRepair" 1
                SectionEnd

                Section "Uninstall"
                    SetShellVarContext current
                    Delete "$INSTDIR\\%s.exe"
                    ; Leftovers from an interrupted in-place update, if any.
                    Delete "$INSTDIR\\%s.exe.sugr-old"
                    Delete "$INSTDIR\\%s.exe.sugr-new"
                    Delete "$INSTDIR\\Uninstall.exe"
                    Delete "$SMPROGRAMS\\%s\\%s.lnk"
                    RMDir "$SMPROGRAMS\\%s"
                    Delete "$DESKTOP\\%s.lnk"
                    RMDir "$INSTDIR"
                    DeleteRegKey HKCU "%s"
                SectionEnd
                """.formatted(
                name, outFile, name, uninstallKey, iconLine,
                versionInfoQuad(appVersion), name, appVersion, appVersion, name,
                WEBVIEW2_REG_KEY, WEBVIEW2_REG_KEY, WEBVIEW2_BOOTSTRAPPER_URL, name,
                stagingExe, name, name, name, name, name, name,
                uninstallKey, name, uninstallKey, appVersion, uninstallKey,
                uninstallKey, name, uninstallKey, uninstallKey, uninstallKey,
                name, name, name, name, name, name, name, uninstallKey);
    }

    /**
     * Windows version resources must be exactly four numeric parts, so
     * {@code 1.2.3} becomes {@code 1.2.3.0} and any {@code -beta} suffix is
     * dropped. Without this the installer shows no version at all in its file
     * properties, which is the first place anyone looks when two builds are
     * side by side.
     */
    static String versionInfoQuad(String version) {
        String numeric = version.split("[-+]", 2)[0];
        String[] parts = numeric.split("\\.");
        StringBuilder quad = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                quad.append('.');
            }
            String part = i < parts.length ? parts[i] : "0";
            quad.append(part.matches("\\d+") ? part : "0");
        }
        return quad.toString();
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
                    <key>CFBundleIdentifier</key><string>%s</string>
                    <key>CFBundleShortVersionString</key><string>%s</string>
                    <key>CFBundleVersion</key><string>%s</string>
                    <key>CFBundleIconFile</key><string>icon.icns</string>
                    <key>CFBundlePackageType</key><string>APPL</string>
                </dict>
                </plist>
                """.formatted(name, name, config.identifierOrDefault(), appVersion, appVersion));

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
            producedInstaller = dmg;
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
            // Recorded as the manifest's fallback download, not as something the
            // updater will run: a .deb belongs to the package manager, so an app
            // installed from one is told to update through apt instead.
            producedInstaller = debFile;
            System.out.println("[sugr] .deb written to " + debFile);
        }
        return exit;
    }
}
