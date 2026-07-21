package com.sugr.cli;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.function.UnaryOperator;

/**
 * Recursive copy/delete helpers shared by {@link BuildCommand} (plain copy,
 * embedding built frontend assets) and {@link InitCommand} (copy while
 * renaming "__pkg__" path segments to the real package name).
 */
final class FileTrees {

    private FileTrees() {
    }

    /** Copies source's tree into target, applying pathTransform to each relative path (use {@code s -> s} for a plain copy). */
    static void copyRecursively(Path source, Path target, UnaryOperator<String> pathTransform) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(transformed(dir));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, transformed(file), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }

            private Path transformed(Path path) {
                String relative = pathTransform.apply(source.relativize(path).toString());
                return target.resolve(relative);
            }
        });
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
