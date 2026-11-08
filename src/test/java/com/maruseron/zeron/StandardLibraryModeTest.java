package com.maruseron.zeron;

import org.junit.Test;

import com.maruseron.zeron.domain.ZeronLibraryIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class StandardLibraryModeTest {
    @Test
    public void discoversOneCompiledStandardLibraryBesideTheInstallation() throws Exception {
        final var home = Files.createTempDirectory(Path.of("target"), "zeron-home-");
        final var bin = home.resolve("bin");
        final var executable = bin.resolve("zeron.cmd");
        final var library = home.resolve("lib/zeron-stdlib-index-v1-api-v1.jar");

        try {
            Files.createDirectories(bin);
            Files.createDirectories(library.getParent());
            Files.createFile(executable);
            Files.createFile(library);

            final var commandHome = Zeron.getCommandHome(bin.toString(), "zeron");

            assertEquals(home, commandHome.orElseThrow());
            assertEquals(library, Zeron.bundledStandardLibraryJar(commandHome.orElseThrow()).orElseThrow());
        } finally {
            deleteTree(home);
        }
    }

    @Test
    public void rejectsAmbiguousCompiledStandardLibraries() throws Exception {
        final var home = Files.createTempDirectory(Path.of("target"), "zeron-home-");
        final var libraryDirectory = home.resolve("lib");

        try {
            Files.createDirectories(libraryDirectory);
            Files.createFile(libraryDirectory.resolve("zeron-stdlib-index-v1-api-v1.jar"));
            Files.createFile(libraryDirectory.resolve("zeron-stdlib-index-v2-api-v2.jar"));

            final var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> Zeron.bundledStandardLibraryJar(home));

            assertTrue(error.getMessage().contains("Multiple compiled standard libraries"));
        } finally {
            deleteTree(home);
        }
    }

    @Test
    public void defaultsToBundledSourcesWhenInstalledLibraryIsIncompatible() throws Exception {
        final var home = Files.createTempDirectory(Path.of("target"), "zeron-home-");

        try {
            Files.createDirectories(home.resolve("bin"));
            Files.createDirectories(home.resolve("lib"));
            Files.createFile(home.resolve("lib/zeron-stdlib-index-v1-api-v1.jar"));

            final var setup = Zeron.resolveStandardLibrary(StandardLibraryMode.DEFAULT, List.of(), home);

            assertTrue(setup.bundleStandardLibrarySources());
            assertTrue(setup.libraryRoots().isEmpty());
            assertTrue(setup.libraries().isEmpty());
        } finally {
            deleteTree(home);
        }
    }

    @Test
    public void defaultsToCompiledLibraryWhenCompatible() throws Exception {
        final var home = Files.createTempDirectory(Path.of("target"), "zeron-home-");

        try {
            Files.createDirectories(home.resolve("bin"));
            Files.createDirectories(home.resolve("lib"));
            final var library = home.resolve("lib/zeron-stdlib-index-v" + StandardLibrary.API_VERSION
                    + "-api-v" + StandardLibrary.API_VERSION + ".jar");
            writeEmptyLibraryJar(library);

            final var setup = Zeron.resolveStandardLibrary(StandardLibraryMode.DEFAULT, List.of(), home);

            assertFalse(setup.bundleStandardLibrarySources());
            assertEquals(1, setup.libraryRoots().size());
            assertEquals(1, setup.libraries().size());
            assertTrue(setup.libraries().getFirst() instanceof ZeronLibraryIndex);
        } finally {
            deleteTree(home);
        }
    }

    @Test
    public void explicitCompiledModeRequiresAnArtifact() throws Exception {
        final var home = Files.createTempDirectory(Path.of("target"), "zeron-home-");

        try {
            Files.createDirectories(home.resolve("bin"));
            Files.createDirectories(home.resolve("lib"));

            final var error = assertThrows(IllegalArgumentException.class,
                    () -> Zeron.resolveStandardLibrary(
                            StandardLibraryMode.COMPILED,
                            List.of(),
                            home));

            assertTrue(error.getMessage().contains("Compiled standard-library mode"));
        } finally {
            deleteTree(home);
        }
    }

    @Test
    public void explicitSourceModeDoesNotRequireAnArtifact() throws Exception {
        final var setup = Zeron.resolveStandardLibrary(StandardLibraryMode.SOURCE, List.of());
        assertTrue(setup.bundleStandardLibrarySources());
        assertTrue(setup.libraries().isEmpty());
    }

    private static void writeEmptyLibraryJar(final Path jarPath) throws IOException {
        final var index = Files.createTempFile(Path.of("target"), "zeron-mode-index-", ".bin");
        try {
            new ZeronLibraryIndex(StandardLibrary.API_VERSION, List.of()).writeTo(index);
            try (final var output = new JarOutputStream(Files.newOutputStream(jarPath))) {
                output.putNextEntry(new JarEntry(ZeronVersions.API_INDEX_JAR_ENTRY));
                Files.copy(index, output);
                output.closeEntry();
            }
        } finally {
            Files.deleteIfExists(index);
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
