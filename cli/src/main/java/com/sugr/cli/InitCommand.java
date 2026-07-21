package com.sugr.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * `sugr init <name>` - scaffolds the react-ts template into examples/<name>
 * of the current sugr checkout and registers it in settings.gradle.kts.
 *
 * Scoped to "inside a sugr checkout" for now, not a standalone project you
 * can create anywhere: core/bridge/processor aren't published anywhere yet
 * (that's Giai đoạn 4 work), so the only way a scaffolded app can depend on
 * them today is as a sibling Gradle project in the same build.
 */
@Command(name = "init", description = "Scaffold a new app from the react-ts template")
final class InitCommand implements Callable<Integer> {

    private static final Set<String> BINARY_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".ico", ".dll", ".so", ".dylib");

    @Parameters(index = "0", description = "App name - used as the examples/ directory name, Java package, and window title")
    String name;

    @Override
    public Integer call() throws Exception {
        Path sugrRoot = findSugrRoot(Path.of(".").toAbsolutePath().normalize());
        if (sugrRoot == null) {
            System.err.println("[sugr] couldn't find a sugr checkout (no settings.gradle.kts with rootProject.name "
                    + "\"sugr\" in this directory or any parent).");
            System.err.println("[sugr] `sugr init` currently scaffolds into <sugr-checkout>/examples/<name> - "
                    + "publishing core/bridge so standalone projects can depend on them is planned for a later milestone.");
            return 1;
        }

        Path templateDir = sugrRoot.resolve("templates").resolve("react-ts");
        if (!Files.isDirectory(templateDir)) {
            System.err.println("[sugr] template not found at " + templateDir);
            return 1;
        }

        Path target = sugrRoot.resolve("examples").resolve(name);
        if (Files.exists(target)) {
            System.err.println("[sugr] " + target + " already exists");
            return 1;
        }

        String pkg = sanitizePackageName(name);
        System.out.println("[sugr] scaffolding \"" + name + "\" (package app." + pkg + ") into " + target + " ...");

        FileTrees.copyRecursively(templateDir, target, relative -> relative.replace("__pkg__", pkg));
        substitutePlaceholders(target, pkg, name);
        registerInSettings(sugrRoot, name);

        Path relativeTarget = sugrRoot.relativize(target);
        System.out.println();
        System.out.println("[sugr] done. Next steps:");
        System.out.println("  cd " + sugrRoot + " && pnpm install   (links @sugr/runtime into the new frontend)");
        System.out.println("  cd " + relativeTarget);
        System.out.println("  sugr dev");
        return 0;
    }

    private static Path findSugrRoot(Path start) throws IOException {
        Path cursor = start;
        while (cursor != null) {
            Path settings = cursor.resolve("settings.gradle.kts");
            if (Files.exists(settings) && Files.readString(settings).contains("rootProject.name = \"sugr\"")) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private static String sanitizePackageName(String rawName) {
        String cleaned = rawName.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0))) {
            cleaned = "app" + cleaned;
        }
        return cleaned;
    }

    /** Replaces __APP_NAME__ and __pkg__ inside every text file's content. */
    private static void substitutePlaceholders(Path root, String pkg, String appName) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                String ext = dot >= 0 ? fileName.substring(dot) : "";
                if (BINARY_EXTENSIONS.contains(ext)) {
                    return FileVisitResult.CONTINUE;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String replaced = content.replace("__pkg__", pkg).replace("__APP_NAME__", appName);
                if (!replaced.equals(content)) {
                    Files.writeString(file, replaced, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void registerInSettings(Path sugrRoot, String name) throws IOException {
        Path settings = sugrRoot.resolve("settings.gradle.kts");
        String content = Files.readString(settings);
        String includeLine = "include(\"examples:" + name + "\")";
        if (content.contains(includeLine)) {
            return;
        }
        String addition = "\n" + includeLine + "\n"
                + "project(\":examples:" + name + "\").projectDir = file(\"examples/" + name + "/lib\")\n";
        Files.writeString(settings, content + addition, StandardCharsets.UTF_8);
        System.out.println("[sugr] registered :examples:" + name + " in settings.gradle.kts");
    }
}
