package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ResolutionService;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class DoNotationTest {
    @Test
    public void optionChainShortCircuitsAndProjectsTheYield() throws Exception {
        final var packageName = "doOption" + suffix();
        final var source = """
                package %s;

                fn first(flag: Boolean): Option<Int> =
                    if (flag) then Option.Some(7) else Option<Int>.None();

                fn total(flag: Boolean): Option<Int> = do {
                    let base <- first(flag);
                    let added <- if (flag) then Option.Some(base + 1) else Option<Int>.None();
                    yield added * 2;
                };

                fn value(flag: Boolean): Int = match (total(flag)) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };

                fn shortCircuits(flag: Boolean): Boolean = match (total(flag)) {
                    case Option.Some(_) -> false;
                    case Option.None() -> true;
                };
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(16, main.getMethod("value", boolean.class).invoke(null, true));
            assertEquals(true, main.getMethod("shortCircuits", boolean.class).invoke(null, false));
        }
    }

    @Test
    public void resultChainPropagatesTheFirstError() throws Exception {
        final var packageName = "doResult" + suffix();
        final var source = """
                package %s;

                class Failure {
                    public property message: String;
                    public constructor new;
                }

                fn positive(value: Int): Result<Int, Failure> =
                    if (value > 0) then Result<Int, Failure>.Ok(value)
                    else Result<Int, Failure>.Err(Failure.new("negative"));

                fn sum(first: Int, second: Int): Result<Int, Failure> = do {
                    let left <- positive(first);
                    let right <- positive(second);
                    yield left + right;
                };

                fn code(first: Int, second: Int): Int = match (sum(first, second)) {
                    case Result.Ok(value) -> value;
                    case Result.Err(_) -> -1;
                };
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(7, main.getMethod("code", int.class, int.class).invoke(null, 3, 4));
            assertEquals(-1, main.getMethod("code", int.class, int.class).invoke(null, -1, 4));
            assertEquals(-1, main.getMethod("code", int.class, int.class).invoke(null, 3, 0));
        }
    }

    @Test
    public void localBindingsInsideTheBlockKeepTheirScope() throws Exception {
        final var packageName = "doLocals" + suffix();
        final var source = """
                package %s;

                fn total(): Option<Int> = do {
                    let base <- Option.Some(4);
                    let doubled = base * 2;
                    let added <- Option.Some(doubled + 1);
                    yield added + base;
                };

                fn value(): Int = match (total()) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(13, main.getMethod("value").invoke(null));
        }
    }

    @Test
    public void streamChainsLazilyMapEveryElement() throws Exception {
        final var packageName = "doStream" + suffix();
        final var source = """
                package %s;

                import zeron.collections.Stream;

                fn doubled(): Stream<Int> = do<Stream<_>> {
                    let value <- Stream.of(1, 2, 3);
                    yield value * 2;
                };

                fn total(): Int = doubled().fold(0, (accumulated, value) -> accumulated + value);
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(12, main.getMethod("total").invoke(null));
        }
    }

    @Test
    public void nestedStreamBindsDispatchThroughTheMonadWitness() throws Exception {
        final var packageName = "doCartesian" + suffix();
        final var source = """
                package %s;

                import zeron.collections.Stream;

                fn cartesian(xs: Stream<Int>, ys: Stream<Int>): Stream<Int> = do<Stream<_>> {
                    let x <- xs;
                    let y <- ys;
                    yield x * 100 + y;
                };

                fn total(): Int = cartesian(Stream<Int>.of(1, 2), Stream<Int>.of(10, 20))
                    .fold(0, (accumulator, value) -> accumulator + value);
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(660, main.getMethod("total").invoke(null));
        }
    }

    @Test
    public void genericMonadBindsDispatchThroughTheContractBounds() throws Exception {
        final var packageName = "doGeneric" + suffix();
        final var source = """
                package %s;

                import zeron.control.Monad;

                fn through<S<_>: Monad<S>, A>(value: S<A>): S<A> = do {
                    let inner <- value;
                    yield inner;
                };

                fn optionValue(): Int = match (through(Option.Some(4))) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(4, main.getMethod("optionValue").invoke(null));
        }
    }

    @Test
    public void declaredConstructorMustMatchTheInferredChain() throws Exception {
        assertDiagnostic("""
                fn wrong(): Option<Int> = do<Option<_>> {
                    let value <- Result<Int, String>.Ok(1);
                    yield value;
                };
                """, "does not match the inferred result type");
    }

    @Test
    public void malformedBlocksAreRejected() {
        assertDiagnostic("""
                fn empty(): Option<Int> = do {
                    yield 1;
                };
                """, "requires at least one 'let name <- expression' bind clause");
        assertDiagnostic("""
                fn missing(): Option<Int> = do {
                    let value <- Option.Some(1);
                };
                """, "must end with a 'yield' clause");
        assertDiagnostic("""
                fn hoisted(): Option<Int> = do {
                    let early = 1;
                    let value <- Option.Some(early);
                    yield value;
                };
                """, "must follow the first bind clause");
        assertDiagnostic("""
                fn slot(): Option<Int> = do<Option<Int>> {
                    let value <- Option.Some(1);
                    yield value;
                };
                """, "exactly one open slot");
        assertDiagnostic("""
                fn notMonadic(): Option<Int> = do {
                    let value <- 1;
                    yield value;
                };
                """, "");
    }

    private static void assertDiagnostic(final String source, final String expectedMessage) {
        final var parsed = Parser.of(Scanner.from(source, "Do.zn").scanTokens())
                .parseCompilationUnitWithDiagnostics("Do.zn");
        final var messages = new java.util.ArrayList<String>();
        parsed.diagnostics().forEach(diagnostic -> messages.add(diagnostic.message()));
        if (messages.isEmpty()) {
            try {
                final var output = Files.createTempDirectory(Path.of("target"), "zeron-do-diag-");
                final var service = CompilationService.forCompilationUnits(
                        List.of(parsed.compilationUnit()), "Main", "", List.of(), true,
                        List.of(), output);
                service.resolveWithDiagnostics().errors()
                        .forEach(error -> messages.add(error.message()));
                deleteTree(output);
            } catch (final java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
        assertTrue("Expected a diagnostic containing '" + expectedMessage + "' but got " + messages,
                expectedMessage.isEmpty()
                        ? !messages.isEmpty()
                        : messages.stream().anyMatch(message -> message.contains(expectedMessage)));
    }

    private static URLClassLoader compile(final String packageName, final String source) throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-do-");
        try {
            final var unit = parse("Main.zn", source);
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
        } catch (final RuntimeException exception) {
            deleteTree(output);
            throw exception;
        }
        return new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                DoNotationTest.class.getClassLoader()) {
            @Override
            public void close() throws java.io.IOException {
                super.close();
                deleteTree(output);
            }
        };
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path path) {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
