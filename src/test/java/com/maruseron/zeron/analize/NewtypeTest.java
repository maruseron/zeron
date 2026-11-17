package com.maruseron.zeron.analize;

import com.maruseron.zeron.ZeronVersions;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
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

public final class NewtypeTest {
    @Test
    public void witnessHelpersForFunctionNewtypesUseCarrierSignatures() throws Exception {
        final var programName = "NewtypeWitnessProgram" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory("zeron-newtype-witness-");
        final var unit = parse("newtype-witness.zn", """
                import zeron.control.Functor;
                public newtype IO<A: mut> = () -> A;
                public extension<A: mut> IO<A> { public fn run(): A = this(); }
                default witness Functor<IO<_>> for IO<_> {
                    fn map<A, B>(this: IO<A>, transform: A -> B): IO<B> =
                        IO.new(() -> transform(this()));
                }
                fn value(): Int = IO.new(() -> 41).map(item -> item + 1).run();
                """);
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), programName, "", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(programName).getMethod("value").invoke(null));
            }
            try (final var classes = Files.walk(output)) {
                for (final var path : classes.filter(file -> file.toString().endsWith(".class")).toList()) {
                    final var bytes = Files.readAllBytes(path);
                    final var text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                    assertFalse(path + " retains a newtype generic signature",
                            text.contains("LIO<"));
                }
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void newtypeRepresentationsDoNotLeakAcrossCompilations() throws Exception {
        final var firstOutput = Files.createTempDirectory("zeron-newtype-isolation-first");
        final var secondOutput = Files.createTempDirectory("zeron-newtype-isolation-second");
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var first = parse("first.zn", """
                newtype IO<A: mut> = () -> A;
                fn value(): IO<Int> = IO.new(() -> 42);
                """);
        final var second = parse("second.zn", """
                type IO<A> = Pure(value: A) | Bind<B>(source: IO<A>, transform: (A) -> IO<B>);
                fn bind(): IO<Int> {
                    let source: IO<Int> = IO.Pure(1);
                    return IO.Bind<Int>(source, value -> IO.Pure(value + 1));
                }
                fn result(): Int = match (bind()) {
                    case IO.Pure(value) -> value;
                    case IO.Bind<let B>(source, transform) -> 1;
                };
                """);
        try {
            final var firstCompiler = CompilationService.forCompilationUnits(
                    List.of(first), "First" + suffix, "", List.of(), true, List.of(), firstOutput);
            firstCompiler.resolve();
            firstCompiler.compile();

            final var programName = "Second" + suffix;
            final var secondCompiler = CompilationService.forCompilationUnits(
                    List.of(second), programName, "", List.of(), true, List.of(), secondOutput);
            secondCompiler.resolve();
            secondCompiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{secondOutput.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(1, loader.loadClass(programName).getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(firstOutput);
            deleteTree(secondOutput);
        }
    }

    @Test
    public void compiledLibraryPreservesGenericFunctionNewtypeCarrier() throws Exception {
        final var libraryOutput = Files.createTempDirectory("zeron-function-newtype-library");
        final var clientOutput = Files.createTempDirectory("zeron-function-newtype-client");
        final var libraryPackage = "functionnewtypelib" + UUID.randomUUID().toString().replace("-", "");
        final var library = parse("library/IO.zn", """
                package %s;
                public newtype IO<A: mut> = () -> A;
                public extension<A: mut> IO<A> {
                    public fn run(): A = this();
                }
                """.formatted(libraryPackage));
        final var libraryCompiler = CompilationService.forCompilationUnits(
                List.of(library), libraryPackage + ".Library", libraryPackage,
                List.of(), true, List.of(), libraryOutput);
        try {
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFrom(libraryOutput.resolve(
                    ZeronVersions.API_INDEX_RELATIVE_PATH));
            final var exportedType = index.declarations().stream()
                    .filter(ZeronLibraryIndex.NewtypeExport.class::isInstance)
                    .map(ZeronLibraryIndex.NewtypeExport.class::cast)
                    .filter(newtype -> newtype.qualifiedName().equals(libraryPackage + ".IO"))
                    .findFirst().orElseThrow();
            assertEquals(1, exportedType.typeParameters().size());
            assertTrue(exportedType.underlyingType() instanceof com.maruseron.zeron.domain.FunctionDescriptor);

            final var client = parse("client/Main.zn", """
                    package client;
                    import %s.IO;
                    fn reads(): Int = IO.new(() -> 42).run();
                    """.formatted(libraryPackage));
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), "client.Main", "client",
                    List.of(index), true, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass("client.Main")
                        .getMethod("reads").invoke(null));
            }
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
        }
    }

    @Test
    public void functionShapedNewtypesUseTheirRepresentationForOperations() throws Exception {
        final var output = Files.createTempDirectory("zeron-function-newtype-test");
        final var programName = "FunctionNewtypeProgram" + UUID.randomUUID().toString().replace("-", "");
        final var unit = parse("function-newtypes.zn", """
                public newtype IO<A: mut> = () -> A;
                public extension<A: mut> IO<A> {
                    public fn map<B: mut>(transform: A -> B): IO<B> =
                        IO.new(() -> transform(this()));
                    public fn flatMap<B: mut>(transform: A -> IO<B>): IO<B> =
                        IO.new(() -> {
                            let next: () -> B = transform(this());
                            return next();
                        });
                    public fn run(): A = this();
                }
                fn mapped(): Int = IO.new(() -> 41).map(value -> value + 1).run();
                fn chained(): Int =
                    IO.new(() -> 40).flatMap(value -> IO.new(() -> value + 2)).run();
                """);
        final var compiler = CompilationService.forCompilationUnits(
                List.of(unit), programName, "", List.of(), true, List.of(), output);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals(42, program.getMethod("mapped").invoke(null));
                assertEquals(42, program.getMethod("chained").invoke(null));
                assertThrows(ClassNotFoundException.class, () -> loader.loadClass("IO"));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void validatedAndUnvalidatedNewtypesUseDistinctConstructionRules() throws Exception {
        final var output = Files.createTempDirectory("zeron-newtype-test");
        final var programName = "NewtypeProgram" + UUID.randomUUID().toString().replace("-", "");
        final var unit = parse("newtypes.zn", """
                public newtype UserId = String where value != "";
                public newtype Meters = Float;
                namespace UserId { fn text(userId: UserId): String = userId; }
                namespace Meters { fn raw(meters: Meters): Float = meters; }
                fn accepted(): Boolean = match (UserId.tryNew("alice")) {
                    case Option.Some(_) -> true;
                    case Option.None() -> false;
                };
                fn rejected(): Boolean = match (UserId.tryNew("")) {
                    case Option.Some(_) -> false;
                    case Option.None() -> true;
                };
                fn extracted(): String = match (UserId.tryNew("alice")) {
                    case Option.Some(userId) -> UserId.text(userId);
                    case Option.None() -> "";
                };
                fn unvalidatedValue(): Float = Meters.raw(Meters.new(2.5));
                """);
        final var compiler = CompilationService.forCompilationUnits(
                List.of(unit), programName, "", List.of(), true, List.of(), output);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertTrue((Boolean) program.getMethod("accepted").invoke(null));
                assertTrue((Boolean) program.getMethod("rejected").invoke(null));
                assertEquals("alice", program.getMethod("extracted").invoke(null));
                assertEquals(2.5, (Double) program.getMethod("unvalidatedValue").invoke(null), 0.0);
                assertEquals(String.class, program.getMethod("extracted").getReturnType());
                assertEquals(double.class, program.getMethod("unvalidatedValue").getReturnType());
                assertThrows(ClassNotFoundException.class, () -> loader.loadClass("UserId"));
                assertThrows(ClassNotFoundException.class, () -> loader.loadClass("Meters"));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsUncheckedValidatedConstructionIdentityAndNonBooleanValidators() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("bad-new.zn", """
                newtype UserId = String where value != "";
                fn invalid(): UserId = UserId.new("forged");
                """).declarations()));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("identity.zn", """
                newtype UserId = String where value != "";
                fn invalid(first: UserId, second: UserId): Boolean = first === second;
                """).declarations()));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("equality.zn", """
                newtype UserId = String;
                fn invalid(first: UserId, second: UserId): Boolean = first == second;
                """).declarations()));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("validator.zn", """
                newtype UserId = String where value;
                """).declarations()));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("value.zn", """
                newtype UserId = String;
                fn invalid(userId: UserId): String = userId.value;
                """).declarations()));
        final var guardedReference = Parser.of(Scanner.from("""
                data class Point(x: Int, y: Int) {}
                newtype Invalid = Point where value.x > 0;
                """, "invalid-representation.zn").scanTokens())
                .parseCompilationUnitWithDiagnostics("invalid-representation.zn");
        assertFalse(guardedReference.diagnostics().isEmpty());
    }

    @Test
    public void rejectsCovariantNewtypeParameters() {
        final var parsed = Parser.of(Scanner.from("""
                newtype Box<out T> = T;
                """, "variance.zn").scanTokens())
                .parseCompilationUnitWithDiagnostics("variance.zn");
        assertFalse(parsed.diagnostics().isEmpty());
        assertTrue(parsed.diagnostics().toString(), parsed.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("invariant")));
    }

    @Test
    public void acceptsGenericAndNominalRepresentations() throws Exception {
        final var programName = "GenericNewtypeProgram" + UUID.randomUUID().toString().replace("-", "");
        final var unit = parse("generic-newtypes.zn", """
                public newtype Sum<A> = A;
                data class Point(x: Int, y: Int) {}
                public newtype PointView = Point;
                namespace Sum { fn getInt(value: Sum<Int>): Int = value; }
                namespace PointView { fn point(view: PointView): Point = view; }
                fn generic(): Int = Sum.getInt(Sum<Int>.new(41)) + 1;
                fn nominal(): Int = PointView.point(PointView.new(Point.new(3, 4))).x;
                """);
        final var compiler = new CompilationService(unit.declarations(), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals(42, program.getMethod("generic").invoke(null));
                assertEquals(3, program.getMethod("nominal").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void rejectsRuntimeTypeTestsAndCastsOnNewtypes() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("test.zn", """
                newtype UserId = String;
                fn invalid(value: UserId): Boolean = value is UserId;
                """).declarations()));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("cast.zn", """
                newtype UserId = String;
                fn invalid(value: UserId): UserId = value as UserId;
                """).declarations()));
    }

    @Test
    public void newtypeExtensionsOperateOnTheRepresentation() throws Exception {
        final var programName = "NewtypeExtensionProgram" + UUID.randomUUID().toString().replace("-", "");
        final var unit = parse("newtype-extension.zn", """
                newtype UserId = String;
                extension UserId { fn text(): String = this + "!"; }
                fn read(): String = UserId.new("alice").text();
                """);
        final var compiler = new CompilationService(unit.declarations(), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals("alice!", loader.loadClass(programName).getMethod("read").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void compiledLibraryExportsValidatedNewtypeAndFactoryMetadata() throws Exception {
        final var libraryOutput = Files.createTempDirectory("zeron-newtype-library");
        final var clientOutput = Files.createTempDirectory("zeron-newtype-client");
        final var libraryPackage = "newtypelib" + UUID.randomUUID().toString().replace("-", "");
        final var library = parse("library/UserId.zn", """
                package %s;
                public newtype UserId = String where value != "";
                """.formatted(libraryPackage));
        final var libraryCompiler = CompilationService.forCompilationUnits(
                List.of(library), libraryPackage + ".Library", libraryPackage,
                List.of(), true, List.of(), libraryOutput);
        try {
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFrom(libraryOutput.resolve(
                    ZeronVersions.API_INDEX_RELATIVE_PATH));
            final var exportedType = index.declarations().stream()
                    .filter(ZeronLibraryIndex.NewtypeExport.class::isInstance)
                    .map(ZeronLibraryIndex.NewtypeExport.class::cast)
                    .filter(newtype -> newtype.qualifiedName().equals(libraryPackage + ".UserId"))
                    .findFirst().orElseThrow();
            assertTrue(exportedType.validated());
            assertTrue(index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .anyMatch(function -> function.qualifiedName().equals(libraryPackage + ".UserId.tryNew")));

            final var client = parse("client/Main.zn", """
                    package client;
                    import %s.UserId;
                    fn createsValue(): Boolean = match (UserId.tryNew("alice")) {
                        case Option.Some(_) -> true;
                        case Option.None() -> false;
                    };
                    """.formatted(libraryPackage));
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), "client.Main", "client",
                    List.of(index), true, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertTrue((Boolean) loader.loadClass("client.Main")
                        .getMethod("createsValue").invoke(null));
            }
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
        }
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
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
