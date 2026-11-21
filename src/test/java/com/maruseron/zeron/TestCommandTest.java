package com.maruseron.zeron;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class TestCommandTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void discoversAnnotatedTestsAndRunsTheGeneratedRunner() throws Exception {
        final var root = temporaryFolder.getRoot().toPath();
        Files.createDirectories(root.resolve("src/app"));
        Files.createDirectories(root.resolve("tests/app"));
        Files.writeString(root.resolve("src/app/Main.zn"), """
                package app;

                public fn add(left: Int, right: Int): Int = left + right;
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("tests/app/MainTest.zn"), """
                package app;

                import zeron.test.Test;
                import zeron.test.Ignore;
                import zeron.test.TestFailure;
                import zeron.test.assertEquals;

                @Test
                public fn additionAddsOperands(): Unit performs TestFailure {
                    assertEquals(4, add(2, 2));
                }

                @Test
                @Ignore(reason = "not ready")
                public fn pendingWork(): Unit {
                }
                """, StandardCharsets.UTF_8);
        final var manifest = root.resolve("zeron.toml");
        Files.writeString(manifest, """
                [project]
                source-roots = ["src"]
                test-roots = ["tests"]
                output = "dist"

                [platform]
                stdlib = "source"

                [targets.app]
                entry = "src/app/Main.zn"

                [targets.unit]
                kind = "test"
                output = "dist/test"
                """, StandardCharsets.UTF_8);

        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        final var originalOut = System.out;
        final var originalErr = System.err;
        final int status;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            status = Zeron.runCli("test", "unit", "--manifest", manifest.toString());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        assertEquals(output.toString(StandardCharsets.UTF_8)
                + errors.toString(StandardCharsets.UTF_8), 0, status);
        assertTrue(Files.isDirectory(root.resolve("dist/test")));
    }
}
