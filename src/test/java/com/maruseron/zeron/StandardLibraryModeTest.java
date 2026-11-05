package com.maruseron.zeron;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class StandardLibraryModeTest {
    @Test
    public void discoversOneCompiledStandardLibraryBesideTheInstallation() throws Exception {
        final var home = Files.createTempDirectory(Path.of("target"), "zeron-home-");
        final var library = home.resolve("lib").resolve("zeron-stdlib-index-v9-api-v4.jar");
        final var previousHome = System.getProperty("zeron.home");
        try {
            Files.createDirectories(library.getParent());
            Files.createFile(library);
            System.setProperty("zeron.home", home.toString());
            assertEquals(library, Zeron.bundledStandardLibraryJar().orElseThrow());
        } finally {
            restoreHome(previousHome);
            deleteTree(home);
        }
    }

    @Test
    public void rejectsAmbiguousCompiledStandardLibraries() throws Exception {
        final var home = Files.createTempDirectory(Path.of("target"), "zeron-home-ambiguous-");
        final var libraryDirectory = home.resolve("lib");
        final var previousHome = System.getProperty("zeron.home");
        try {
            Files.createDirectories(libraryDirectory);
            Files.createFile(libraryDirectory.resolve("zeron-stdlib-index-v9-api-v4.jar"));
            Files.createFile(libraryDirectory.resolve("zeron-stdlib-index-v9-api-v5.jar"));
            System.setProperty("zeron.home", home.toString());
            final var error = assertThrows(IllegalArgumentException.class,
                    Zeron::bundledStandardLibraryJar);
            assertTrue(error.getMessage().contains("Multiple compiled standard libraries"));
        } finally {
            restoreHome(previousHome);
            deleteTree(home);
        }
    }

    @Test
    public void defaultsToBundledSourcesWithoutAnInstalledArtifact() throws Exception {
        final var previousHome = System.getProperty("zeron.home");
        System.clearProperty("zeron.home");
        try {
            final var setup = Zeron.resolveStandardLibrary(StandardLibraryMode.DEFAULT, List.of());
            assertTrue(setup.bundleStandardLibrarySources());
            assertTrue(setup.libraryRoots().isEmpty());
            assertTrue(setup.libraries().isEmpty());
        } finally {
            restoreHome(previousHome);
        }
    }

    @Test
    public void explicitCompiledModeRequiresAnArtifact() throws Exception {
        final var previousHome = System.getProperty("zeron.home");
        System.clearProperty("zeron.home");
        try {
            final var error = assertThrows(IllegalArgumentException.class,
                    () -> Zeron.resolveStandardLibrary(StandardLibraryMode.COMPILED, List.of()));
            assertTrue(error.getMessage().contains("Compiled standard-library mode"));
        } finally {
            restoreHome(previousHome);
        }
    }

    @Test
    public void explicitSourceModeDoesNotRequireAnArtifact() throws Exception {
        final var previousHome = System.getProperty("zeron.home");
        System.clearProperty("zeron.home");
        try {
            final var setup = Zeron.resolveStandardLibrary(StandardLibraryMode.SOURCE, List.of());
            assertTrue(setup.bundleStandardLibrarySources());
            assertTrue(setup.libraries().isEmpty());
        } finally {
            restoreHome(previousHome);
        }
    }

    private static void restoreHome(final String previousHome) {
        if (previousHome == null) System.clearProperty("zeron.home");
        else System.setProperty("zeron.home", previousHome);
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
