package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ClassTypeParameterBoundTest {
    @Test
    public void classBoundsSurviveLibraryIndexRoundTrip() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "boundedlibrary" + suffix;
        final var clientPackage = "boundedclient" + suffix;
        final var libraryRoot = Files.createTempDirectory(Path.of("target"), "zeron-bounded-library-");
        final var libraryUnit = parse("Library.zn", """
                package %s;
                import zeron.lang.Hashable;
                public class HashKey<T: Hashable<T>> {
                    public constructor new;
                }
                public class HashBox<T: Hashable<T>> {
                    value: T;
                    public constructor new;
                    public hash(): Int = this.value.hash();
                }
                public class NamedHashBox<T: Hashable<T>> {
                    value: T;
                    public constructor new;
                    public constructor of(value: T) = NamedHashBox<T>.new(value);
                    public hash(): Int = this.value.hash();
                }
                public contract HashCarrier<K: Hashable<K>> {
                    key(): K;
                    default keyHash(): Int = this.key().hash();
                }
                public class IntHashCarrier is HashCarrier<Int> {
                    public constructor new;
                    public key(): Int = 29;
                }
                public class GenericHashCarrier<K: Hashable<K>> is HashCarrier<K> {
                    keyValue: K;
                    public constructor new;
                    public key(): K = this.keyValue;
                }
                public contract Empty<T> {
                    constructor empty(value: T);
                }
                public class IntCell is Empty<Int> {
                    value: Int;
                    public constructor new;
                    public constructor empty(value: Int) = IntCell.new(value);
                    public read(): Int = this.value;
                }
                public class FactoryBox<C: Empty<Int>> {
                    public constructor new;
                    public make(): &C = C.empty(42);
                }
                """.formatted(libraryPackage));

        try {
            deleteTree(Path.of("dist"));
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(libraryUnit), libraryPackage + ".Library", libraryPackage);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            copyTree(Path.of("dist"), libraryRoot);

            final var libraryIndex = ZeronLibraryIndex.readFromDirectory(libraryRoot);
            final var exportedClass = libraryIndex.declarations().stream()
                    .filter(ZeronLibraryIndex.ClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ClassExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(libraryPackage + ".HashKey"))
                    .findFirst().orElseThrow();
            assertEquals(1, exportedClass.typeParameters().size());
            assertEquals(1, exportedClass.typeParameters().getFirst().bounds().size());
            final var exportedContract = libraryIndex.declarations().stream()
                    .filter(ZeronLibraryIndex.ContractExport.class::isInstance)
                    .map(ZeronLibraryIndex.ContractExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(libraryPackage + ".HashCarrier"))
                    .findFirst().orElseThrow();
            assertEquals(1, exportedContract.typeParameters().getFirst().bounds().size());

            deleteTree(Path.of("dist"));
            final var validClient = parse("Client.zn", """
                    package %s;
                    import zeron.lang.Hashable;
                    import %s.HashKey;
                    import %s.HashBox;
                    import %s.NamedHashBox;
                    import %s.IntHashCarrier;
                    import %s.GenericHashCarrier;
                    import %s.IntCell;
                    import %s.FactoryBox;
                    public fn create(): &HashKey<Int> = HashKey<Int>.new();
                    public fn hashBox(): Int = HashBox<Int>.new(17).hash();
                    public fn hashBoxObject(): &HashBox<Int> = HashBox<Int>.new(41);
                    public fn namedHashBox(): Int = NamedHashBox<Int>.of(37).hash();
                    public fn forwardedHash<T: Hashable<T>>(value: T): Int =
                            HashBox<T>.new(value).hash();
                    public fn forwardedHashResult(): Int = forwardedHash<Int>(23);
                    public fn hashCarrier(): Int = IntHashCarrier.new().keyHash();
                    public fn genericHashCarrier<K: Hashable<K>>(value: K): Int =
                            GenericHashCarrier<K>.new(value).keyHash();
                    public fn genericHashCarrierResult(): Int = genericHashCarrier<Int>(31);
                    public fn classBoundFactory(): &IntCell =
                            FactoryBox<IntCell>.new().make();
                    """.formatted(clientPackage, libraryPackage, libraryPackage, libraryPackage,
                            libraryPackage, libraryPackage, libraryPackage, libraryPackage,
                            libraryPackage));
            final var validCompiler = CompilationService.forCompilationUnits(
                    List.of(validClient), clientPackage + ".Client", clientPackage, List.of(libraryIndex));
            validCompiler.resolve();
            validCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), libraryRoot.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass(clientPackage + ".Client");
                assertTrue(generated.getMethod("create").invoke(null) != null);
                assertEquals(17, generated.getMethod("hashBox").invoke(null));
                final var hashBox = generated.getMethod("hashBoxObject").invoke(null);
                assertEquals(41, hashBox.getClass().getMethod("hash").invoke(hashBox));
                assertEquals(37, generated.getMethod("namedHashBox").invoke(null));
                assertEquals(23, generated.getMethod("forwardedHashResult").invoke(null));
                assertEquals(29, generated.getMethod("hashCarrier").invoke(null));
                assertEquals(31, generated.getMethod("genericHashCarrierResult").invoke(null));
                final var cell = generated.getMethod("classBoundFactory").invoke(null);
                assertEquals(42, cell.getClass().getMethod("read").invoke(cell));
                final var boundedClass = loader.loadClass(libraryPackage + ".HashKey");
                final var javaConstruction = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> boundedClass.getConstructor().newInstance());
                assertTrue(javaConstruction.getCause() instanceof UnsupportedOperationException);
                final var namedFactory = loader.loadClass(libraryPackage + ".NamedHashBox");
                final var javaFactoryCall = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> namedFactory.getMethod("of", Object.class).invoke(null, 43));
                assertTrue(javaFactoryCall.getCause() instanceof UnsupportedOperationException);
            }

            deleteTree(Path.of("dist"));
            final var invalidClient = parse("InvalidClient.zn", """
                    package %s;
                    import %s.HashKey;
                    fn reject(): &HashKey<Any> = HashKey<Any>.new();
                    """.formatted(clientPackage, libraryPackage));
            final var invalidCompiler = CompilationService.forCompilationUnits(
                    List.of(invalidClient), clientPackage + ".InvalidClient", clientPackage,
                    List.of(libraryIndex));
            assertThrows(ResolutionError.class, invalidCompiler::resolve);

            final var invalidContractClient = parse("InvalidContractClient.zn", """
                    package %s;
                    import %s.HashCarrier;
                    fn reject(value: HashCarrier<Any>): Unit = ();
                    """.formatted(clientPackage, libraryPackage));
            final var invalidContractCompiler = CompilationService.forCompilationUnits(
                    List.of(invalidContractClient), clientPackage + ".InvalidContractClient", clientPackage,
                    List.of(libraryIndex));
            assertThrows(ResolutionError.class, invalidContractCompiler::resolve);
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(libraryRoot);
        }
    }

    private static CompilationUnit parse(final String path, final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(path);
    }

    private static void copyTree(final Path source, final Path destination) throws Exception {
        try (var paths = Files.walk(source)) {
            for (final var path : paths.toList()) {
                final var target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private static void deleteTree(final Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
