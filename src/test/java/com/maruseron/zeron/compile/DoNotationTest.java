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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DoNotationTest {
    @Test
    public void finalMonadValuedExpressionIsReturnedWithoutAnExtraPure() throws Exception {
        final var packageName = "doTail" + suffix();
        final var source = """
                package %s;

                fn next(flag: Boolean): Option<Int> = do {
                    let base <- if (flag) then Option.Some(4) else Option<Int>.None();
                    match (if (base > 0) then Option.Some(base + 1) else Option<Int>.None()) {
                        case Option.Some(found) -> Option.Some(found);
                        case Option.None() -> Option<Int>.None();
                    };
                };

                fn value(flag: Boolean): Int = match (next(flag)) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(5, main.getMethod("value", boolean.class).invoke(null, true));
            assertEquals(-1, main.getMethod("value", boolean.class).invoke(null, false));
        }
    }

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
    public void leadingLocalBindingsRunBeforeTheFirstBind() throws Exception {
        final var packageName = "doLeading" + suffix();
        final var source = """
                package %s;

                fn total(): Option<Int> = do {
                    let base = 3;
                    let prefix = base * 2;
                    let found <- Option.Some(prefix + 1);
                    yield found + base;
                };

                fn value(): Int = match (total()) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };

                fn inferred(): Int = match (do {
                    let base = 2;
                    let found <- Option.Some(4);
                    yield base + found;
                }) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(10, main.getMethod("value").invoke(null));
            assertEquals(6, main.getMethod("inferred").invoke(null));
        }
    }

    @Test
    public void bindFreeBlocksLiftUnitAndMapTheirLeadingBindings() throws Exception {
        final var packageName = "doBindFree" + suffix();
        final var source = """
                package %s;

                fn plain(): Option<Int> = do<Option<_>> {
                    let base = 4;
                    let scaled = base * 2;
                    yield scaled + 1;
                };

                fn monadic(): Option<Int> = do<Option<_>> {
                    if (1 > 0) then Option.Some(5) else Option.None<Int>();
                };

                fn plainValue(): Int = match (plain()) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };

                fn monadicValue(): Int = match (monadic()) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(9, main.getMethod("plainValue").invoke(null));
            assertEquals(5, main.getMethod("monadicValue").invoke(null));
        }
    }

    @Test
    public void bindFreeResultBlockBindsTheWitnessErrorType() throws Exception {
        final var packageName = "doBindFreeResult" + suffix();
        final var source = """
                package %s;

                fn ok(): Result<Int, String> = do<Result<_, String>> {
                    let base = 6;
                    yield base * 7;
                };

                fn value(): Int = match (ok()) {
                    case Result.Ok(found) -> found;
                    case Result.Err(_) -> -1;
                };
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(42, main.getMethod("value").invoke(null));
        }
    }

    @Test
    public void typeLevelContractMethodsDispatchThroughTheConstructorWitness() throws Exception {
        final var packageName = "doStaticPure" + suffix();
        final var source = """
                package %s;

                fn value(): Int = match (Option.pure(5)) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };

                fn resultValue(): Int = match (Result<Int, String>.pure(7)) {
                    case Result.Ok(found) -> found;
                    case Result.Err(_) -> -1;
                };
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(5, main.getMethod("value").invoke(null));
            assertEquals(7, main.getMethod("resultValue").invoke(null));
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
    public void optionGuardsFilterThePrecedingBind() throws Exception {
        final var packageName = "doGuards" + suffix();
        final var source = """
                package %s;

                fn positive(value: Int): Option<Int> = do {
                    let base <- Option.Some(value);
                    if (base > 0);
                    yield base;
                };

                fn inRange(value: Int): Option<Int> = do {
                    let base <- Option.Some(value);
                    if (base > 0);
                    if (base < 10);
                    let scaled = base * 2;
                    yield scaled;
                };

                fn positiveValue(value: Int): Int = match (positive(value)) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };

                fn rangeValue(value: Int): Int = match (inRange(value)) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };

                fn safeDivide(value: Int): Option<Int> = do {
                    let base <- Option.Some(value);
                    if (base > 0);
                    let scaled <- Option.Some(100 / base);
                    yield scaled;
                };

                fn divided(value: Int): Int = match (safeDivide(value)) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(4, main.getMethod("positiveValue", int.class).invoke(null, 4));
            assertEquals(-1, main.getMethod("positiveValue", int.class).invoke(null, -4));
            assertEquals(14, main.getMethod("rangeValue", int.class).invoke(null, 7));
            assertEquals(-1, main.getMethod("rangeValue", int.class).invoke(null, 11));
            assertEquals(50, main.getMethod("divided", int.class).invoke(null, 2));
            assertEquals(-1, main.getMethod("divided", int.class).invoke(null, 0));
        }
    }

    @Test
    public void streamGuardsFilterLazilyInOrder() throws Exception {
        final var packageName = "doStreamGuards" + suffix();
        final var source = """
                package %s;

                import zeron.collections.Stream;

                fn evens(values: Stream<Int>): Stream<Int> = do<Stream<_>> {
                    let value <- values;
                    if (value %% 2 == 0);
                    yield value * 10;
                };

                fn total(): Int = evens(Stream<Int>.of(1, 2, 3, 4))
                    .fold(0, (accumulator, value) -> accumulator + value);

                fn repeated(): Int = {
                    let filtered = evens(Stream<Int>.of(1, 2, 3, 4));
                    yield filtered.fold(0, (a, value) -> a + value)
                        + filtered.fold(0, (a, value) -> a + value);
                };

                fn guardedDivide(): Stream<Int> = do<Stream<_>> {
                    let value <- Stream<Int>.of(0);
                    if (100 / value > 0);
                    yield value;
                };

                fn evaluate(): Int = guardedDivide().fold(0, (a, value) -> a + value);
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(60, main.getMethod("total").invoke(null));
            assertEquals(120, main.getMethod("repeated").invoke(null));
            assertNotNull(main.getMethod("guardedDivide").invoke(null));
            try {
                main.getMethod("evaluate").invoke(null);
                fail("The guarded stream should evaluate its predicate during traversal.");
            } catch (final java.lang.reflect.InvocationTargetException exception) {
                assertTrue(String.valueOf(exception.getCause()),
                        exception.getCause() instanceof ArithmeticException);
            }
        }
    }

    @Test
    public void genericGuardsRequireMonadAndFilterableBounds() throws Exception {
        final var packageName = "doGenericGuards" + suffix();
        final var source = """
                package %s;

                import zeron.control.Monad;
                import zeron.control.Filterable;

                fn active<S<_>: Monad<S> + Filterable<S>, A>(
                    source: S<A>,
                    predicate: (A) -> Boolean
                ): S<A> = do {
                    let value <- source;
                    if (predicate(value));
                    yield value;
                };

                fn value(): Int = match (active(Option.Some(4), number -> number > 0)) {
                    case Option.Some(found) -> found;
                    case Option.None() -> -1;
                };
                """.formatted(packageName);
        try (final var loader = compile(packageName, source)) {
            final var main = loader.loadClass(packageName + ".Main");
            assertEquals(4, main.getMethod("value").invoke(null));
        }
    }

    @Test
    public void guardsWithoutFilterableEvidenceAreRejected() {
        assertDiagnostic("""
                import zeron.control.Monad;

                fn active<S<_>: Monad<S>, A>(source: S<A>, predicate: (A) -> Boolean): S<A> = do {
                    let value <- source;
                    if (predicate(value));
                    yield value;
                };
                """, "Method is not provided by any of the type parameter's contract bounds");
        assertDiagnostic("""
                fn guarded(value: Int): Result<Int, String> = do {
                    let base <- Result<Int, String>.Ok(value);
                    if (base > 0);
                    yield base;
                };
                """, "No contract witness provides 'filter'");
    }

    @Test
    public void guardPlacementAndConditionTypeAreChecked() {
        assertDiagnostic("""
                fn early(): Option<Int> = do {
                    if (true);
                    let value <- Option.Some(1);
                    yield value;
                };
                """, "must follow a bind clause");
        assertDiagnostic("""
                fn late(): Option<Int> = do<Option<_>> {
                    if (true);
                    yield 1;
                };
                """, "must follow a bind clause");
        assertDiagnostic("""
                fn afterLocal(): Option<Int> = do {
                    let value <- Option.Some(1);
                    let doubled = value * 2;
                    if (doubled > 0);
                    yield doubled;
                };
                """, "must precede the bind group's plain 'let name = ...' locals");
        assertDiagnostic("""
                fn nonBoolean(): Option<Int> = do {
                    let value <- Option.Some(1);
                    if (value + 1);
                    yield value;
                };
                """, "Boolean");
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
