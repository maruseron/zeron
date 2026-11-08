package com.maruseron.zeron;

import java.nio.file.Path;

/**
 * Resolves paths in the source tree independently of the per-fork test working directory.
 *
 * <p>Surefire runs test classes in separate forks, each with its own working directory so that
 * relative output (for example {@code dist}) is isolated. Tests that intentionally read files from
 * the repository must therefore resolve them against the project root instead of the current
 * directory.</p>
 */
public final class TestPaths {
    private static final Path PROJECT_ROOT = Path.of(
            System.getProperty("zeron.test.project.root",
                    System.getProperty("basedir", "."))).toAbsolutePath().normalize();

    private TestPaths() {
    }

    public static Path projectRoot() {
        return PROJECT_ROOT;
    }

    public static Path projectFile(final String first, final String... more) {
        return PROJECT_ROOT.resolve(Path.of(first, more));
    }
}
