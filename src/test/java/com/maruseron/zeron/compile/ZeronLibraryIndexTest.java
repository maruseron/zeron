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
        final var indexPath = Path.of("dist", "META-INF", "zeron", "api-v4.bin");
        final var mainPath = Path.of("dist", appPackage, entryName + ".class");
        final var boxPath = Path.of("dist", libraryPackage, "Box.class");
        final var contractPath = Path.of("dist", libraryPackage, "Echo.class");
        final var namedPath = Path.of("dist", libraryPackage, "Named.class");
        final var holderContractPath = Path.of("dist", libraryPackage, "Holder.class");
        final var stringHolderPath = Path.of("dist", libraryPackage, "StringHolder.class");
        var functionHolderPath = (Path) null;
        final var entry = parse("app/Main.zn", """
                package %s;
                fn result(): Int = 42;
                """.formatted(appPackage));
        final var library = parse("library/Api.zn", """
                package %s;
                public contract Echo<T> {
                    property tag: Int;
                    echo(value: T): T;
                    copy<U>(value: U): U;
                    select<U>(ignored: U): T;
                }
                public contract Named {
                    name(): String;
                    default label(): String = this.name();
                }
                public class Box<T> is Echo<T> {
                    value: T;
                    mirror: T = value;
                    public property tag: Int = 7;
                    public property echoValue: T = value;
                    public constructor new;
                    public read(): T = this.mirror;
                    public mut write(value: T): Unit { this.value = value; }
                    public echo(value: T): T = value;
                    public copy<V>(value: V): V = value;
                    public select<V>(ignored: V): T = this.value;
                }
                public contract Holder<T> {
                    mut property value: T;
                }
                public class StringHolder is Holder<String> {
                    public mut property value: String;
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
            assertTrue(box.properties().stream().anyMatch(property -> property.name().equals("tag")
                    && !property.mutating()));
            assertEquals(box.typeParameters().getFirst(), box.properties().stream()
                    .filter(property -> property.name().equals("echoValue"))
                    .findFirst().orElseThrow().type());
            assertTrue(box.methods().stream().anyMatch(method -> method.name().equals("write")
                    && method.mutating()));
            final var copy = box.methods().stream()
                    .filter(method -> method.name().equals("copy"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, copy.signature().typeParameters().size());
            assertEquals(copy.signature().typeParameters().getFirst(), copy.signature().parameters().getFirst());
            assertEquals(copy.signature().parameters().getFirst(), copy.signature().returnType());
            final var select = box.methods().stream()
                    .filter(method -> method.name().equals("select"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(select.signature().typeParameters().getFirst(),
                    select.signature().parameters().getFirst());
            assertEquals(box.typeParameters().getFirst(), select.signature().returnType());

            final var echo = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ContractExport.class::isInstance)
                    .map(ZeronLibraryIndex.ContractExport.class::cast)
                    .filter(type -> type.qualifiedName().equals(libraryPackage + ".Echo"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(3, echo.methods().size());
            assertTrue(echo.properties().stream().anyMatch(property -> property.name().equals("tag")));
            final var contractSelect = echo.methods().stream()
                    .filter(method -> method.name().equals("select"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(contractSelect.signature().typeParameters().getFirst(),
                    contractSelect.signature().parameters().getFirst());
            assertEquals(echo.typeParameters().getFirst(), contractSelect.signature().returnType());
            final var named = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ContractExport.class::isInstance)
                    .map(ZeronLibraryIndex.ContractExport.class::cast)
                    .filter(type -> type.qualifiedName().equals(libraryPackage + ".Named"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(named.methods().stream().anyMatch(method -> method.name().equals("label")
                    && method.defaultMethod()));
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
            Files.deleteIfExists(holderContractPath);
            Files.deleteIfExists(stringHolderPath);
            if (functionHolderPath != null) Files.deleteIfExists(functionHolderPath);
        }
    }

        @Test
        public void rejectsLibraryIndexesBuiltAgainstAnotherStandardLibraryVersion() throws Exception {
                final var libraryRoot = Files.createTempDirectory(Path.of("target"), "zeron-incompatible-library-");
                final var indexPath = libraryRoot.resolve(Path.of("META-INF", "zeron", "api-v4.bin"));
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
                final var libraryIndexPath = libraryRoot.resolve(Path.of("META-INF", "zeron", "api-v4.bin"));
                final var libraryUnit = parse("library/Api.zn", """
                                package %s;
                                public contract Echo<T> {
                                        property tag: Int;
                                        echo(value: T): T;
                                        copy<U>(value: U): U;
                                        select<U>(ignored: U): T;
                                }
                                public class Box<T> is Echo<T> {
                                        value: T;
                                        public property tag: Int = 7;
                                        public property echoValue: T = value;
                                        public constructor new;
                                        public read(): T = this.value;
                                        public echo(value: T): T = value;
                                        public copy<V>(value: V): V = value;
                                        public select<V>(ignored: V): T = this.value;
                                }
                                public contract Holder<T> {
                                        mut property value: T;
                                }
                                public contract Named {
                                        name(): String;
                                        default label(): String = this.name();
                                }
                                public class StringHolder is Holder<String> {
                                        public mut property value: String;
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
                        Files.copy(Path.of("dist", "META-INF", "zeron", "api-v4.bin"), libraryIndexPath);
                        final var library = ZeronLibraryIndex.readFromDirectory(libraryRoot);

                        deleteTree(Path.of("dist"));
                        final var client = parse("LibraryConsumer.zn", """
                                        package %s;
                                        import %s.Box as LibraryBox;
                                        import %s.Echo as Echo;
                                        import %s.Holder as Holder;
                                        import %s.Named as Named;
                                        import %s.StringHolder as StringHolder;
                                        import %s.identity as identity;
                                        class ClientName is Named {
                                                public constructor new;
                                                public name(): String = "library default";
                                        }
                                        fn result(): Int {
                                                let box = LibraryBox<Int>.new(identity<Int>(55));
                                                let echo: Echo<Int> = box;
                                                return echo.copy(echo.select("ignored"));
                                        }
                                        fn propertyResult(): Int = LibraryBox<Int>.new(12).tag;
                                        fn genericPropertyResult(): Int = LibraryBox<Int>.new(14).echoValue;
                                        fn contractPropertyResult(value: Echo<Int>): Int = value.tag;
                                        fn genericContractPropertyResult(): String {
                                                let holder: &Holder<String> = StringHolder.new("one");
                                                holder.value = "two";
                                                return holder.value;
                                        }
                                        fn libraryDefaultMethodResult(): String {
                                                let named: Named = ClientName.new();
                                                return named.label();
                                        }
                                                """.formatted(appPackage, libraryPackage, libraryPackage, libraryPackage,
                                        libraryPackage, libraryPackage, libraryPackage));
                        final var clientCompiler = Compiler.forCompilationUnits(
                                        List.of(client), clientMainName, appPackage, List.of(library));
                        clientCompiler.resolve();
                        clientCompiler.compile();

                        try (final var loader = new URLClassLoader(new java.net.URL[]{
                                        Path.of("dist").toUri().toURL(), libraryRoot.toUri().toURL()}, getClass().getClassLoader())) {
                                assertEquals(55, loader.loadClass(clientMainName).getMethod("result").invoke(null));
                                assertEquals(7,
                                        loader.loadClass(clientMainName).getMethod("propertyResult").invoke(null));
                                assertEquals(14, loader.loadClass(clientMainName)
                                        .getMethod("genericPropertyResult").invoke(null));
                                final var boxClass = loader.loadClass(libraryPackage + ".Box");
                                final var box = boxClass.getConstructor(Object.class).newInstance(13);
                                assertEquals(7, loader.loadClass(clientMainName)
                                        .getMethod("contractPropertyResult",
                                                loader.loadClass(libraryPackage + ".Echo"))
                                        .invoke(null, box));
                                assertEquals("two", loader.loadClass(clientMainName)
                                        .getMethod("genericContractPropertyResult").invoke(null));
                                assertEquals("library default", loader.loadClass(clientMainName)
                                        .getMethod("libraryDefaultMethodResult").invoke(null));
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
