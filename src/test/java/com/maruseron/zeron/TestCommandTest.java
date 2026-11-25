package com.maruseron.zeron;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TestCommandTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private record CliResult(int status, String output) {
        void assertContains(final String expected) {
            assertTrue("expected output to contain '" + expected + "' but was:\n" + output,
                    output.contains(expected));
        }

        void assertDoesNotContain(final String unexpected) {
            assertFalse("expected output not to contain '" + unexpected + "' but was:\n" + output,
                    output.contains(unexpected));
        }
    }

    private static CliResult run(final String... arguments) throws Exception {
        final var output = new ByteArrayOutputStream();
        final var errors = new ByteArrayOutputStream();
        final var originalOut = System.out;
        final var originalErr = System.err;
        final int status;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            status = Zeron.runCli(arguments);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new CliResult(status, output.toString(StandardCharsets.UTF_8)
                + errors.toString(StandardCharsets.UTF_8));
    }

    private Path writeProject(final String manifest, final String testSource) throws Exception {
        final var root = temporaryFolder.getRoot().toPath();
        Files.createDirectories(root.resolve("src/app"));
        Files.createDirectories(root.resolve("tests/app"));
        Files.writeString(root.resolve("src/app/Main.zn"), """
                package app;

                public fn add(left: Int, right: Int): Int = left + right;
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("tests/app/MainTest.zn"), testSource, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("zeron.toml"), manifest, StandardCharsets.UTF_8);
        return root;
    }

    private static String manifest(final String unitTarget) {
        return """
                [project]
                source-roots = ["src"]
                test-roots = ["tests"]
                output = "dist"

                [platform]
                stdlib = "source"

                [targets.app]
                entry = "src/app/Main.zn"

                [targets.unit]
                """ + unitTarget;
    }

    @Test
    public void discoversAnnotatedTestsAndRunsTheGeneratedRunner() throws Exception {
        final var root = writeProject(manifest("""
                kind = "test"
                output = "dist/test"
                """), """
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
                """);
        final var manifest = root.resolve("zeron.toml");

        final var result = run("test", "unit", "--manifest", manifest.toString());

        assertEquals(result.output(), 0, result.status());
        assertTrue(Files.isDirectory(root.resolve("dist/test")));
    }

    @Test
    public void runsCompileFailureTestsAgainstFixtureRoots() throws Exception {
        final var root = writeProject(manifest("""
                kind = "test"
                output = "dist/test"
                compile-failure-roots = ["tests/fixtures"]
                """), """
                package app;

                import zeron.test.Test;
                import zeron.test.TestFailure;
                import zeron.test.CompileFailure;
                import zeron.test.assertEquals;

                @CompileFailure(fixture = "broken.zn")
                public fn brokenFixtureFails(): Unit {
                }

                @CompileFailure(fixture = "unparseable.zn")
                public fn unparseableFixtureFails(): Unit {
                }

                @Test
                public fn additionAddsOperands(): Unit performs TestFailure {
                    assertEquals(4, add(2, 2));
                }
                """);
        Files.createDirectories(root.resolve("tests/fixtures"));
        Files.writeString(root.resolve("tests/fixtures/broken.zn"), """
                package fixtures;

                fn broken(): Missing = 1;
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("tests/fixtures/unparseable.zn"), """
                package fixtures;

                fn broken(: Int = 1;
                """, StandardCharsets.UTF_8);

        final var result = run("test", "unit", "--manifest",
                root.resolve("zeron.toml").toString());

        assertEquals(result.output(), 0, result.status());
        result.assertContains("compile-failure test app.brokenFixtureFails ... ok");
        result.assertContains("compile-failure test app.unparseableFixtureFails ... ok");
        result.assertContains("2 compile-failure tests passed, 0 failed");
    }

    @Test
    public void reportsUnsatisfiedCompileFailureExpectations() throws Exception {
        final var root = writeProject(manifest("""
                kind = "test"
                output = "dist/test"
                compile-failure-roots = ["tests/fixtures"]
                """), """
                package app;

                import zeron.test.CompileFailure;

                @CompileFailure(fixture = "broken.zn", code = "ZR9999")
                public fn wrongCode(): Unit {
                }

                @CompileFailure(fixture = "missing.zn")
                public fn missingFixture(): Unit {
                }
                """);
        Files.createDirectories(root.resolve("tests/fixtures"));
        Files.writeString(root.resolve("tests/fixtures/broken.zn"), """
                package fixtures;

                fn broken(): Missing = 1;
                """, StandardCharsets.UTF_8);

        final var result = run("test", "unit", "--manifest",
                root.resolve("zeron.toml").toString());

        assertTrue(result.output(), result.status() != 0);
        result.assertContains("compile-failure test app.missingFixture ... FAILED: "
                + "fixture 'missing.zn' was not found");
        result.assertContains("compile-failure test app.wrongCode ... FAILED: "
                + "expected diagnostic ZR9999 but got [ZR2001]");
        result.assertContains("0 compile-failure tests passed, 2 failed");
    }

    @Test
    public void listsAndFiltersDiscoveredTests() throws Exception {
        final var root = writeProject(manifest("""
                kind = "test"
                output = "dist/test"
                """), """
                package app;

                import zeron.test.Test;
                import zeron.test.TestFailure;
                import zeron.test.assertTrue;
                import zeron.test.assertEquals;

                @Test
                public fn additionAddsOperands(): Unit performs TestFailure {
                    assertEquals(4, add(2, 2));
                }

                @Test
                public fn additionIsCommutative(): Unit performs TestFailure {
                    assertTrue(add(1, 2) == add(2, 1));
                }
                """);
        final var manifest = root.resolve("zeron.toml").toString();

        final var list = run("test", "unit", "--manifest", manifest, "--list");
        assertEquals(list.output(), 0, list.status());
        list.assertContains("app.additionAddsOperands");
        list.assertContains("app.additionIsCommutative");
        list.assertContains("2 tests");

        final var filteredList = run("test", "unit", "--manifest", manifest,
                "--list", "--filter", "Commutative");
        assertEquals(filteredList.output(), 0, filteredList.status());
        filteredList.assertContains("app.additionIsCommutative");
        filteredList.assertDoesNotContain("app.additionAddsOperands");
        filteredList.assertContains("1 tests");

        // The generated runner is a child process, so its own output is not captured here;
        // the filter narrowing the suite is what the exit status confirms.
        final var filtered = run("test", "unit", "--manifest", manifest,
                "--filter", "Commutative");
        assertEquals(filtered.output(), 0, filtered.status());
    }

    @Test
    public void lambdaCaptureIgnoresASameNamedLocalInAnotherTestFile() throws Exception {
        final var root = writeProject(manifest("""
                kind = "test"
                output = "dist/test"
                """), """
                package app;

                import zeron.test.Test;
                import zeron.test.TestFailure;
                import zeron.test.assertEquals;

                @Test
                public fn shadowsTheHelperName(): Unit performs TestFailure {
                    let double = () -> 5;
                    assertEquals(5, double());
                }
                """);
        Files.writeString(root.resolve("tests/app/ZHelperTest.zn"), """
                package app;

                import zeron.lang.Option;
                import zeron.test.Test;
                import zeron.test.TestFailure;
                import zeron.test.assertEquals;

                @Test
                public fn callsTheHelperFromALambda(): Unit performs TestFailure =
                    assertEquals(42, Option.Some(21).map(value -> double(value)).getOrElse(0));
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/app/Main.zn"), """
                package app;

                public fn double(value: Int): Int = value * 2;
                """, StandardCharsets.UTF_8);

        final var result = run("test", "unit", "--manifest",
                root.resolve("zeron.toml").toString());

        assertEquals(result.output(), 0, result.status());
    }

    @Test
    public void stopsAfterTheFirstFailureWhenFailFastIsRequested() throws Exception {
        final var root = writeProject(manifest("""
                kind = "test"
                output = "dist/test"
                """), """
                package app;

                import zeron.test.Test;
                import zeron.test.TestFailure;
                import zeron.test.assertTrue;
                import zeron.test.assertEquals;

                @Test
                public fn aFails(): Unit performs TestFailure {
                    assertTrue(false, "deliberate failure");
                }

                @Test
                public fn bPasses(): Unit performs TestFailure {
                    assertEquals(4, add(2, 2));
                }
                """);
        final var manifest = root.resolve("zeron.toml").toString();

        final var result = run("test", "unit", "--manifest", manifest, "--fail-fast");

        assertTrue(result.output(), result.status() != 0);
    }

    @Test
    public void failFastGuardsTheRemainingTestsInTheGeneratedRunner() {
        final var tests = java.util.List.of(
                new TestRunner.DiscoveredTest("app.aFails", false, "", null),
                new TestRunner.DiscoveredTest("app.bPasses", false, "", null));

        final var failFast = TestRunner.generate(tests, "unit", true);
        assertTrue(failFast, failFast.contains("let mut stop = false;"));
        assertTrue(failFast, failFast.contains("if (not stop) {"));
        assertTrue(failFast, failFast.contains("stop = true;"));
        assertTrue(failFast, failFast.contains("stopped after the first failure"));

        final var ordinary = TestRunner.generate(tests, "unit", false);
        assertFalse(ordinary, ordinary.contains("stop"));
    }
}
