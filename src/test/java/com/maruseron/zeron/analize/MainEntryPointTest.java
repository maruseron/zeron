package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class MainEntryPointTest {
    @Test
    public void infersUnitEntryReturnFromTheBody() throws Exception {
        final var compiled = compile("""
                package %s;
                fn main() = println("inferred");
                """, "inferredunit");
        try (final var loader = loader(compiled.output())) {
            final var capture = captureOut(() -> loader.loadClass(compiled.packageName() + ".Main")
                    .getMethod("main", String[].class).invoke(null, (Object) new String[0]));
            assertEquals("inferred", capture.strip());
        }
    }

    @Test
    public void infersIntEntryReturnFromTheBody() throws Exception {
        final var compiled = compile("""
                package %s;
                fn main() = 0;
                """, "inferredint");
        try (final var loader = loader(compiled.output())) {
            loader.loadClass(compiled.packageName() + ".Main")
                    .getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        }
    }

    @Test
    public void unitMainUsesTheGeneratedLauncher() throws Exception {
        final var compiled = compile("""
                package %s;
                fn main(): Unit performs IoFailure = println("hello");
                """, "unit");
        try (final var loader = loader(compiled.output())) {
            final var capture = captureOut(() -> loader.loadClass(compiled.packageName() + ".Main")
                    .getMethod("main", String[].class).invoke(null, (Object) new String[0]));
            assertEquals("hello", capture.strip());
        }
    }

    @Test
    public void argumentMainReceivesTheHostArguments() throws Exception {
        final var compiled = compile("""
                package %s;
                fn main(args: Array<String>): Unit performs IoFailure = println(args.length);
                """, "args");
        try (final var loader = loader(compiled.output())) {
            final var capture = captureOut(() -> loader.loadClass(compiled.packageName() + ".Main")
                    .getMethod("main", String[].class).invoke(null, (Object) new String[]{"a", "b"}));
            assertEquals("2", capture.strip());
        }
    }

    @Test
    public void intEntryZeroEndsNormally() throws Exception {
        final var compiled = compile("""
                package %s;
                fn main(): Int = 0;
                """, "int");
        try (final var loader = loader(compiled.output())) {
            loader.loadClass(compiled.packageName() + ".Main")
                    .getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        }
    }

    @Test
    public void userWitnessInterpretsTheEntryValue() throws Exception {
        final var compiled = compile("""
                package %s;
                public class Job {
                    public constructor new;
                    public start(): Unit performs IoFailure = println("started");
                }
                fn main(): Job = Job.new();
                public witness JobMain: Main<Job> for Job {
                    static fn run(value: Job): Unit performs IoFailure = value.start();
                }
                """, "user");
        try (final var loader = loader(compiled.output())) {
            final var capture = captureOut(() -> loader.loadClass(compiled.packageName() + ".Main")
                    .getMethod("main", String[].class).invoke(null, (Object) new String[0]));
            assertEquals("started", capture.strip());
        }
    }

    @Test
    public void resultEntryOkEndsNormally() throws Exception {
        final var compiled = compile("""
                package %s;
                fn main(): Result<Unit, String> performs IoFailure =
                    Result<Unit, String>.Ok(println("ok"));
                """, "result");
        try (final var loader = loader(compiled.output())) {
            loader.loadClass(compiled.packageName() + ".Main")
                    .getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        }
    }

    @Test
    public void missingEntryEvidenceIsDiagnosed() throws Exception {
        final var pkg = packageName("missing");
        final var unit = parse("""
                package %s;
                fn main(): String = "plain";
                """.formatted(pkg), "Main.zn");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-main-missing-");
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    pkg + ".Main", pkg, List.of(), true, List.of(), output);
            compiler.resolve();
            fail("Expected missing Main evidence to be diagnosed");
        } catch (final ResolutionError error) {
            assertTrue(error.getMessage().contains("Main"));
        } finally {
            deleteTree(output);
        }
    }

    private static Compiled compile(final String source, final String name) throws Exception {
        final var pkg = packageName(name);
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-main-" + name + "-");
        final var compiler = CompilationService.forCompilationUnits(
                List.of(parse(source.formatted(pkg), "Main.zn")),
                pkg + ".Main", pkg, List.of(), true, List.of(), output);
        compiler.resolve();
        compiler.compile();
        return new Compiled(output, pkg);
    }

    private record Compiled(Path output, String packageName) {}

    private static URLClassLoader loader(final Path output) throws Exception {
        return new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                MainEntryPointTest.class.getClassLoader());
    }

    private static String captureOut(final ThrowingRunnable action) throws Exception {
        final var previous = System.out;
        final var buffer = new ByteArrayOutputStream();
        try (final var stream = new PrintStream(buffer)) {
            System.setOut(stream);
            action.run();
        } finally {
            System.setOut(previous);
        }
        return buffer.toString();
    }

    private static String packageName(final String name) {
        return "mainEntry" + name + UUID.randomUUID().toString().replace("-", "");
    }

    private static CompilationUnit parse(final String source, final String name) {
        return Parser.of(Scanner.from(source, name).scanTokens()).parseCompilationUnit(name);
    }

    private static void deleteTree(final Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (final var paths = Files.walk(root)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
