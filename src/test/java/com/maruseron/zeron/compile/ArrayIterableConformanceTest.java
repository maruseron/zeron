package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.analize.ResolutionService;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ArrayIterableConformanceTest {
    @Test
    public void convertsArraysToFreshIterableAdaptersAndSupportsBoundEvidence() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "arrayiterable" + suffix;
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-array-iterable-");
        final var entry = sourceRoot.resolve(packageName).resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package %s;
                import zeron.collections.Iterable;
                import zeron.lang.Cell;

                fn sum(values: Iterable<Int>): Int {
                    let mut total = 0;
                    for (let value in values) {
                        total += value;
                    }
                    return total;
                }

                fn defaultForEach(values: Iterable<Int>): Int {
                    let total = Cell<Int>.new(0);
                    values.forEach(value -> total.set(total.get() + value));
                    return total.get();
                }

                fn returned(values: Array<Int>): Iterable<Int> = values;
                fn argument(values: Array<Int>): Int = sum(values);

                fn assigned(values: Array<Int>): Int {
                    let iterable: Iterable<Int> = values;
                    return sum(iterable);
                }

                fn bound<T: Iterable<Int>>(values: T): Int {
                    values.forEach(value -> {});
                    let iterator = values.iterator();
                    return 1;
                }

                fn throughBound(values: Array<Int>): Int = bound(values);

                fn aliasVisible(): Int {
                    let values: &Array<Int> = [1, 2, 3];
                    let iterable = returned(values);
                    values[0] = 10;
                    return sum(iterable);
                }

                fn directArrayLoop(): Int {
                    let values: Array<Int> = [1, 2, 3];
                    let mut total = 0;
                    for (let value in values) {
                        total += value;
                    }
                    return total;
                }
                """.formatted(packageName));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of(packageName, "Main.zn").toString()));
            final var program = ClassFile.of().parse(Path.of("dist", packageName, "Main.class"));
            final var directLoop = program.methods().stream()
                    .filter(method -> method.methodName().equalsString("directArrayLoop"))
                    .findFirst().orElseThrow();
            assertFalse(directLoop.code().orElseThrow().elementStream()
                    .filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast)
                    .anyMatch(invoke -> invoke.owner().asInternalName()
                            .equals("zeron/runtime/ArrayIterable")
                            && invoke.name().equalsString("wrap")));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                final var iterableType = loader.loadClass("zeron.collections.Iterable");
                final var values = new Object[]{1, 2, 3};
                final var first = main.getMethod("returned", Object[].class).invoke(null, (Object) values);
                final var second = main.getMethod("returned", Object[].class).invoke(null, (Object) values);

                assertTrue(iterableType.isInstance(first));
                assertNotSame(first, second);
                assertEquals(6, main.getMethod("sum", iterableType).invoke(null, first));
                assertEquals(6, main.getMethod("defaultForEach", iterableType).invoke(null, first));
                assertEquals(6, main.getMethod("argument", Object[].class).invoke(null, (Object) values));
                assertEquals(6, main.getMethod("assigned", Object[].class).invoke(null, (Object) values));
                assertEquals(1, main.getMethod("throughBound", Object[].class).invoke(null, (Object) values));
                assertEquals(15, main.getMethod("aliasVisible").invoke(null));
                assertEquals(6, main.getMethod("directArrayLoop").invoke(null));
            }
        } finally {
            deleteTree(sourceRoot);
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void requiresExactElementTypesAndDoesNotExposeIterableMembersOnArrays() {
        for (final var source : List.of(
                """
                package arrayiterablebad;
                import zeron.collections.Iterable;
                fn consume(values: Iterable<Long>): Unit {}
                fn invalid(values: Array<Int>): Unit = consume(values);
                """,
                """
                package arrayiterablebad;
                import zeron.collections.Iterable;
                fn invalid(values: Array<Int>?): Iterable<Int> = values;
                """,
                """
                package arrayiterablebad;
                fn invalid(values: Array<Int>): Unit {
                    values.forEach(value -> {});
                }
                """)) {
            assertThrows(ResolutionError.class, () -> resolve(source));
        }
    }

    @Test
    public void suppliesArrayEvidenceToGenericBoundsFromCompiledLibraries() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "arrayiterablelibrary" + suffix;
        final var clientPackage = "arrayiterableclient" + suffix;
        final var libraryRoot = Files.createTempDirectory(Path.of("target"), "zeron-array-iterable-library-");
        try {
            deleteTree(Path.of("dist"));
            final var libraryUnit = parse("Library.zn", """
                    package %s;
                    import zeron.collections.Iterable;
                    public fn consume<T: Iterable<Int>>(values: T): Int {
                        values.forEach(value -> {});
                        let iterator = values.iterator();
                        return 17;
                    }
                    """.formatted(libraryPackage));
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(libraryUnit), libraryPackage + ".Library", libraryPackage);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            copyTree(Path.of("dist"), libraryRoot);
            final var libraryIndex = ZeronLibraryIndex.readFromDirectory(libraryRoot);

            deleteTree(Path.of("dist"));
            final var clientUnit = parse("Client.zn", """
                    package %s;
                    import %s.consume;
                    public fn fromArray(values: Array<Int>): Int = consume(values);
                    """.formatted(clientPackage, libraryPackage));
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(clientUnit), clientPackage + ".Client", clientPackage, List.of(libraryIndex));
            clientCompiler.resolve();
            clientCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), libraryRoot.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var client = loader.loadClass(clientPackage + ".Client");
                assertEquals(17, client.getMethod("fromArray", Object[].class)
                        .invoke(null, (Object) new Object[]{1, 2}));
            }
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(libraryRoot);
        }
    }

    private static void resolve(final String source) {
        final var unit = parse("ArrayIterableConformance.zn", source);
        new ResolutionService().resolveUnits(StandardLibrary.withBundledUnits(List.of(unit)));
    }

    private static CompilationUnit parse(final String path, final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(path);
    }

    private static void copyTree(final Path source, final Path destination) throws Exception {
        try (final var paths = Files.walk(source)) {
            for (final var path : paths.toList()) {
                final var target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var files = Files.walk(path)) {
            for (final var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
