package com.maruseron.zeron;

import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ProjectManifestTest {
    @Test
    public void buildCommandCompilesUsingManifestRelativePaths() throws Exception {
        final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-manifest-")
                .toAbsolutePath().normalize();
        final var sourceRoot = projectRoot.resolve("src");
        final var entry = sourceRoot.resolve("app").resolve("Main.zn");
        final var output = projectRoot.resolve("build-output");
        final var manifest = projectRoot.resolve("zeron.toml");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package app;
                fn answer(): Int = 42;
                """);
        Files.writeString(manifest, """
                [project]
                entry = "src/app/Main.zn"
                source-roots = ["src"]
                output = "build-output"

                [platform]
                stdlib = "source"
                """);
        Files.createDirectories(output.resolve("app"));
        Files.writeString(output.resolve("app").resolve("Stale.class"), "stale");
        Files.writeString(output.resolve("obsolete.txt"), "stale");

        try {
            assertEquals(0, Zeron.runCli("build", "--manifest", manifest.toString()));
            final var generatedClass = output.resolve("app").resolve("Main.class");
            assertTrue(Files.exists(generatedClass));
            assertFalse(Files.exists(output.resolve("app").resolve("Stale.class")));
            assertFalse(Files.exists(output.resolve("obsolete.txt")));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass("app.Main").getMethod("answer").invoke(null));
            }
            assertFalse(Files.exists(projectRoot.resolve("dist")));
        } finally {
            deleteTree(projectRoot);
        }
    }

    @Test
    public void refusesToCleanAProjectDirectoryAsAnOutputDirectory() throws Exception {
        final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-output-guard-")
                .toAbsolutePath().normalize();
        final var manifest = projectRoot.resolve("zeron.toml");
        try {
            Files.writeString(manifest, """
                    [project]
                    entry = "src/Main.zn"
                    source-roots = ["src"]
                    output = "."
                    """);
            final var error = assertThrows(IllegalArgumentException.class,
                    () -> Zeron.cleanOutputDirectory(projectRoot, List.of()));
            assertTrue(error.getMessage().contains("project or repository"));
            assertTrue(Files.exists(manifest));
        } finally {
            deleteTree(projectRoot);
        }
    }

    @Test
    public void manifestResolvesDependencyPathsRelativeToItsDirectory() throws Exception {
        final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-manifest-paths-")
                .toAbsolutePath().normalize();
        final var manifest = projectRoot.resolve("zeron.toml");
        try {
            Files.writeString(manifest, """
                    [project]
                    entry = "src/Main.zn"
                    source-roots = ["src"]

                    [dependencies]
                    zeron-libraries = ["lib/shared.jar"]
                    java-classpath = ["host/classes", "../external/host.jar"]
                    """);

            final var configuration = ProjectManifest.load(manifest);
            assertEquals(List.of(projectRoot.resolve("src")), configuration.sourceRoots());
            assertEquals(projectRoot.resolve("src/Main.zn"), configuration.entry());
            assertEquals(projectRoot.resolve("dist"), configuration.outputDirectory());
            assertEquals(List.of(projectRoot.resolve("lib/shared.jar")), configuration.libraryRoots());
            assertEquals(List.of(projectRoot.resolve("host/classes"),
                            projectRoot.resolve("../external/host.jar").normalize()),
                    configuration.javaClassPathRoots());
            assertEquals(StandardLibraryMode.DEFAULT, configuration.standardLibraryMode());
        } finally {
            deleteTree(projectRoot);
        }
    }

    @Test
    public void parsesExplicitStandardLibraryModes() throws Exception {
        final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-manifest-modes-")
                .toAbsolutePath().normalize();
        final var manifest = projectRoot.resolve("zeron.toml");
        final var header = """
                [project]
                entry = "src/Main.zn"
                source-roots = ["src"]

                [platform]
                stdlib = "%s"
                """;
        try {
            Files.writeString(manifest, header.formatted("source"));
            assertEquals(StandardLibraryMode.SOURCE,
                    ProjectManifest.load(manifest).standardLibraryMode());

            Files.writeString(manifest, header.formatted("compiled"));
            assertEquals(StandardLibraryMode.COMPILED,
                    ProjectManifest.load(manifest).standardLibraryMode());
        } finally {
            deleteTree(projectRoot);
        }
    }

    @Test
    public void manifestRejectsUnknownKeysAndInvalidStandardLibraryMode() throws Exception {
        final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-manifest-invalid-")
                .toAbsolutePath().normalize();
        final var manifest = projectRoot.resolve("zeron.toml");
        try {
            Files.writeString(manifest, """
                    [project]
                    entry = "src/Main.zn"
                    source-roots = ["src"]
                    typo = true
                    """);
            final var unknownKey = assertThrows(IllegalArgumentException.class,
                    () -> ProjectManifest.load(manifest));
            assertTrue(unknownKey.getMessage().contains("Unknown key"));

            Files.writeString(manifest, """
                    [project]
                    entry = "src/Main.zn"
                    source-roots = ["src"]

                    [platform]
                    stdlib = "automatic"
                    """);
            final var invalidMode = assertThrows(IllegalArgumentException.class,
                    () -> ProjectManifest.load(manifest));
            assertTrue(invalidMode.getMessage().contains("stdlib"));

            Files.writeString(manifest, """
                    [project]
                    entry = "src/Main.zn"
                    source-roots = ["src", 1]
                    """);
            final var invalidArray = assertThrows(IllegalArgumentException.class,
                    () -> ProjectManifest.load(manifest));
            assertTrue(invalidArray.getMessage().contains("source-roots"));
        } finally {
            deleteTree(projectRoot);
        }
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
