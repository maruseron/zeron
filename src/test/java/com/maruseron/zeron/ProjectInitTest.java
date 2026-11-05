package com.maruseron.zeron;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ProjectInitTest {
    @Test
    public void createsBuildableProjectManifestAndEntry() throws Exception {
        final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-init-")
                .toAbsolutePath().normalize();
        final var previousHome = System.getProperty("zeron.home");
        System.clearProperty("zeron.home");
        try {
            assertEquals(0, Zeron.initializeProject(projectRoot));

            final var manifestPath = projectRoot.resolve("zeron.toml");
            final var sourcePath = projectRoot.resolve("src/main.zn");
            assertTrue(Files.isRegularFile(manifestPath));
            assertEquals("""
                    fn main() {
                    }
                    """, Files.readString(sourcePath));
            final var manifest = ProjectManifest.load(manifestPath);
            assertEquals(sourcePath, manifest.entry());
            assertEquals(java.util.List.of(projectRoot.resolve("src")), manifest.sourceRoots());
            assertEquals(projectRoot.resolve("dist"), manifest.outputDirectory());
            assertEquals(StandardLibraryMode.SOURCE, manifest.standardLibraryMode());
            assertEquals(0, Zeron.runCli("build", "--manifest", manifestPath.toString()));
            assertTrue(Files.isRegularFile(projectRoot.resolve("dist/main.class")));
        } finally {
            if (previousHome == null) System.clearProperty("zeron.home");
            else System.setProperty("zeron.home", previousHome);
            deleteTree(projectRoot);
        }
    }

    @Test
    public void initSelectsCompiledModeWhenTheDistributionBundlesIt() throws Exception {
        final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-init-compiled-")
                .toAbsolutePath().normalize();
        final var distribution = Files.createTempDirectory(Path.of("target"), "zeron-init-home-")
                .toAbsolutePath().normalize();
        final var libraryDirectory = distribution.resolve("lib");
        Files.createDirectories(libraryDirectory);
        Files.createFile(libraryDirectory.resolve("zeron-stdlib-index-v1-api-v1.jar"));
        final var previousHome = System.getProperty("zeron.home");
        try {
            System.setProperty("zeron.home", distribution.toString());
            assertEquals(0, Zeron.initializeProject(projectRoot));
            final var manifest = ProjectManifest.load(projectRoot.resolve("zeron.toml"));
            assertEquals(StandardLibraryMode.COMPILED, manifest.standardLibraryMode());
        } finally {
            if (previousHome == null) System.clearProperty("zeron.home");
            else System.setProperty("zeron.home", previousHome);
            deleteTree(projectRoot);
            deleteTree(distribution);
        }
    }

    @Test
    public void doesNotOverwriteEitherExistingProjectFile() throws Exception {
        for (final var existingPath : java.util.List.of("zeron.toml", "src/main.zn")) {
            final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-init-existing-")
                    .toAbsolutePath().normalize();
            try {
                final var existingFile = projectRoot.resolve(existingPath);
                Files.createDirectories(existingFile.getParent());
                Files.writeString(existingFile, "keep");

                assertThrows(IllegalArgumentException.class,
                        () -> Zeron.initializeProject(projectRoot));
                assertEquals("keep", Files.readString(existingFile));
                if (existingPath.equals("zeron.toml")) {
                    assertFalse(Files.exists(projectRoot.resolve("src/main.zn")));
                } else {
                    assertFalse(Files.exists(projectRoot.resolve("zeron.toml")));
                }
            } finally {
                deleteTree(projectRoot);
            }
        }
    }

    private static void deleteTree(final Path path) throws Exception {
        try (final var paths = Files.walk(path)) {
            for (final var file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
