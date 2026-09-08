package com.sugr.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link GradleProjectLocator#locate} maps "the directory the user ran `sugr dev` in" to
 * a Gradle task. All file-system walking + settings.gradle.kts parsing - runs anywhere.
 */
final class GradleProjectLocatorTest {

    @TempDir
    Path tmp;

    @Test
    void standaloneBuildRunsTheBareTaskFromItsOwnDir() throws IOException {
        Files.writeString(tmp.resolve("settings.gradle.kts"), "rootProject.name = \"app\"\n");

        GradleProjectLocator.Result r = GradleProjectLocator.locate(tmp, "run");
        assertEquals(tmp.toRealPath(), r.gradleDir().toRealPath());
        assertEquals("run", r.task());
    }

    @Test
    void alsoAcceptsAGroovySettingsFile() throws IOException {
        Files.writeString(tmp.resolve("settings.gradle"), "rootProject.name = 'app'\n");
        assertEquals("build", GradleProjectLocator.locate(tmp, "build").task());
    }

    @Test
    void moduleOfAMonorepoResolvesToItsGradlePath() throws IOException {
        Files.writeString(tmp.resolve("settings.gradle.kts"),
                "include(\"examples:sql-client\")\n");
        Path module = Files.createDirectories(tmp.resolve("examples/sql-client"));

        GradleProjectLocator.Result r = GradleProjectLocator.locate(module, "run");
        assertEquals(tmp.toRealPath(), r.gradleDir().toRealPath());
        assertEquals(":examples:sql-client:run", r.task());
    }

    @Test
    void resolvesFromAppRootWhenTheGradleProjectDirIsANestedSibling() throws IOException {
        // The layout our own examples use: `sugr dev` is run from examples/sql-client,
        // but the Gradle project is examples/sql-client/lib.
        Files.writeString(tmp.resolve("settings.gradle.kts"),
                "include(\"examples:sql-client:lib\")\n");
        Files.createDirectories(tmp.resolve("examples/sql-client/lib"));
        Path appRoot = tmp.resolve("examples/sql-client");

        GradleProjectLocator.Result r = GradleProjectLocator.locate(appRoot, "run");
        assertEquals(":examples:sql-client:lib:run", r.task());
    }

    @Test
    void honoursAProjectDirOverride() throws IOException {
        Files.writeString(tmp.resolve("settings.gradle.kts"), """
                include("app")
                project(":app").projectDir = file("modules/the-app")
                """);
        Path realDir = Files.createDirectories(tmp.resolve("modules/the-app"));

        GradleProjectLocator.Result r = GradleProjectLocator.locate(realDir, "run");
        assertEquals(":app:run", r.task());
    }

    @Test
    void exactMatchWinsOverAnAncestorMatch() throws IOException {
        Files.writeString(tmp.resolve("settings.gradle.kts"), """
                include("a")
                include("a:b")
                """);
        Path a = Files.createDirectories(tmp.resolve("a"));
        Files.createDirectories(tmp.resolve("a/b"));

        assertEquals(":a:run", GradleProjectLocator.locate(a, "run").task());
    }

    @Test
    void returnsNullWhenNoSettingsFileExistsAnywhereAbove() throws IOException {
        Path deep = Files.createDirectories(tmp.resolve("x/y/z"));
        assertNull(GradleProjectLocator.locate(deep, "run"));
    }

    @Test
    void returnsNullWhenTheDirIsNotADeclaredProjectOfAnAncestorBuild() throws IOException {
        Files.writeString(tmp.resolve("settings.gradle.kts"), "include(\"something-else\")\n");
        Path stray = Files.createDirectories(tmp.resolve("not-a-module"));
        assertNull(GradleProjectLocator.locate(stray, "run"));
    }
}
