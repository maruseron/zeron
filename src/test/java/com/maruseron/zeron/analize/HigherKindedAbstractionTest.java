package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HigherKindedAbstractionTest {
    @Test
    public void receiverOperationsDispatchFromAppliedConstructorTypesAndAccessPrivateClassState()
            throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var mainClass = "HktReceiverMain" + suffix;
        final var boxClass = "HktReceiverBox" + suffix;
        final var source = """
                contract Functor<F<_>> {
                    map<A, B>(this: F<A>, transform: (A) -> B): F<B>;
                }
                class %s<T> is Functor<%s<_>> {
                    value: T;
                    public getValue(): T = this.value;
                    implementation Functor<%s<_>> {
                        fn map<A, B>(this: %s<A>, transform: (A) -> B): %s<B> =
                            %s.new(transform(this.value));
                    }
                }
                fn mapValue<F<_>: Functor<F>, A, B>(
                    source: F<A>, transform: (A) -> B
                ): F<B> = source.map(transform);
                fn staticMapValue<F<_>: Functor<F>, A, B>(
                    source: F<A>, transform: (A) -> B
                ): F<B> = F.map(source, transform);
                fn run(): Int {
                    let source = %s.new(40);
                    let viaReceiver = mapValue<%s<_>, Int, Int>(source, value -> value + 1);
                    let viaStatic = staticMapValue<%s<_>, Int, Int>(source, value -> value + 2);
                    let direct = source.map(value -> value + 3);
                    return viaReceiver.getValue() + viaStatic.getValue() + direct.getValue();
                }
                """.formatted(boxClass, boxClass, boxClass, boxClass, boxClass, boxClass,
                boxClass, boxClass, boxClass);
        final var program = new CompilationService(parse(source), mainClass);

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(126, loader.loadClass(mainClass).getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void genericOperationDispatchesThroughConstructorWitness() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var mainClass = "HktMain" + suffix;
        final var boxClass = "HktBox" + suffix;
        final var source = """
                contract Functor<F<_>> {
                    map<A, B>(this: F<A>, transform: (A) -> B): F<B>;
                }
                class %s<T> {
                    public property value: T;
                }
                witness Functor<%s<_>> for %s<_> {
                    fn map<A, B>(this: %s<A>, transform: (A) -> B): %s<B> =
                        %s.new(transform(this.value));
                }
                fn mapValue<F<_>: Functor<F>, A, B>(
                    source: F<A>, transform: (A) -> B
                ): F<B> = F.map(source, transform);
                fn run(): Int {
                    let mapped = mapValue<%s<_>, Int, Int>(%s.new(41), value -> value + 1);
                    let direct = %s.new(41).map(value -> value + 2);
                    return mapped.value + direct.value;
                }
                """.formatted(boxClass, boxClass, boxClass, boxClass, boxClass, boxClass,
                boxClass, boxClass, boxClass);
        final var statements = parse(source);
        final var program = new CompilationService(statements, mainClass);

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(85, loader.loadClass(mainClass).getMethod("run").invoke(null));
            }
            assertFalse(Files.exists(Path.of("dist", "Functor.class")));
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void classImplementationProvidesConstructorEvidence() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var mainClass = "HktDeclaredMain" + suffix;
        final var boxClass = "HktDeclaredBox" + suffix;
        final var source = """
                contract Functor<F<_>> {
                    map<A, B>(source: F<A>, transform: (A) -> B): F<B>;
                }
                class %s<T> is Functor<%s<_>> {
                    public property value: T;
                    implementation Functor<%s<_>> {
                        fn map<A, B>(source: %s<A>, transform: (A) -> B): %s<B> =
                            %s.new(transform(source.value));
                    }
                }
                fn mapValue<F<_>: Functor<F>, A, B>(
                    source: F<A>, transform: (A) -> B
                ): F<B> = F.map(source, transform);
                fn run(): Int {
                    let mapped = mapValue<%s<_>, Int, Int>(%s.new(41), value -> value + 1);
                    return mapped.value;
                }
                """.formatted(boxClass, boxClass, boxClass, boxClass, boxClass, boxClass,
                boxClass, boxClass);
        final var program = new CompilationService(parse(source), mainClass);

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(mainClass).getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void collectorSupportsCreationAndMutatingReceiverDispatch() throws Exception {
        final var source = """
                import zeron.collections.Collector;
                import zeron.collections.Sink;
                class Bag<T: nullable + mut> is Sink<T>, Collector<Bag<_>> {
                    public mut property count: Int = 0;
                    public constructor empty() = Bag<T>.new();
                    public mut add(value: T): Boolean {
                        this.count += 1;
                        return true;
                    }
                    public read(): Int = this.count;
                    implementation Collector<Bag<_>> {
                        fn empty<A: nullable + mut>(): &Bag<A> = Bag<A>.empty();
                        fn add<A: nullable + mut>(this: &Bag<A>, value: A): Boolean {
                            this.add(value);
                            this.add(value);
                            return true;
                        }
                    }
                }
                fn empty<C<_>: Collector<C>, A: nullable + mut>(): &C<A> = C.empty<A>();
                fn append<C<_>: Collector<C>, A: nullable + mut>(values: &C<A>, value: A): Boolean =
                    values.add(value);
                fn appendWithBoth<A: nullable + mut, C<_>: Sink<A> + Collector<C>>(
                    values: &C<A>, value: A
                ): Boolean = values.add(value);
                fn appendStatically<A: nullable + mut, C<_>: Sink<A> + Collector<C>>(
                    values: &C<A>, value: A
                ): Boolean = C.add(values, value);
                fn run(): Int {
                    let values = empty<Bag<_>, Int>();
                    append<Bag<_>, Int>(values, 41);
                    appendWithBoth<Int, Bag<_>>(values, 42);
                    appendStatically<Int, Bag<_>>(values, 43);
                    return values.read();
                }
                """;
        final var program = new CompilationService(parse(source), "CollectorMain");

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(5, loader.loadClass("CollectorMain").getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }

        final var immutableCall = """
                import zeron.collections.Collector;
                fn append<C<_>: Collector<C>, A: nullable + mut>(values: C<A>, value: A): Boolean =
                    values.add(value);
                """;
        assertThrows(ResolutionError.class,
                () -> new CompilationService(parse(immutableCall), "ImmutableCollectorMain").resolve());

        final var immutableBehavioralCall = """
                import zeron.collections.Collector;
                import zeron.collections.Sink;
                fn append<A: nullable, C<_>: Sink<A> + Collector<C>>(
                    values: C<A>, value: A
                ): Boolean = values.add(value);
                """;
        final var error = assertThrows(ResolutionError.class,
                () -> new CompilationService(parse(immutableBehavioralCall), "ImmutableSinkMain").resolve());
        assertTrue(error.getMessage().contains("Mutating methods require"));

        final var immutableStaticCall = """
                import zeron.collections.Collector;
                fn append<A: nullable, C<_>: Collector<C>>(
                    values: C<A>, value: A
                ): Boolean = C.add(values, value);
                """;
        assertThrows(ResolutionError.class,
                () -> new CompilationService(parse(immutableStaticCall), "ImmutableStaticMain").resolve());
    }

    @Test
    public void fixedConstructorArgumentsAreSubstitutedThroughWitnessEvidence() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var mainClass = "HktFixedMain" + suffix;
        final var boxClass = "HktFixedBox" + suffix;
        final var source = """
                contract Functor<F<_>> {
                    map<A, B>(source: F<A>, transform: (A) -> B): F<B>;
                }
                class %s<T, E> is Functor<%s<_, E>> {
                    public property value: T;
                    public property error: E;
                    implementation Functor<%s<_, E>> {
                        fn map<A, B>(source: %s<A, E>, transform: (A) -> B): %s<B, E> =
                            %s.new(transform(source.value), source.error);
                    }
                }
                fn mapValue<F<_>: Functor<F>, A, B>(
                    source: F<A>, transform: (A) -> B
                ): F<B> = F.map(source, transform);
                fn run(): String {
                    let mapped = mapValue<%s<_, String>, Int, Int>(%s.new(41, "e"), value -> value + 1);
                    return mapped.error;
                }
                """.formatted(boxClass, boxClass, boxClass, boxClass, boxClass, boxClass,
                boxClass, boxClass);
        final var program = new CompilationService(parse(source), mainClass);

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals("e", loader.loadClass(mainClass).getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void rejectsConstructorWitnessWithDifferentContractAndTargetPatterns() {
        final var source = """
                contract Functor<F<_>> {
                    map<A, B>(source: F<A>, transform: (A) -> B): F<B>;
                }
                class Box<T> { value: T; }
                witness Functor<Box<_>> for Box<String> {}
                """;

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
    }

    @Test
    public void independentHigherKindedReceiverProvidersAreAmbiguous() {
        final var source = """
                contract First<F<_>> {
                    map<A>(this: F<A>): F<A>;
                }
                contract Second<F<_>> {
                    map<A>(this: F<A>): F<A>;
                }
                fn apply<F<_>: First<F> + Second<F>, A>(value: F<A>): F<A> = value.map();
                """;
        final var error = assertThrows(ResolutionError.class,
                () -> new CompilationService(parse(source), "AmbiguousHktMain").resolve());
        assertTrue(error.getMessage().contains("ambiguous"));
    }

    @Test
    public void higherKindedBoundsExposeComposedStandardLibraryOperations() {
        final var source = """
                import zeron.control.Monad;
                fn mapWithMonad<F<_>: Monad<F>, A>(
                    source: F<A>, transform: (A) -> A
                ): F<A> = F.map(source, transform);
                """;
        final var program = new CompilationService(parse(source), "HktCompositionMain");

        program.resolve();
    }

    @Test
    public void classHktConformanceRequiresAnImplementationBlock() {
        final var source = """
                contract Functor<F<_>> {
                    map<A, B>(source: F<A>, transform: (A) -> B): F<B>;
                }
                class Box<T> is Functor<Box<_>> {
                    public property value: T;
                }
                """;

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
    }

    @Test
    public void concreteConstructorBoundRequiresUniqueHktEvidence() {
        final var source = """
                contract Functor<F<_>> {
                    map<A, B>(source: F<A>, transform: (A) -> B): F<B>;
                }
                class Box<T> { value: T; }
                fn mapValue<F<_>: Functor<F>, A, B>(
                    source: F<A>, transform: (A) -> B
                ): F<B> = F.map(source, transform);
                fn run(): Box<Int> = mapValue<Box<_>, Int, Int>(Box.new(41), value -> value + 1);
                """;

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (final var item : paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
