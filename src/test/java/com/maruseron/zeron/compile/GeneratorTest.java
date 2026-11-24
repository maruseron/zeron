package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;
import zeron.runtime.ContinuationProviderLoader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/// End-to-end pull generators: `generator` returns a replayable source, each cursor pulls lazily,
/// and abandoning a cursor runs the body's `using` cleanup.
public final class GeneratorTest {
    @Test
    public void generatorBodiesSuspendBetweenPullsAndCleanUp() throws Exception {
        final var packageName = "generator" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-generator-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.collections.generator;
                import zeron.collections.Step;
                import zeron.collections.Stream;
                import zeron.lang.Yield;
                import zeron.resource.Disposable;
                import zeron.resource.DisposeFailure;

                class Tracker is Disposable {
                    state: &Array<Int>;
                    public constructor new;
                    public mut dispose(): Unit performs DisposeFailure {
                        this.state[0] = this.state[0] + 1;
                    }
                }

                public fn sum(): Int {
                    let body: () -> Unit performs Yield<Int> = () -> {
                        perform Yield<Int>(1);
                        perform Yield<Int>(2);
                        perform Yield<Int>(3);
                    };
                    let source = generator<Int>(body);
                    let mut total = 0;
                    for (let number in source) total = total + number;
                    return total;
                }

                public fn manual(): Int {
                    let body: () -> Unit performs Yield<Int> = () -> {
                        perform Yield<Int>(4);
                        perform Yield<Int>(5);
                    };
                    let mut cursor = generator<Int>(body).iterator();
                    let mut total = 0;
                    let mut finished = false;
                    until (finished) {
                        let step = cursor.next();
                        if (step matches Step.Yield(value)) {
                            total = total + value;
                        } else {
                            finished = true;
                        }
                    }
                    return if (cursor.isDone()) then total else -1;
                }

                public fn trailingLambda(): Int {
                    let mut total = 0;
                    for (let value in generator<Int> {
                        perform Yield<Int>(1);
                        perform Yield<Int>(2);
                    }) total = total + value;
                    return total;
                }

                public fn replayable(): Int {
                    let body: () -> Unit performs Yield<Int> = () -> {
                        perform Yield<Int>(1);
                        perform Yield<Int>(2);
                    };
                    let source = generator<Int>(body);
                    let mut total = 0;
                    for (let number in source) total = total + number;
                    for (let number in source) total = total + number;
                    return total;
                }

                public fn cleanupOnCancel(): Int performs DisposeFailure {
                    let state: &Array<Int> = [0];
                    let body: () -> Unit performs Yield<Int>, DisposeFailure = () -> {
                        using (let tracker = Tracker.new(state)) {
                            perform Yield<Int>(1);
                            perform Yield<Int>(2);
                        }
                    };
                    let mut cursor = generator<Int>(body).iterator();
                    let first = cursor.next();
                    let actual = match (first) {
                        case Step.Yield(item) -> item;
                        case Step.Done() -> -1;
                    };
                    if (actual != 1) return -1;
                    cursor.cancel();
                    return state[0];
                }

                public fn breakCloses(): Int performs DisposeFailure {
                    let state: &Array<Int> = [0];
                    let body: () -> Unit performs Yield<Int>, DisposeFailure = () -> {
                        using (let tracker = Tracker.new(state)) {
                            perform Yield<Int>(1);
                            perform Yield<Int>(2);
                        }
                    };
                    for (let value in generator<Int>(body)) {
                        break;
                    }
                    return state[0];
                }

                public fn exhaustionCloses(): Int performs DisposeFailure {
                    let state: &Array<Int> = [0];
                    let body: () -> Unit performs Yield<Int>, DisposeFailure = () -> {
                        using (let tracker = Tracker.new(state)) {
                            perform Yield<Int>(1);
                        }
                    };
                    let mut total = 0;
                    for (let value in generator<Int>(body)) total = total + value;
                    return total * 10 + state[0];
                }

                public fn streamCloses(): Int performs DisposeFailure {
                    let state: &Array<Int> = [0];
                    let body: () -> Unit performs Yield<Int>, DisposeFailure = () -> {
                        using (let tracker = Tracker.new(state)) {
                            perform Yield<Int>(1);
                            perform Yield<Int>(2);
                        }
                    };
                    let stream = Stream<Int>.from(generator<Int>(body)).map(value -> value * 2);
                    let mut total = 0;
                    for (let value in stream) total = total + value;
                    return total * 10 + state[0];
                }

                public fn cancelBeforeStart(): Int performs DisposeFailure {
                    let state: &Array<Int> = [0];
                    let body: () -> Unit performs Yield<Int>, DisposeFailure = () -> {
                        using (let tracker = Tracker.new(state)) {
                            perform Yield<Int>(1);
                        }
                    };
                    let mut cursor = generator<Int>(body).iterator();
                    cursor.cancel();
                    return if (cursor.isDone()) then state[0] else -1;
                }

                fn returnInside(state: &Array<Int>): Int {
                    let body: () -> Unit performs Yield<Int>, DisposeFailure = () -> {
                        using (let tracker = Tracker.new(state)) {
                            perform Yield<Int>(1);
                            perform Yield<Int>(2);
                        }
                    };
                    for (let value in generator<Int>(body)) {
                        return 99;
                    }
                    return -1;
                }

                public fn returnCloses(): Int {
                    let state: &Array<Int> = [0];
                    if (returnInside(state) != 99) return -1;
                    return state[0];
                }

                public fn continueKeepsCursorOpen(): Int performs DisposeFailure {
                    let state: &Array<Int> = [0];
                    let body: () -> Unit performs Yield<Int>, DisposeFailure = () -> {
                        using (let tracker = Tracker.new(state)) {
                            perform Yield<Int>(1);
                            perform Yield<Int>(2);
                        }
                    };
                    let mut total = 0;
                    for (let value in generator<Int>(body)) {
                        for (let inner in 1..2) {
                            if (inner == 1) continue;
                        }
                        total = total + value;
                    }
                    return total * 10 + state[0];
                }

                fn effectEscapesFromLoop(state: &Array<Int>): Int performs DisposeFailure {
                    let body: () -> Unit performs Yield<Int>, DisposeFailure = () -> {
                        using (let tracker = Tracker.new(state)) {
                            perform Yield<Int>(1);
                        }
                    };
                    for (let value in generator<Int>(body)) {
                        perform DisposeFailure.new("boom");
                    }
                    return -1;
                }

                public fn exceptionalExitCloses(): Int {
                    let state: &Array<Int> = [0];
                    let closed: Int = handle (effectEscapesFromLoop(state)) with {
                        case DisposeFailure -> state[0];
                    };
                    return closed;
                }

                public fn streamBreakCloses(): Int performs DisposeFailure {
                    let state: &Array<Int> = [0];
                    let body: () -> Unit performs Yield<Int>, DisposeFailure = () -> {
                        using (let tracker = Tracker.new(state)) {
                            perform Yield<Int>(1);
                            perform Yield<Int>(2);
                        }
                    };
                    let stream = Stream<Int>.from(generator<Int>(body)).map(value -> value * 2);
                    for (let value in stream) {
                        break;
                    }
                    return state[0];
                }

                public fn lazyStart(): Int {
                    let state: &Array<Int> = [0];
                    let body: () -> Unit performs Yield<Int> = () -> {
                        state[0] = state[0] + 1;
                        perform Yield<Int>(1);
                    };
                    let mut cursor = generator<Int>(body).iterator();
                    let before = state[0];
                    cursor.next();
                    return if (before == 0 and state[0] == 1) then 1 else 0;
                }

                public fn exhaustedCursorStaysDone(): Int {
                    let body: () -> Unit performs Yield<Int>, DisposeFailure = () -> {
                        perform Yield<Int>(1);
                    };
                    let mut cursor = generator<Int>(body).iterator();
                    cursor.next();
                    let second = cursor.next();
                    return if (second matches Step.Done() and cursor.isDone()) then 1 else 0;
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            final var resolution = compiler.resolveWithDiagnostics();
            assertTrue(resolution.errors().toString(), resolution.errors().isEmpty());
            final var compilation = compiler.compileWithDiagnostics();
            assertTrue(compilation.diagnostics().toString(), compilation.successful());

            final var result = runProgram(output, packageName + ".Main");
            assertEquals(result.output, 0, result.exitCode);
            assertTrue(result.output, result.output.contains("sum=6"));
            assertTrue(result.output, result.output.contains("manual=9"));
            assertTrue(result.output, result.output.contains("replayable=6"));
            assertTrue(result.output, result.output.contains("trailingLambda=3"));
            assertTrue(result.output, result.output.contains("cleanupOnCancel=1"));
            assertTrue(result.output, result.output.contains("cancelBeforeStart=0"));
            assertTrue(result.output, result.output.contains("breakCloses=1"));
            assertTrue(result.output, result.output.contains("exhaustionCloses=11"));
            assertTrue(result.output, result.output.contains("streamCloses=61"));
            assertTrue(result.output, result.output.contains("returnCloses=1"));
            assertTrue(result.output, result.output.contains("continueKeepsCursorOpen=31"));
            assertTrue(result.output, result.output.contains("exceptionalExitCloses=1"));
            assertTrue(result.output, result.output.contains("streamBreakCloses=1"));
            assertTrue(result.output, result.output.contains("exhaustedCursorStaysDone=1"));
            assertTrue(result.output, result.output.contains("lazyStart=1"));
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void generatorCursorIsNotIterable() throws Exception {
        final var packageName = "generator" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-generator-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.collections.generator;
                import zeron.collections.Step;
                import zeron.lang.Yield;

                public fn bad(): Int {
                    let body: () -> Unit performs Yield<Int> = () -> {
                        perform Yield<Int>(1);
                    };
                    let mut cursor = generator<Int>(body).iterator();
                    let mut total = 0;
                    for (let value in cursor) total = total + value;
                    return total;
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            final var resolution = compiler.resolveWithDiagnostics();
            assertTrue(resolution.errors().toString(),
                    resolution.errors().stream().anyMatch(error -> error.message().contains("Iterable")));
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void generatorRejectsUnhandledEffects() throws Exception {
        final var packageName = "generator" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-generator-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.collections.generator;
                import zeron.lang.Yield;

                public effect Other { public property amount: Int; }

                public fn bad(): Int {
                    let body: () -> Unit performs Yield<Int>, Other = () -> {
                        perform Yield<Int>(1);
                        perform Other.new(2);
                    };
                    let source = generator<Int>(body);
                    return 0;
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            final var resolution = compiler.resolveWithDiagnostics();
            assertTrue(resolution.errors().toString(),
                    resolution.errors().stream().anyMatch(error ->
                            error.message().contains("performs") || error.message().contains("DisposeFailure")));
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void generatorRejectsMutableCaptures() throws Exception {
        final var packageName = "generator" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-generator-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.collections.generator;
                import zeron.lang.Yield;

                public fn bad(): Int {
                    let mut count = 0;
                    let body: () -> Unit performs Yield<Int> = () -> {
                        perform Yield<Int>(count);
                    };
                    let source = generator<Int>(body);
                    return count;
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            final var resolution = compiler.resolveWithDiagnostics();
            assertTrue(resolution.errors().toString(),
                    resolution.errors().stream().anyMatch(error ->
                            error.message().contains("Cannot capture mutable binding")));
        } finally {
            deleteTree(output);
        }
    }

    private record ProgramResult(int exitCode, String output) {}

    private static ProgramResult runProgram(final Path output, final String mainClass)
            throws Exception {
        assertTrue("generators require the continuation provider export",
                ContinuationProviderLoader.loomPackagePresent());
        final var java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        final var command = new ArrayList<String>();
        command.add(java.toString());
        command.addAll(ContinuationProviderLoader.launcherArguments());
        command.add("-D" + ContinuationProviderLoader.BACKEND_PROPERTY + "=a");
        command.add("-cp");
        command.add(System.getProperty("java.class.path") + File.pathSeparator + output);
        command.add(Program.class.getName());
        command.add(mainClass);

        final var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final var completed = process.waitFor(60, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("Generator program timed out");
        }
        final var text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProgramResult(process.exitValue(), text);
    }

    /// Runs in a fresh JVM so the continuation provider can see the module export.
    public static final class Program {
        public static void main(final String[] arguments) throws Exception {
            final var main = Class.forName(arguments[0]);
            for (final var name : List.of("sum", "manual", "replayable", "trailingLambda", "cleanupOnCancel",
                    "cancelBeforeStart", "breakCloses", "exhaustionCloses", "streamCloses",
                    "returnCloses", "continueKeepsCursorOpen", "exceptionalExitCloses",
                    "streamBreakCloses", "exhaustedCursorStaysDone", "lazyStart")) {
                System.out.println(name + "=" + main.getMethod(name).invoke(null));
            }
        }
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens())
                .parseCompilationUnit(sourceName);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
