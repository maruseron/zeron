package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ZeronLibraryIndexTest {
    @Test
    public void writesAndReadsPublicZeronApiMetadataAlongsideCompiledClasses() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "library" + suffix;
        final var appPackage = "app" + suffix;
        final var entryName = "LibraryIndexMain" + suffix;
        final var indexPath = Path.of("dist", "META-INF", "zeron", "api-v2.bin");
        final var mainPath = Path.of("dist", appPackage, entryName + ".class");
        final var boxPath = Path.of("dist", libraryPackage, "Box.class");
        final var contractPath = Path.of("dist", libraryPackage, "Echo.class");
        final var namedPath = Path.of("dist", libraryPackage, "Named.class");
        var functionHolderPath = (Path) null;
        final var entry = parse("app/Main.zn", """
                package %s;
                fn result(): Int = 42;
                """.formatted(appPackage));
        final var library = parse("library/Api.zn", """
                package %s;
                public contract Echo<T> {
                    echo(value: T): T;
                }
                public contract Named {
                    name(): String;
                }
                public class Box<T> is Echo<T> {
                    value: T;
                    public constructor new;
                    public read(): T = this.value;
                    public mut write(value: T): Unit { this.value = value; }
                    public echo(value: T): T = value;
                }
                public fn identity<T>(value: T): T = value;
                public fn display<T: Named>(value: T): String = value.name();
                fn hidden(): Int = 0;
                """.formatted(libraryPackage));

        try {
            final var compiler = Compiler.forCompilationUnits(
                    List.of(entry, library), appPackage + "." + entryName, appPackage);
            compiler.resolve();
            compiler.compile();

            final var index = ZeronLibraryIndex.readFrom(indexPath);
            assertEquals(StandardLibrary.API_VERSION, index.standardLibraryApiVersion());
            final var identity = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(function -> function.qualifiedName().equals(libraryPackage + ".identity"))
                    .findFirst()
                    .orElseThrow();
                    functionHolderPath = Path.of("dist", identity.jvmOwner().replace('.', '/') + ".class");
            assertTrue(identity.jvmOwner().startsWith(libraryPackage + ".$File$Api$"));
            assertTrue(identity.signature().isGeneric());
            assertEquals(1, identity.signature().typeParameters().size());
            assertEquals(identity.signature().typeParameters().getFirst(), identity.signature().parameters().getFirst());
            assertEquals(identity.signature().parameters().getFirst(), identity.signature().returnType());
            final var display = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(function -> function.qualifiedName().equals(libraryPackage + ".display"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(libraryPackage + ".Named",
                    display.signature().typeParameters().getFirst().bound().name());

            final var box = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ClassExport.class::cast)
                    .filter(type -> type.qualifiedName().equals(libraryPackage + ".Box"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(box.canonicalConstructorPublic());
            assertEquals(1, box.canonicalConstructorParameters().size());
            assertEquals(box.typeParameters().getFirst(), box.canonicalConstructorParameters().getFirst());
            assertEquals(libraryPackage + ".Echo", box.contracts().getFirst().qualifiedName());
            assertTrue(box.methods().stream().anyMatch(method -> method.name().equals("read")));
            assertTrue(box.methods().stream().anyMatch(method -> method.name().equals("write")
                    && method.mutating()));

            final var echo = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ContractExport.class::isInstance)
                    .map(ZeronLibraryIndex.ContractExport.class::cast)
                    .filter(type -> type.qualifiedName().equals(libraryPackage + ".Echo"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, echo.methods().size());
            assertFalse(index.declarations().stream()
                    .anyMatch(declaration -> declaration.qualifiedName().equals(libraryPackage + ".hidden")));
            assertFalse(index.declarations().stream()
                    .anyMatch(declaration -> declaration.qualifiedName().startsWith("zeron.")));
            assertTrue(Files.exists(indexPath));
        } finally {
            Files.deleteIfExists(indexPath);
            Files.deleteIfExists(mainPath);
            Files.deleteIfExists(boxPath);
            Files.deleteIfExists(contractPath);
            Files.deleteIfExists(namedPath);
            if (functionHolderPath != null) Files.deleteIfExists(functionHolderPath);
        }
    }

        @Test
        public void rejectsLibraryIndexesBuiltAgainstAnotherStandardLibraryVersion() throws Exception {
                final var libraryRoot = Files.createTempDirectory(Path.of("target"), "zeron-incompatible-library-");
                final var indexPath = libraryRoot.resolve(Path.of("META-INF", "zeron", "api-v2.bin"));
                try {
                        new ZeronLibraryIndex(StandardLibrary.API_VERSION + 1, List.of()).writeTo(indexPath);
                        assertThrows(IOException.class, () -> ZeronLibraryIndex.readFromDirectory(libraryRoot));
                } finally {
                        deleteTree(libraryRoot);
                }
        }

        @Test
        public void bundledStandardLibraryDeclarationsCannotBeOverridden() {
                final var override = parse("Override.zn", """
                                package zeron.collections;
                                public contract Iterable<T> {
                                        iterator(): T;
                                }
                                """);
                final var compiler = Compiler.forCompilationUnits(List.of(override), "Override", "zeron.collections");
                assertThrows(ResolutionError.class, compiler::resolve);
        }

        @Test
        public void compilesConsumerAgainstLibraryDirectoryWithoutLibrarySources() throws Exception {
                final var suffix = UUID.randomUUID().toString().replace("-", "");
                final var libraryPackage = "compiledlib" + suffix;
                final var appPackage = "libraryclient" + suffix;
                final var libraryMainName = libraryPackage + ".LibraryBuilder" + suffix;
                final var clientMainName = appPackage + ".LibraryConsumer" + suffix;
                final var libraryRoot = Files.createTempDirectory(Path.of("target"), "zeron-library-classes-");
                final var libraryIndexPath = libraryRoot.resolve(Path.of("META-INF", "zeron", "api-v2.bin"));
                final var libraryUnit = parse("library/Api.zn", """
                                package %s;
                                public contract Echo<T> {
                                        echo(value: T): T;
                                }
                                public class Box<T> is Echo<T> {
                                        value: T;
                                        public constructor new;
                                        public read(): T = this.value;
                                        public echo(value: T): T = value;
                                }
                                public fn identity<T>(value: T): T = value;
                                """.formatted(libraryPackage));

                try {
                        deleteTree(Path.of("dist"));
                        final var libraryCompiler = Compiler.forCompilationUnits(
                                        List.of(libraryUnit), libraryMainName, libraryPackage);
                        libraryCompiler.resolve();
                        libraryCompiler.compile();

                        copyTree(Path.of("dist", libraryPackage), libraryRoot.resolve(libraryPackage));
                        Files.createDirectories(libraryIndexPath.getParent());
                        Files.copy(Path.of("dist", "META-INF", "zeron", "api-v2.bin"), libraryIndexPath);
                        final var library = ZeronLibraryIndex.readFromDirectory(libraryRoot);

                        deleteTree(Path.of("dist"));
                        final var client = parse("LibraryConsumer.zn", """
                                        package %s;
                                        import %s.Box as LibraryBox;
                                        import %s.Echo as Echo;
                                        import %s.identity as identity;
                                        fn result(): Int {
                                                let box = LibraryBox<Int>.new(identity<Int>(55));
                                                let echo: Echo<Int> = box;
                                                return echo.echo(box.read());
                                        }
                                        """.formatted(appPackage, libraryPackage, libraryPackage, libraryPackage));
                        final var clientCompiler = Compiler.forCompilationUnits(
                                        List.of(client), clientMainName, appPackage, List.of(library));
                        clientCompiler.resolve();
                        clientCompiler.compile();

                        try (final var loader = new URLClassLoader(new java.net.URL[]{
                                        Path.of("dist").toUri().toURL(), libraryRoot.toUri().toURL()}, getClass().getClassLoader())) {
                                assertEquals(55, loader.loadClass(clientMainName).getMethod("result").invoke(null));
                        }
                } finally {
                        deleteTree(Path.of("dist"));
                        deleteTree(libraryRoot);
                }
        }

        private static void copyTree(final Path source, final Path target) throws Exception {
                try (final var paths = Files.walk(source)) {
                        for (final var path : paths.toList()) {
                                final var destination = target.resolve(source.relativize(path));
                                if (Files.isDirectory(path)) Files.createDirectories(destination);
                                else Files.copy(path, destination);
                        }
                }
        }

        private static void deleteTree(final Path path) throws Exception {
                if (!Files.exists(path)) return;
                try (final var paths = Files.walk(path)) {
                        for (final var file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                                Files.deleteIfExists(file);
                        }
                }
        }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(sourcePath);
    }
}
