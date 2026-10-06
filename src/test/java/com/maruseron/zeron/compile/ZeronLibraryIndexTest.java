package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.analize.ResolutionService;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.ZeronLibraryJar;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ZeronLibraryIndexTest {
    @Test
    public void compiledLibraryMetadataPreservesSealedContractPermits() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "sealedlibrary" + suffix;
        final var entryName = "SealedLibraryMain" + suffix;
        final var entry = parse("app/Main.zn", """
                package app;
                fn answer(): Int = 42;
                """);
        final var library = parse("library/Sealed.zn", """
                package %s;
                public sealed contract Outcome<T> permits Success<T>, Failure<T> {
                    read(): T;
                }
                public class Success<T> is Outcome<T> {
                    payload: T;
                    public constructor new;
                    public read(): T = this.payload;
                }
                public class Failure<T> is Outcome<T> {
                    payload: T;
                    public constructor new;
                    public read(): T = this.payload;
                }
                """.formatted(libraryPackage));
        final var indexPath = Path.of("dist", "META-INF", "zeron", "api-v13.bin");

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(entry, library), "app." + entryName, "app");
            compiler.resolve();
            compiler.compile();

            final var index = ZeronLibraryIndex.readFrom(indexPath);
            final var outcome = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ContractExport.class::isInstance)
                    .map(ZeronLibraryIndex.ContractExport.class::cast)
                    .filter(contract -> contract.qualifiedName().equals(libraryPackage + ".Outcome"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(outcome.sealed());
            assertEquals(List.of(libraryPackage + ".Success", libraryPackage + ".Failure"),
                    outcome.permittedClasses().stream()
                            .map(ZeronLibraryIndex.ContractUseExport::qualifiedName).toList());
            assertEquals(outcome.typeParameters().getFirst(),
                    outcome.permittedClasses().getFirst().typeArguments().getFirst());

            final var consumer = parse("library/Intruder.zn", """
                    package %s;
                    public class Intruder<T> is Outcome<T> {
                        payload: T;
                        public constructor new;
                        public read(): T = this.payload;
                    }
                    """.formatted(libraryPackage));
            final var units = new java.util.ArrayList<>(List.of(consumer));
            units.addAll(index.toCompilationUnits("sealed-api"));
            assertThrows(ResolutionError.class, () -> new ResolutionService().resolveUnits(units));
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void compiledLibraryEnforcesSealedPermitsInSeparateConsumerCompilation() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "sealedartifact" + suffix;
        final var appPackage = "sealedclient" + suffix;
        final var libraryRoot = Files.createTempDirectory(Path.of("target"), "zeron-sealed-library-");
        final var indexPath = libraryRoot.resolve(Path.of("META-INF", "zeron", "api-v13.bin"));
        final var library = parse("Outcome.zn", """
                package %s;
                public sealed contract Outcome<T> permits Success<T>, Failure<T> {
                    read(): T;
                }
                public class Success<T> is Outcome<T> {
                    payload: T;
                    public constructor new;
                    public read(): T = this.payload;
                }
                public class Failure<T> is Outcome<T> {
                    payload: T;
                    public constructor new;
                    public read(): T = this.payload;
                }
                """.formatted(libraryPackage));
        final var client = parse("Main.zn", """
                package %s;
                import %s.Outcome;
                import %s.Success;
                fn result(): Int {
                    let outcome: Outcome<Int> = Success<Int>.new(42);
                    return outcome.read();
                }
                """.formatted(appPackage, libraryPackage, libraryPackage));

        try {
            deleteTree(Path.of("dist"));
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(library), libraryPackage + ".LibraryBuilder", libraryPackage);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            copyTree(Path.of("dist", libraryPackage), libraryRoot.resolve(libraryPackage));
            Files.createDirectories(indexPath.getParent());
            Files.copy(Path.of("dist", "META-INF", "zeron", "api-v13.bin"), indexPath);

            final var compiledLibrary = ZeronLibraryIndex.readFromDirectory(libraryRoot);
            deleteTree(Path.of("dist"));
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), appPackage + ".Main", appPackage, List.of(compiledLibrary));
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), libraryRoot.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(appPackage + ".Main").getMethod("result").invoke(null));
            }

            final var matchClient = parse("Match.zn", """
                    package %s;
                    import %s.Outcome;
                    import %s.Success;
                    import %s.Failure;
                    fn result(): Int {
                        let value: Outcome<Int> = Success<Int>.new(42);
                        return match (value) {
                            case Success<Int> as success -> success.read();
                            case Failure<Int> as failure -> failure.read();
                        };
                    }
                    """.formatted(appPackage, libraryPackage, libraryPackage, libraryPackage));
            final var matchCompiler = CompilationService.forCompilationUnits(
                    List.of(matchClient), appPackage + ".Match", appPackage,
                    List.of(compiledLibrary));
            matchCompiler.resolve();
            matchCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), libraryRoot.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(appPackage + ".Match").getMethod("result").invoke(null));
            }

            final var incompleteClient = parse("Incomplete.zn", """
                    package %s;
                    import %s.Outcome;
                    import %s.Success;
                    fn result(value: Outcome<Int>): Int = match (value) {
                        case Success<Int> -> 42;
                    };
                    """.formatted(appPackage, libraryPackage, libraryPackage));
            final var incompleteCompiler = CompilationService.forCompilationUnits(
                    List.of(incompleteClient), appPackage + ".Incomplete", appPackage,
                    List.of(compiledLibrary));
            assertThrows(ResolutionError.class, incompleteCompiler::resolve);

            final var intruder = parse("Intruder.zn", """
                    package %s;
                    public class Intruder<T> is Outcome<T> {
                        payload: T;
                        public constructor new;
                        public read(): T = this.payload;
                    }
                    """.formatted(libraryPackage));
            final var invalidCompiler = CompilationService.forCompilationUnits(
                    List.of(intruder), libraryPackage + ".IntruderMain", libraryPackage,
                    List.of(compiledLibrary));
            assertThrows(ResolutionError.class, invalidCompiler::resolve);
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(libraryRoot);
        }
    }

    @Test
    public void variadicCallableMetadataSurvivesSeparateLibraryCompilation() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var contractPackage = "variadicapi" + suffix;
        final var implementationPackage = "variadicimpl" + suffix;
        final var appPackage = "variadicclient" + suffix;
        final var contractRoot = Files.createTempDirectory(Path.of("target"), "zeron-variadic-api-");
        final var implementationRoot = Files.createTempDirectory(Path.of("target"), "zeron-variadic-impl-");
        final var contract = parse("Metric.zn", """
                package %s;
                public contract Metric {
                    measure(prefix: Int = 4, values: Int...): Int;
                }
                """.formatted(contractPackage));
        final var implementation = parse("Counter.zn", """
                package %s;
                import %s.Metric;
                public fn size(prefix: Int = 5, values: Int...): Int = prefix + values.length;
                public class Counter is Metric {
                    values: Array<Int>;
                    public constructor of(values: Int...) = Counter.new(values);
                    public constructor withPrefix(prefix: Int, values: Int...) {
                        let result = Counter.new(values);
                        return result;
                    }
                    public measure(prefix: Int, values: Int...): Int =
                        this.values.length + prefix + values.length;
                }
                """.formatted(implementationPackage, contractPackage));
        final var app = parse("Main.zn", """
                package %s;
                import %s.Metric;
                import %s.Counter;
                import %s.size;
                fn fromFunction(): Int = size();
                fn fromFunctionMany(): Int = size(8, 1, 2);
                fn fromClassDefault(): Int = Counter.of().measure();
                fn fromNamed(): Int = Counter.of(1, 2, 3).measure(8, 9);
                fn fromNamedPrefix(): Int = Counter.withPrefix(0, 1, 2).measure(8, 9);
                fn fromContract(metric: Metric): Int = metric.measure(8, 1, 2);
                fn makeCounter(): Metric = Counter.of();
                """.formatted(appPackage, contractPackage, implementationPackage, implementationPackage));

        try {
            deleteTree(Path.of("dist"));
            final var contractCompiler = CompilationService.forCompilationUnits(
                    List.of(contract), contractPackage + ".Builder", contractPackage);
            contractCompiler.resolve();
            contractCompiler.compile();
            copyTree(Path.of("dist", contractPackage), contractRoot.resolve(contractPackage));
            final var contractIndexPath = contractRoot.resolve(Path.of("META-INF", "zeron", "api-v13.bin"));
            Files.createDirectories(contractIndexPath.getParent());
            Files.copy(Path.of("dist", "META-INF", "zeron", "api-v13.bin"), contractIndexPath);
            final var contractIndex = ZeronLibraryIndex.readFromDirectory(contractRoot);
            final var exportedMetric = contractIndex.declarations().stream()
                    .filter(ZeronLibraryIndex.ContractExport.class::isInstance)
                    .map(ZeronLibraryIndex.ContractExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(contractPackage + ".Metric"))
                    .findFirst().orElseThrow();
            assertTrue(exportedMetric.methods().getFirst().variadic());
            deleteTree(Path.of("dist"));

            final var implementationCompiler = CompilationService.forCompilationUnits(
                    List.of(implementation), implementationPackage + ".Builder", implementationPackage,
                    List.of(contractIndex));
            implementationCompiler.resolve();
            implementationCompiler.compile();
            copyTree(Path.of("dist", implementationPackage), implementationRoot.resolve(implementationPackage));
            final var implementationIndexPath = implementationRoot.resolve(
                    Path.of("META-INF", "zeron", "api-v13.bin"));
            Files.createDirectories(implementationIndexPath.getParent());
            Files.copy(Path.of("dist", "META-INF", "zeron", "api-v13.bin"), implementationIndexPath);
            final var implementationIndex = ZeronLibraryIndex.readFromDirectory(implementationRoot);

            final var function = implementationIndex.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(implementationPackage + ".size"))
                    .findFirst().orElseThrow();
            assertTrue(function.variadic());
            assertEquals(0, function.minimumArity());
            final var exportedCounter = implementationIndex.declarations().stream()
                    .filter(ZeronLibraryIndex.ClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ClassExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(implementationPackage + ".Counter"))
                    .findFirst().orElseThrow();
            assertTrue(exportedCounter.methods().getFirst().variadic());
            assertTrue(exportedCounter.namedConstructors().stream()
                    .allMatch(ZeronLibraryIndex.NamedConstructorExport::variadic));

            deleteTree(Path.of("dist"));
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(app), appPackage + ".Main", appPackage,
                    List.of(contractIndex, implementationIndex));
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), contractRoot.toUri().toURL(),
                    implementationRoot.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(appPackage + ".Main");
                assertEquals(5, main.getMethod("fromFunction").invoke(null));
                assertEquals(10, main.getMethod("fromFunctionMany").invoke(null));
                assertEquals(4, main.getMethod("fromClassDefault").invoke(null));
                assertEquals(12, main.getMethod("fromNamed").invoke(null));
                assertEquals(11, main.getMethod("fromNamedPrefix").invoke(null));
                final var metric = loader.loadClass(contractPackage + ".Metric");
                assertEquals(10, main.getMethod("fromContract", metric)
                        .invoke(null, main.getMethod("makeCounter").invoke(null)));
            }
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(contractRoot);
            deleteTree(implementationRoot);
        }
    }

    @Test
    public void readsJarIndexesAndRejectsMissingOrIncompatibleMetadata() throws Exception {
        final var temporaryRoot = Files.createTempDirectory(Path.of("target"), "zeron-library-jar-index-");
        final var indexPath = temporaryRoot.resolve("api.bin");
        final var validJar = temporaryRoot.resolve("valid.jar");
        final var wrongSchemaJar = temporaryRoot.resolve("wrong-schema.jar");
        final var wrongStandardLibraryJar = temporaryRoot.resolve("wrong-stdlib.jar");
        final var missingIndexJar = temporaryRoot.resolve("missing-index.jar");

        try {
            new ZeronLibraryIndex(StandardLibrary.API_VERSION, List.of()).writeTo(indexPath);
            writeJarWithIndex(validJar, Files.readAllBytes(indexPath));
            assertEquals(StandardLibrary.API_VERSION,
                    ZeronLibraryIndex.readFromJar(validJar).standardLibraryApiVersion());

            final var incompatibleSchema = Files.readAllBytes(indexPath);
            java.nio.ByteBuffer.wrap(incompatibleSchema).putInt(Integer.BYTES, ZeronLibraryIndex.VERSION + 1);
            writeJarWithIndex(wrongSchemaJar, incompatibleSchema);
            assertThrows(IOException.class, () -> ZeronLibraryIndex.readFromJar(wrongSchemaJar));

            new ZeronLibraryIndex(StandardLibrary.API_VERSION + 1, List.of()).writeTo(indexPath);
            writeJarWithIndex(wrongStandardLibraryJar, Files.readAllBytes(indexPath));
            assertThrows(IOException.class, () -> ZeronLibraryIndex.readFromJar(wrongStandardLibraryJar));

            try (final var output = new JarOutputStream(Files.newOutputStream(missingIndexJar))) {
                output.putNextEntry(new JarEntry("unrelated.txt"));
                output.write(new byte[]{1});
                output.closeEntry();
            }
            assertThrows(IOException.class, () -> ZeronLibraryIndex.readFromJar(missingIndexJar));
        } finally {
            deleteTree(temporaryRoot);
        }
    }

    @Test
    public void writesAndReadsPublicZeronApiMetadataAlongsideCompiledClasses() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "library" + suffix;
        final var appPackage = "app" + suffix;
        final var entryName = "LibraryIndexMain" + suffix;
        final var indexPath = Path.of("dist", "META-INF", "zeron", "api-v13.bin");
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
            final var compiler = CompilationService.forCompilationUnits(
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
    public void roundTripsMultipleFunctionBoundsAndUsesThemFromACompiledLibrary() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "multiboundlib" + suffix;
        final var appPackage = "multiboundapp" + suffix;
        final var library = parse("Library.zn", """
                package %s;
                public contract Named { name(): String; }
                public contract Encodable { encode(): String; }
                public contract Renderer {
                    render<T: Named + Encodable>(value: T): String;
                }
                public class Item is Named, Encodable {
                    label: String;
                    public constructor new;
                    public name(): String = this.label;
                    public encode(): String = "[" + this.label + "]";
                }
                public class LibraryRenderer is Renderer {
                    public constructor new;
                    public render<T: Named + Encodable>(value: T): String =
                        value.name() + value.encode();
                }
                public fn describe<T: Named + Encodable>(value: T): String =
                    value.name() + value.encode();
                """.formatted(libraryPackage));
        final var client = parse("Client.zn", """
                package %s;
                import %s.Item;
                import %s.LibraryRenderer;
                import %s.describe;
                fn result(): String {
                    let item = Item.new("library");
                    return describe(item) + LibraryRenderer.new().render(item);
                }
                """.formatted(appPackage, libraryPackage, libraryPackage, libraryPackage));
        final var indexPath = Path.of("dist", "META-INF", "zeron", "api-v13.bin");

        try {
            deleteTree(Path.of("dist"));
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(library), libraryPackage + ".Builder", libraryPackage);
            libraryCompiler.resolve();
            libraryCompiler.compile();

            final var index = ZeronLibraryIndex.readFrom(indexPath);
            final var describe = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(function -> function.qualifiedName().equals(libraryPackage + ".describe"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of(libraryPackage + ".Named", libraryPackage + ".Encodable"),
                    describe.signature().typeParameters().getFirst().bounds().stream()
                            .map(bound -> bound.name()).toList());
            final var renderer = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ContractExport.class::isInstance)
                    .map(ZeronLibraryIndex.ContractExport.class::cast)
                    .filter(contract -> contract.qualifiedName().equals(libraryPackage + ".Renderer"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of(libraryPackage + ".Named", libraryPackage + ".Encodable"),
                    renderer.methods().getFirst().signature().typeParameters().getFirst().bounds().stream()
                            .map(bound -> bound.name()).toList());
            final var libraryRenderer = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ClassExport.class::cast)
                    .filter(type -> type.qualifiedName().equals(libraryPackage + ".LibraryRenderer"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of(libraryPackage + ".Named", libraryPackage + ".Encodable"),
                    libraryRenderer.methods().getFirst().signature().typeParameters().getFirst().bounds().stream()
                            .map(bound -> bound.name()).toList());

            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), appPackage + ".Client", appPackage, List.of(index));
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals("library[library]library[library]",
                        loader.loadClass(appPackage + ".Client").getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

        @Test
    public void resolvesAndInvokesOverloadsFromSeparatelyCompiledLibraries() throws Exception {
            final var suffix = UUID.randomUUID().toString().replace("-", "");
            final var libraryPackage = "overloadlibrary" + suffix;
            final var appPackage = "overloadclient" + suffix;
            final var libraryOneRoot = Files.createTempDirectory(Path.of("target"), "zeron-overload-one-");
            final var libraryTwoRoot = Files.createTempDirectory(Path.of("target"), "zeron-overload-two-");
            final var clientMainName = appPackage + ".OverloadConsumer" + suffix;
            final var libraryOneIndexPath = libraryOneRoot.resolve(Path.of("META-INF", "zeron", "api-v13.bin"));
            final var libraryTwoIndexPath = libraryTwoRoot.resolve(Path.of("META-INF", "zeron", "api-v13.bin"));
            try {
                    for (int libraryNumber = 1; libraryNumber <= 2; libraryNumber++) {
                            deleteTree(Path.of("dist"));
                            final var libraryEntry = libraryPackage + ".LibraryEntry" + libraryNumber + suffix;
                            final var overloadUnit = parse("Overload" + libraryNumber + ".zn", libraryNumber == 1
                                    ? """
                                            package %s;
                                            public fn select(value: Int): String = "integer";
                                            """.formatted(libraryPackage)
                                    : """
                                            package %s;
                                            public fn select(value: String): String = "string";
                                            """.formatted(libraryPackage));
                            final var libraryCompiler = CompilationService.forCompilationUnits(
                                    List.of(overloadUnit), libraryEntry, libraryPackage);
                            libraryCompiler.resolve();
                            libraryCompiler.compile();
                            final var libraryRoot = libraryNumber == 1 ? libraryOneRoot : libraryTwoRoot;
                            copyTree(Path.of("dist", libraryPackage), libraryRoot.resolve(libraryPackage));
                            final var targetIndex = libraryNumber == 1
                                    ? libraryOneIndexPath : libraryTwoIndexPath;
                            Files.createDirectories(targetIndex.getParent());
                            Files.copy(Path.of("dist", "META-INF", "zeron", "api-v13.bin"),
                                    targetIndex);
                    }
                    final var firstIndex = ZeronLibraryIndex.readFromDirectory(libraryOneRoot);
                    final var secondIndex = ZeronLibraryIndex.readFromDirectory(libraryTwoRoot);
                    deleteTree(Path.of("dist"));
                    final var client = parse("OverloadClient.zn", """
                            package %s;
                            import %s.select as select;
                            fn intResult(): String = select(1);
                            fn stringResult(): String = select("text");
                            """.formatted(appPackage, libraryPackage));
                    final var consumer = CompilationService.forCompilationUnits(
                            List.of(client), clientMainName, appPackage, List.of(firstIndex, secondIndex));
                    consumer.resolve();
                    consumer.compile();
                    try (final var loader = new URLClassLoader(new java.net.URL[]{
                            Path.of("dist").toUri().toURL(), libraryOneRoot.toUri().toURL(),
                            libraryTwoRoot.toUri().toURL()}, getClass().getClassLoader())) {
                            final var generated = loader.loadClass(clientMainName);
                            assertEquals("integer", generated.getMethod("intResult").invoke(null));
                            assertEquals("string", generated.getMethod("stringResult").invoke(null));
                    }
            } finally {
                    deleteTree(Path.of("dist"));
                    deleteTree(libraryOneRoot);
                    deleteTree(libraryTwoRoot);
            }
    }

    @Test
    public void rejectsLibraryIndexesBuiltAgainstAnotherStandardLibraryVersion() throws Exception {
                final var libraryRoot = Files.createTempDirectory(Path.of("target"), "zeron-incompatible-library-");
                final var indexPath = libraryRoot.resolve(Path.of("META-INF", "zeron", "api-v13.bin"));
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
                final var compiler = CompilationService.forCompilationUnits(List.of(override), "Override", "zeron.collections");
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
                final var libraryIndexPath = libraryRoot.resolve(Path.of("META-INF", "zeron", "api-v13.bin"));
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
                        final var libraryCompiler = CompilationService.forCompilationUnits(
                                        List.of(libraryUnit), libraryMainName, libraryPackage);
                        libraryCompiler.resolve();
                        libraryCompiler.compile();

                        copyTree(Path.of("dist", libraryPackage), libraryRoot.resolve(libraryPackage));
                        Files.createDirectories(libraryIndexPath.getParent());
                        Files.copy(Path.of("dist", "META-INF", "zeron", "api-v13.bin"), libraryIndexPath);
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
                        final var clientCompiler = CompilationService.forCompilationUnits(
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

        @Test
        public void publicTopLevelLibraryValuesInitializeLazilyOnFirstRead() throws Exception {
                final var suffix = UUID.randomUUID().toString().replace("-", "");
                final var libraryPackage = "valueprovider" + suffix;
                final var appPackage = "valueconsumer" + suffix;
                final var libraryMainName = libraryPackage + ".LibraryEntry";
                final var clientMainName = appPackage + ".LibraryConsumer";
                final var libraryRoot = Files.createTempDirectory(Path.of("target"), "zeron-value-library-");
                final var libraryJar = Path.of("target", "zeron-value-library-" + suffix + ".jar");
                final var entry = parse("Entry.zn", """
                                package %s;
                                fn placeholder(): Int = 0;
                                """.formatted(libraryPackage));
                final var values = parse("Values.zn", """
                                package %s;
                                import zeron.io.println;
                                let mut counter = 0;
                                fn initializeAnswer(): Int {
                                    counter += 1;
                                    println("library initialized");
                                    return 42;
                                }
                                public let answer = initializeAnswer();
                                """.formatted(libraryPackage));

                try {
                        deleteTree(Path.of("dist"));
                        final var libraryCompiler = CompilationService.forCompilationUnits(
                                        List.of(entry, values), libraryMainName, libraryPackage);
                        libraryCompiler.resolve();
                        libraryCompiler.compile();

                        final var indexPath = Path.of("dist", "META-INF", "zeron", "api-v13.bin");
                        final var index = ZeronLibraryIndex.readFrom(indexPath);
                        final var exportedValue = index.declarations().stream()
                                        .filter(ZeronLibraryIndex.ValueExport.class::isInstance)
                                        .map(ZeronLibraryIndex.ValueExport.class::cast)
                                        .filter(value -> value.qualifiedName().equals(libraryPackage + ".answer"))
                                        .findFirst()
                                        .orElseThrow();
                        assertNull(exportedValue.namespaceName());
                        assertEquals(libraryMainName, exportedValue.initializationOwner());

                        copyTree(Path.of("dist"), libraryRoot);
                        ZeronLibraryJar.write(libraryRoot, libraryJar);
                        final var jarIndex = ZeronLibraryIndex.readFromJar(libraryJar);
                        deleteTree(Path.of("dist"));
                        final var client = parse("Consumer.zn", """
                                        package %s;
                                        import %s.answer as importedAnswer;
                                        import %s.*;
                                        fn untouched(): Int = 7;
                                        fn result(): Int = importedAnswer;
                                        fn starResult(): Int = answer;
                                        """.formatted(appPackage, libraryPackage, libraryPackage));
                        final var clientCompiler = CompilationService.forCompilationUnits(
                                        List.of(client), clientMainName, appPackage, List.of(jarIndex));
                        clientCompiler.resolve();
                        clientCompiler.compile();

                        final var originalOutput = System.out;
                        final var capturedOutput = new ByteArrayOutputStream();
                        try (final var redirectedOutput = new PrintStream(capturedOutput)) {
                                System.setOut(redirectedOutput);
                                try (final var loader = new URLClassLoader(new java.net.URL[]{
                                        Path.of("dist").toUri().toURL(), libraryJar.toUri().toURL()},
                                                getClass().getClassLoader())) {
                                        final var consumer = loader.loadClass(clientMainName);
                                        assertEquals(7, consumer.getMethod("untouched").invoke(null));
                                        assertEquals("", capturedOutput.toString());
                                        assertEquals(42, consumer.getMethod("result").invoke(null));
                                        assertEquals("library initialized" + System.lineSeparator(),
                                                        capturedOutput.toString());
                                        assertEquals(42, consumer.getMethod("result").invoke(null));
                                        assertEquals(42, consumer.getMethod("starResult").invoke(null));
                                        assertEquals("library initialized" + System.lineSeparator(),
                                                        capturedOutput.toString());
                                }
                        } finally {
                                System.setOut(originalOutput);
                        }
                } finally {
                        deleteTree(Path.of("dist"));
                        deleteTree(libraryRoot);
                        Files.deleteIfExists(libraryJar);
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

        private static void writeJarWithIndex(final Path jarPath, final byte[] indexBytes) throws IOException {
                try (final var output = new JarOutputStream(Files.newOutputStream(jarPath))) {
                        output.putNextEntry(new JarEntry("META-INF/zeron/api-v13.bin"));
                        output.write(indexBytes);
                        output.closeEntry();
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
