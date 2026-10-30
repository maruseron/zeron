package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
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
    public void delegatedGenericMethodMayAcceptBroaderTypeArguments() throws Exception {
        final var source = """
                contract Mapper<F<_>> {
                    map<A, B>(this: F<A>, transform: (A) -> B): F<B>;
                }
                class Box<T: mut> is Mapper<Box<_>> {
                    public property value: T;
                    public constructor new;
                    public map<R: mut>(transform: (T) -> R): Box<R> =
                        Box<R>.new(transform(this.value));
                    delegating implementation Mapper<Box<_>> {}
                }
                fn run(): Int =
                    Box<Int>.new(41).map(value -> value + 1).value;
                """;
        final var program = CompilationService.forCompilationUnits(
                List.of(parseUnit(source)), "DelegatingBroaderTypeArguments", "", List.of(), false);

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass("DelegatingBroaderTypeArguments")
                        .getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void delegatingClassImplementationForwardsGenericReceiverOperations() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var mainClass = "DelegatingHktMain" + suffix;
        final var boxClass = "DelegatingHktBox" + suffix;
        final var contractName = "DelegatingMapper" + suffix;
        final var source = """
                contract %1$s<F<_>> {
                    map<A: mut, B: mut>(this: F<A>, transform: (A) -> B): F<B>;
                }
                class %2$s<T: nullable + mut> is %1$s<%2$s<_>> {
                    public property value: T;
                    public constructor new;
                    public map<R: mut>(transform: (T) -> R): %2$s<R> =
                        %2$s<R>.new(transform(this.value));
                    delegating implementation %1$s<%2$s<_>> {}
                }
                fn mapValue<F<_>: %1$s<F>, A: mut, B: mut>(
                    source: F<A>, transform: (A) -> B
                ): F<B> = source.map(transform);
                fn run(): Int =
                    mapValue<%2$s<_>, Int, Int>(%2$s<Int>.new(41), value -> value + 1).value;
                """.formatted(contractName, boxClass);
        final var unit = Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(null);
        final var program = CompilationService.forCompilationUnits(
                List.of(unit), mainClass, "", List.of(), false);

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
    public void delegatingClassImplementationAcceptsCompatibleInstanceMethodSignatures() throws Exception {
        final var source = """
                contract Replacer<F<_>> {
                    replace<A: mut>(this: F<A>, value: A): F<A>;
                }
                class Box<T: mut> is Replacer<Box<_>> {
                    public property value: T;
                    public constructor new;
                    public replace(value: Any): Box<T> = this;
                    delegating implementation Replacer<Box<_>> {}
                }
                fn replaceValue<F<_>: Replacer<F>, A: mut>(source: F<A>, value: A): F<A> =
                    source.replace(value);
                fn run(): Int =
                    replaceValue<Box<_>, Int>(Box<Int>.new(42), 0).value;
                """;
        final var program = CompilationService.forCompilationUnits(
                List.of(parseUnit(source)), "DelegatingCompatibleHktMain", "", List.of(), false);

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass("DelegatingCompatibleHktMain")
                        .getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void delegatingClassImplementationForwardsInheritedHktMethods() throws Exception {
        final var source = """
                contract Mapper<F<_>> {
                    map<A: mut, B: mut>(this: F<A>, transform: (A) -> B): F<B>;
                }
                contract Applicative<F<_>> requires Mapper<F> {
                    pure<A: mut>(value: A): F<A>;
                }
                class Box<T: mut> is Applicative<Box<_>> {
                    public property value: T;
                    public constructor new;
                    public map<R: mut>(transform: (T) -> R): Box<R> =
                        Box<R>.new(transform(this.value));
                    delegating implementation Applicative<Box<_>> {
                        fn pure<A: mut>(value: A): Box<A> = Box<A>.new(value);
                    }
                }
                fn mapValue<F<_>: Mapper<F>, A: mut, B: mut>(
                    source: F<A>, transform: (A) -> B
                ): F<B> = source.map(transform);
                fn run(): Int =
                    mapValue<Box<_>, Int, Int>(Box<Int>.new(40), value -> value + 2).value;
                """;
        final var program = CompilationService.forCompilationUnits(
                List.of(parseUnit(source)), "DelegatingInheritedHktMain", "", List.of(), false);

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass("DelegatingInheritedHktMain")
                        .getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void delegatingClassImplementationsSynthesizeSharedInheritedHktMethods() throws Exception {
        final var source = """
                contract Mapper<F<_>> {
                    map<A: mut, B: mut>(this: F<A>, transform: (A) -> B): F<B>;
                }
                contract Applicative<F<_>> requires Mapper<F> {
                    pure<A: mut>(value: A): F<A>;
                    ap<A: mut, B: mut>(this: F<(A) -> B>, values: F<A>): F<B>;
                }
                contract Monad<F<_>> requires Applicative<F> {
                    flatMap<A: mut, B: mut>(this: F<A>, transform: (A) -> F<B>): F<B>;
                }
                contract Foldable<F<_>> requires Mapper<F> {
                    foldLeft<A: mut, B: mut>(this: F<A>, initial: B, combine: (B, A) -> B): B;
                }
                class Box<T: nullable + mut> is Monad<Box<_>>, Foldable<Box<_>> {
                    public property value: T;
                    public constructor new;
                    public map<R: mut>(transform: (T) -> R): Box<R> =
                        Box<R>.new(transform(this.value));
                    public foldLeft<R: mut>(initial: R, combine: (R, T) -> R): R =
                        combine(initial, this.value);
                    delegating implementation Monad<Box<_>> {
                        fn pure<A: mut>(value: A): Box<A> = Box<A>.new(value);
                        fn ap<A: mut, B: mut>(this: Box<(A) -> B>, values: Box<A>): Box<B> =
                            Box<B>.new(this.value(values.value));
                        fn flatMap<A: mut, B: mut>(
                            this: Box<A>, transform: (A) -> Box<B>
                        ): Box<B> = transform(this.value);
                    }
                    delegating implementation Foldable<Box<_>> {}
                }
                fn run(): Int = Box<Int>.new(42).value;
                """;
        final var program = CompilationService.forCompilationUnits(
                List.of(parseUnit(source)), "DelegatingSharedHktMain", "", List.of(), false);

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass("DelegatingSharedHktMain")
                        .getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void higherKindedBoundCanPassAnExplicitConstructorPattern() throws Exception {
        final var source = """
                contract Mapper<F<_>> {
                    map<A: mut, B: mut>(this: F<A>, transform: (A) -> B): F<B>;
                }
                contract Applicative<F<_>> requires Mapper<F> {
                    pure<A: mut>(value: A): F<A>;
                    ap<A: mut, B: mut>(this: F<(A) -> B>, values: F<A>): F<B>;
                }
                contract Monad<F<_>> requires Applicative<F> {
                    flatMap<A: mut, B: mut>(this: F<A>, transform: (A) -> F<B>): F<B>;
                }
                class Box<T: mut> is Monad<Box<_>> {
                    public property value: T;
                    public constructor new;
                    public map<R: mut>(transform: (T) -> R): Box<R> =
                        Box<R>.new(transform(this.value));
                    delegating implementation Monad<Box<_>> {
                        fn pure<A: mut>(value: A): Box<A> = Box<A>.new(value);
                        fn ap<A: mut, B: mut>(this: Box<(A) -> B>, values: Box<A>): Box<B> =
                            Box<B>.new(this.value(values.value));
                        fn flatMap<A: mut, B: mut>(
                            this: Box<A>, transform: (A) -> Box<B>
                        ): Box<B> = transform(this.value);
                    }
                }
                fn pureValue<M<_>: Monad<M>, A: mut>(value: A): M<A> = M.pure(value);
                fn run(): Int = pureValue<Box<_>, Int>(42).value;
                """;
        final var program = CompilationService.forCompilationUnits(
                List.of(parseUnit(source)), "HktConstructorArgumentMain", "", List.of(), false);

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass("HktConstructorArgumentMain")
                        .getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void delegatingClassImplementationPreservesMutableReceiverOperations() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var mainClass = "DelegatingMutableHktMain" + suffix;
        final var bagClass = "DelegatingMutableHktBag" + suffix;
        final var contractName = "DelegatingAppender" + suffix;
        final var source = """
                contract %1$s<F<_>> {
                    mut add<A: mut>(this: F<A>, value: A): Boolean;
                }
                class %2$s<T: mut> is %1$s<%2$s<_>> {
                    public mut property count: Int = 0;
                    public constructor new;
                    public mut add(value: T): Boolean {
                        this.count += 1;
                        return true;
                    }
                    delegating implementation %1$s<%2$s<_>> {}
                }
                fn append<F<_>: %1$s<F>, A: mut>(values: &F<A>, value: A): Boolean =
                    values.add(value);
                fn run(): Int {
                    let bag = %2$s<Int>.new();
                    append<%2$s<_>, Int>(bag, 1);
                    return bag.count;
                }
                """.formatted(contractName, bagClass);
        final var unit = Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(null);
        final var program = CompilationService.forCompilationUnits(
                List.of(unit), mainClass, "", List.of(), false);

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(1, loader.loadClass(mainClass).getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void explicitHktImplementationMethodsWinAndReceiverlessOperationsStayExplicit() {
        final var result = new ResolutionService().resolveWithDiagnostics(parse("""
                contract Mapper<F<_>> {
                    map<A: mut, B: mut>(this: F<A>, transform: (A) -> B): F<B>;
                    pure<A: mut>(value: A): F<A>;
                }
                class Box<T: mut> is Mapper<Box<_>> {
                    public property value: T;
                    public constructor new;
                    public map<R: mut>(transform: (T) -> R): Box<R> =
                        Box<R>.new(transform(this.value));
                    public pure(value: T): Box<T> = Box<T>.new(value);
                    delegating implementation Mapper<Box<_>> {
                        fn map<A: mut, B: mut>(this: Box<A>, transform: (A) -> B): String = "invalid";
                    }
                }
                """));

        assertTrue(result.errors().toString(), result.errors().stream()
                .anyMatch(error -> error.message().equals(
                        "Witness method does not satisfy the contract requirement.")));
        assertTrue(result.errors().toString(), result.errors().stream()
                .anyMatch(error -> error.message().contains("Missing witness implementation for contract method 'pure'")));
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
                class Bag<T: mut> is Sink<T>, Collector<Bag<_>> {
                    public mut property count: Int = 0;
                    public constructor empty() = Bag<T>.new();
                    public mut add(value: T): Boolean {
                        this.count += 1;
                        return true;
                    }
                    public read(): Int = this.count;
                    implementation Collector<Bag<_>> {
                        fn empty<A: mut>(): &Bag<A> = Bag<A>.empty();
                        fn add<A: mut>(this: &Bag<A>, value: A): Boolean {
                            this.add(value);
                            this.add(value);
                            return true;
                        }
                    }
                }
                fn empty<C<_>: Collector<C>, A: mut>(): &C<A> = C.empty<A>();
                fn append<C<_>: Collector<C>, A: mut>(values: &C<A>, value: A): Boolean =
                    values.add(value);
                fn appendWithBoth<A: mut, C<_>: Sink<A> + Collector<C>>(
                    values: &C<A>, value: A
                ): Boolean = values.add(value);
                fn appendStatically<A: mut, C<_>: Sink<A> + Collector<C>>(
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
        final var program = CompilationService.forCompilationUnits(
                List.of(parseUnit(source)), "CollectorMain", "", List.of(), true, List.of());

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
                fn append<C<_>: Collector<C>, A: mut>(values: C<A>, value: A): Boolean =
                    values.add(value);
                """;
        assertThrows(ResolutionError.class,
                () -> new CompilationService(parse(immutableCall), "ImmutableCollectorMain").resolve());

        final var immutableBehavioralCall = """
                import zeron.collections.Collector;
                import zeron.collections.Sink;
                fn append<A, C<_>: Sink<A> + Collector<C>>(
                    values: C<A>, value: A
                ): Boolean = values.add(value);
                """;
        final var error = assertThrows(ResolutionError.class,
                () -> CompilationService.forCompilationUnits(
                        List.of(parseUnit(immutableBehavioralCall)), "ImmutableSinkMain", "", List.of(),
                        true, List.of()).resolve());
        assertTrue(error.getMessage().contains("Mutating methods require"));

        final var immutableStaticCall = """
                import zeron.collections.Collector;
                fn append<A, C<_>: Collector<C>>(
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
        final var program = CompilationService.forCompilationUnits(
                List.of(parseUnit(source)), "HktCompositionMain", "", List.of(), true, List.of());

        program.resolve();
    }

    @Test
    public void receiverBasedHktDefaultUsesDeclaredOperations() throws Exception {
        final var source = """
                contract Foldable<F<_>> {
                    foldLeft<A: mut, B: mut>(
                        this: F<A>, initial: B, combine: (B, A) -> B
                    ): B;
                    default count<A: mut>(this: F<A>): Int =
                        this.foldLeft(0, (count, _) -> count + 1);
                }
                class Box<T: mut> is Foldable<Box<_>> {
                    public property value: T;
                    public constructor new;
                    implementation Foldable<Box<_>> {
                        fn foldLeft<A: mut, B: mut>(
                            this: Box<A>, initial: B, combine: (B, A) -> B
                        ): B = combine(initial, this.value);
                    }
                }
                fn countItems<F<_>: Foldable<F>, A: mut>(source: F<A>): Int =
                    source.count();
                fn run(): Int = countItems<Box<_>, Int>(Box<Int>.new(42));
                """;
        final var program = new CompilationService(parse(source), "HktDefaultCountMain");

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(1, loader.loadClass("HktDefaultCountMain")
                        .getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void receiverlessHktDefaultUsesContractEvidence() throws Exception {
        final var source = """
                contract Factory<F<_>> {
                    pure<A: mut>(value: A): F<A>;
                    default create<A: mut>(value: A): F<A> = F.pure(value);
                }
                class Box<T: mut> is Factory<Box<_>> {
                    public property value: T;
                    public constructor new;
                    implementation Factory<Box<_>> {
                        fn pure<A: mut>(value: A): Box<A> = Box<A>.new(value);
                    }
                }
                fn create<F<_>: Factory<F>, A: mut>(value: A): F<A> = F.create(value);
                fn run(): Int = create<Box<_>, Int>(42).value;
                """;
        final var program = new CompilationService(parse(source), "HktDefaultCreateMain");

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass("HktDefaultCreateMain")
                        .getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void hktDefaultCanComposeRequiredContractOperations() throws Exception {
        final var source = """
                contract Functor<F<_>> {
                    map<A: mut, B: mut>(this: F<A>, transform: (A) -> B): F<B>;
                }
                contract Applicative<F<_>> requires Functor<F> {
                    ap<A: mut, B: mut>(this: F<A>, transform: F<(A) -> B>): F<B>;
                    default map2<A: mut, B: mut, C: mut>(
                        first: F<A>, second: F<B>, combine: (A, B) -> C
                    ): F<C> = first.ap(second.map((b) -> applySecond(combine, b)));
                }
                fn applySecond<A: mut, B: mut, C: mut>(
                    combine: (A, B) -> C, second: B
                ): (A) -> C = first -> combine(first, second);
                class Box<T: mut> is Applicative<Box<_>> {
                    public property value: T;
                    public constructor new;
                    implementation Applicative<Box<_>> {
                        fn map<A: mut, B: mut>(
                            this: Box<A>, transform: (A) -> B
                        ): Box<B> = Box<B>.new(transform(this.value));
                        fn ap<A: mut, B: mut>(
                            this: Box<A>, transform: Box<(A) -> B>
                        ): Box<B> = Box<B>.new(transform.value(this.value));
                    }
                }
                fn mapTwo<F<_>: Applicative<F>, A: mut, B: mut, C: mut>(
                    first: F<A>, second: F<B>, combine: (A, B) -> C
                ): F<C> = F.map2(first, second, combine);
                fn run(): Int =
                    mapTwo<Box<_>, Int, Int, Int>(
                        Box<Int>.new(20), Box<Int>.new(22), (a, b) -> a + b
                    ).value;
                """;
        final var program = new CompilationService(parse(source), "HktDefaultMap2Main");

        try {
            deleteTree(Path.of("dist"));
            program.resolve();
            program.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass("HktDefaultMap2Main")
                        .getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void unrelatedInheritedHktDefaultsRequireAnExplicitImplementation() {
        final var source = """
                contract First<F<_>> {
                    default identity<A: mut>(value: F<A>): F<A> = value;
                }
                contract Second<F<_>> {
                    default identity<A: mut>(value: F<A>): F<A> = value;
                }
                contract Combined<F<_>> requires First<F> + Second<F> {}
                class Box<T: mut> is Combined<Box<_>> {
                    implementation Combined<Box<_>> {}
                }
                """;
        final var error = assertThrows(ResolutionError.class,
                () -> new CompilationService(parse(source), "HktDefaultConflictMain").resolve());
        assertTrue(error.getMessage().contains("Multiple inherited HKT defaults"));
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

    private static CompilationUnit parseUnit(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(null);
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
