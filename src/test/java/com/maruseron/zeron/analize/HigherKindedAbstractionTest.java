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
