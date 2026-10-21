package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class GenericVarianceTest {
    @Test
    public void covariantContractsAndClassesAcceptSafeAssignments() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-variance-");
        final var unit = parseUnit("Variance.zn", """
                package variance;
                contract Animal {}
                class Dog is Animal {
                    public constructor new;
                }
                contract Producer<out T> {
                    get(): T;
                }
                contract NestedProducer<out T> {
                    get(): Producer<T>;
                }
                contract ProjectedProducer<out T> requires Producer<T> {}
                contract VisitorProducer<out T> {
                    visit(callback: (T) -> Boolean): Boolean;
                }
                class DogProducer is Producer<Dog> {
                    public constructor new;
                    public get(): Dog = Dog.new();
                }
                class Empty<out T> {
                    public constructor new;
                }
                fn widenProducer(source: Producer<Dog>): Producer<Animal> = source;
                fn widenNullable(source: Producer<Dog>?): Producer<Animal>? = source;
                fn widenClass(source: Empty<Dog>): Empty<Animal> = source;
                fn widenNested(source: NestedProducer<Dog>): NestedProducer<Animal> = source;
                fn projectNested(source: ProjectedProducer<Dog>): Producer<Animal> = source;
                fn readBounded<T: Producer<Animal>>(source: T): Animal = source.get();
                fn readDog(source: Producer<Dog>): Animal = readBounded<Producer<Dog>>(source);
                fn run(): Boolean {
                    let source: Producer<Animal> = DogProducer.new();
                    return source.get() is Dog;
                }
                """);
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), "variance.Program", "variance",
                    List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertTrue((Boolean) loader.loadClass("variance.Program").getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsCovarianceInUnsafePositionsIncludingPrivateMembers() {
        assertInvalid("""
                contract Bad<out T> {
                    accept(value: T): Unit;
                }
                """);
        assertInvalid("""
                contract Bad<out T> {
                    callback(): (T) -> Boolean;
                }
                """);
        assertInvalid("""
                contract Bad<out T> {
                    values(): Array<T>;
                }
                """);
        assertInvalid("""
                contract Bad<out T> {
                    reference(): &T;
                }
                """);
        assertInvalid("""
                contract Bad<out T> {
                    mut property value: T;
                }
                """);
        assertInvalid("""
                contract Sink<T> {
                    accept(value: T): Unit;
                }
                contract Bad<out T> {
                    sink(): Sink<T>;
                }
                """);
        assertInvalid("""
                class Bad<out T> {
                    private consume(value: T): Unit {}
                }
                """);
    }

    @Test
    public void mutableReferencesAndArraysRemainInvariant() {
        assertInvalid("""
                contract Animal {}
                class Dog is Animal {
                    public constructor new;
                }
                contract Producer<out T> {
                    get(): T;
                }
                fn widen(source: &Producer<Dog>): &Producer<Animal> = source;
                """);
        assertInvalid("""
                contract Animal {}
                class Dog is Animal {
                    public constructor new;
                }
                fn widen(source: Array<Dog>): Array<Animal> = source;
                """);
    }

    @Test
    public void useSiteProjectionsConstrainAssignmentsAndMemberAccess() {
        final var unit = parseUnit("ProjectedCell.zn", """
                package variance.projection;
                contract Animal {}
                class Dog is Animal {
                    public constructor new;
                }
                contract Cell<T> {
                    get(): T;
                    set(value: T): Unit;
                }
                contract MutableCell<T> {
                    mut property value: T;
                }
                class DogCell is Cell<Dog> {
                    public constructor new;
                    public get(): Dog = Dog.new();
                    public set(value: Dog): Unit {}
                }
                fn widen(source: Cell<Dog>): Cell<out Animal> = source;
                fn contravariant(source: Cell<Animal>): Cell<in Dog> = source;
                fn read(source: Cell<out Animal>): Animal = source.get();
                fn write(source: Cell<in Dog>, value: Dog): Unit = source.set(value);
                fn rejectOutWrite(source: Cell<out Animal>, value: Dog): Unit = source.set(value);
                """);
        assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolveUnits(List.of(unit)));
    }

    @Test
    public void useSiteProjectionReadsAndWritesUseCaptureBounds() {
        final var unit = parseUnit("ProjectedCell.zn", """
                package variance.projection;
                contract Animal {}
                class Dog is Animal {
                    public constructor new;
                }
                contract Producer<out T> {
                    get(): T;
                }
                contract Cell<T> {
                    get(): T;
                    set(value: T): Unit;
                }
                contract MutableCell<T> {
                    mut property value: T;
                }
                fn redundant(source: Producer<out Dog>): Producer<out Animal> = source;
                fn widen(source: Cell<Dog>): Cell<out Animal> = source;
                fn consumer(source: Cell<Animal>): Cell<in Dog> = source;
                fn read(source: Cell<out Animal>): Animal = source.get();
                fn write(source: Cell<in Dog>, value: Dog): Unit = source.set(value);
                fn readContravariant(source: Cell<in Dog>): Any? = source.get();
                fn writeProperty(source: &MutableCell<in Dog>, value: Dog): Unit {
                    source.value = value;
                }
                """);
        new ResolutionService().resolveUnits(List.of(unit));
    }

    @Test
    public void rejectsOpposingAndArrayUseSiteProjections() {
        assertInvalid("""
                contract Producer<out T> {}
                fn invalid(value: Producer<in Animal>): Unit {}
                contract Animal {}
                """);
        assertInvalid("""
                fn invalid(value: Array<out String>): Unit {}
                """);
        assertInvalid("""
                contract Cell<T> {}
                contract Animal {}
                fn invalid(value: Cell<out Animal>): Cell<Animal> = value;
                """);
        assertInvalid("""
                contract Animal {}
                contract Container<T> {}
                contract Box<T> {
                    wrap(): Container<T>;
                }
                fn leak(value: Box<out Animal>): Container<Animal> = value.wrap();
                """);
        assertInvalid("""
                contract Animal {}
                contract MutableCell<T> {
                    mut property value: T;
                }
                fn unsafeWrite(value: &MutableCell<out Animal>, item: Animal): Unit {
                    value.value = item;
                }
                """);
        assertInvalid("""
                contract MutableCell<T> {
                    mut property value: T;
                }
                fn unsafeCompoundWrite(value: &MutableCell<in Int>): Unit {
                    value.value += 1;
                }
                """);
    }

    @Test
    public void compiledLibraryMetadataPreservesVarianceForClients() throws Exception {
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-variance-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-variance-client-");
        final var library = parseUnit("Library.zn", """
                package variance.library;
                public contract Animal {}
                public class Dog is Animal {
                    public constructor new;
                }
                public contract Producer<out T> {
                    get(): T;
                }
                public contract Cell<T> {}
                public contract ProjectedViews {
                    transform(value: Cell<out Animal>): Cell<in Animal>;
                }
                public class Empty<out T> {
                    public constructor new;
                }
                """);
        final var client = parseUnit("Client.zn", """
                package variance.client;
                import variance.library.*;
                fn widenProducer(value: Producer<Dog>): Producer<Animal> = value;
                fn widenClass(value: Empty<Dog>): Empty<Animal> = value;
                """);
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(library), "variance.library.Library", "variance.library",
                    List.of(), false, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var producer = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ContractExport.class::isInstance)
                    .map(ZeronLibraryIndex.ContractExport.class::cast)
                    .filter(exported -> exported.qualifiedName().equals("variance.library.Producer"))
                    .findFirst().orElseThrow();
            final var empty = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ClassExport.class::cast)
                    .filter(exported -> exported.qualifiedName().equals("variance.library.Empty"))
                    .findFirst().orElseThrow();
            final var projectedViews = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ContractExport.class::isInstance)
                    .map(ZeronLibraryIndex.ContractExport.class::cast)
                    .filter(exported -> exported.qualifiedName().equals("variance.library.ProjectedViews"))
                    .findFirst().orElseThrow();
            assertEquals(TypeParameterDescriptor.Variance.COVARIANT,
                    producer.typeParameters().getFirst().variance());
            assertEquals(TypeParameterDescriptor.Variance.COVARIANT,
                    empty.typeParameters().getFirst().variance());
            final var projectedParameter = (com.maruseron.zeron.domain.GenericDescriptor)
                    projectedViews.methods().getFirst().signature().parameters().getFirst();
            assertEquals(com.maruseron.zeron.domain.TypeProjectionDescriptor.Kind.OUT,
                    ((com.maruseron.zeron.domain.TypeProjectionDescriptor)
                            projectedParameter.typeParameters().getFirst()).kind());
            final var projectedReturn = (com.maruseron.zeron.domain.GenericDescriptor)
                    projectedViews.methods().getFirst().signature().returnType();
            assertEquals(com.maruseron.zeron.domain.TypeProjectionDescriptor.Kind.IN,
                    ((com.maruseron.zeron.domain.TypeProjectionDescriptor)
                            projectedReturn.typeParameters().getFirst()).kind());

            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), "variance.client.Client", "variance.client",
                    List.of(index), false, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
        }
    }

    private static void assertInvalid(final String source) {
        assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolve(parseUnit("InvalidVariance.zn", source).declarations()));
    }

    private static CompilationUnit parseUnit(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens()).parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (final var paths = Files.walk(root)) {
            for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
