package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DataClassTest {
    @Test
    public void constructsComponentsAndDerivesStructuralEqAndHashable() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var programName = "DataClassProgram" + suffix;
        final var className = "Point" + suffix;
        final var source = """
                import zeron.lang.Eq;
                import zeron.lang.Hashable;
                public data class %s(x: Int, y: Int) deriving Hashable {
                    total: Int = sum(x, y);
                    public squaredDistance(): Int = this.x * this.x + this.y * this.y;
                    public getTotal(): Int = total;
                    public mut setTotal(value: Int): Unit {
                        this.total = value;
                    }
                }
                fn sum(x: Int, y: Int): Int = x + y;
                fn same<T: mut + Eq<T>>(left: T, right: T): Boolean = left == right;
                fn hash<T: mut + Hashable<T>>(value: T): Long = value.hash();
                fn components(): Int = %s.new(3, 4).squaredDistance();
                fn auxiliaryInitializer(): Int = %s.new(3, 4).getTotal();
                fn equal(): Boolean = same(%s.new(3, 4), %s.new(3, 4));
                fn unequal(): Boolean = same(%s.new(3, 4), %s.new(4, 3));
                fn auxiliaryStateIgnored(): Boolean {
                    let mut left = %s.new(3, 4);
                    let mut right = %s.new(3, 4);
                    left.setTotal(99);
                    return same(left, right);
                }
                fn hashed(): Long = hash(%s.new(3, 4));
                """.formatted(className, className, className, className, className,
                className, className, className, className, className);
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals(25, program.getMethod("components").invoke(null));
                assertEquals(7, program.getMethod("auxiliaryInitializer").invoke(null));
                assertTrue((Boolean) program.getMethod("equal").invoke(null));
                assertFalse((Boolean) program.getMethod("unequal").invoke(null));
                assertTrue((Boolean) program.getMethod("auxiliaryStateIgnored").invoke(null));
                assertTrue((Long) program.getMethod("hashed").invoke(null) != 0L);
                final var dataClass = loader.loadClass(className);
                assertTrue(java.lang.reflect.Modifier.isFinal(dataClass.getDeclaredField("x").getModifiers()));
                assertTrue(java.lang.reflect.Modifier.isFinal(dataClass.getDeclaredField("y").getModifiers()));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", className + ".class"));
        }
    }

    @Test
    public void initializesAuxiliaryFieldsInInitializerBlocks() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var programName = "DataInitializerProgram" + suffix;
        final var className = "Initialized" + suffix;
        final var source = """
                data class %s(value: Int) {
                    doubled: Int;
                    %s {
                        doubled = value * 2;
                    }
                    public getDoubled(): Int = doubled;
                }
                fn result(): Int = %s.new(9).getDoubled();
                """.formatted(className, className, className);
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(18, loader.loadClass(programName).getMethod("result").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", className + ".class"));
        }
    }

    @Test
    public void evaluatesDirectInitializersInDeclarationOrder() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var programName = "DataInitializerOrder" + suffix;
        final var className = "Ordered" + suffix;
        final var source = """
                data class %s(value: Int) {
                    first: Int = value + 1;
                    second: Int = first + 1;
                    third: Int = second + 1;
                    public result(): Int = third;
                }
                fn result(): Int = %s.new(4).result();
                """.formatted(className, className);
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(7, loader.loadClass(programName).getMethod("result").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", className + ".class"));
        }
    }

    @Test
    public void rejectsAuxiliaryFieldsThatAreNotInitialized() {
        final var source = """
                data class MissingInitialization(value: Int) {
                    auxiliary: Int;
                }
                """;
        org.junit.Assert.assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolve(parse(source)));
    }

    @Test
    public void supportsConditionalEqualityForGenericDataClasses() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var programName = "GenericDataProgram" + suffix;
        final var className = "Box" + suffix;
        final var source = """
                import zeron.lang.Eq;
                public data class %s<T>(value: T) {}
                fn result(): Boolean = %s<Int>.new(12) == %s<Int>.new(12);
                """.formatted(className, className, className);
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertTrue((Boolean) loader.loadClass(programName).getMethod("result").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", className + ".class"));
        }
    }

    @Test
    public void propagatesAndHandlesInitializerBlockEffects() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var programName = "EffectfulDataConstruction" + suffix;
        final var className = "Source" + suffix;
        final var effectName = "InitializationFailure" + suffix;
        final var effectful = parse("""
                effect %s {}
                data class %s(value: Int) {
                    %s raises %s {
                        raise %s.new();
                    }
                }
                fn create(): %s raises %s = %s.new(1);
                fn handled(): Any = handle (%s.new(1)) with {
                    case %s -> true;
                };
                """.formatted(effectName, className, className, effectName, effectName,
                className, effectName, className, className, effectName));
        final var declaration = (Stmt.ClassDecl) effectful.stream()
                .filter(Stmt.ClassDecl.class::isInstance).map(Stmt.ClassDecl.class::cast)
                .filter(classDeclaration -> !classDeclaration.isEffect()).findFirst().orElseThrow();
        assertEquals(1, declaration.constructorEffects().size());
        final var effectfulCompiler = new CompilationService(effectful, programName);
        try {
            effectfulCompiler.resolve();
            effectfulCompiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertTrue((Boolean) loader.loadClass(programName).getMethod("handled").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", className + ".class"));
            Files.deleteIfExists(Path.of("dist", effectName + ".class"));
        }
        final var create = (Stmt.Function) effectful.stream()
                .filter(Stmt.Function.class::isInstance).findFirst().orElseThrow();
        final var createCall = (Expr.MemberCall) ((Stmt.Return) create.body().getFirst()).value();
        assertEquals(1, createCall.resolvedDescriptor().raisedEffects().size());

        final var unhandled = parse("""
                effect %s {}
                data class %s(value: Int) {
                    %s raises %s {}
                }
                fn create(): %s = %s.new(1);
                """.formatted(effectName, className, className, effectName, className, className));
        org.junit.Assert.assertThrows(ResolutionError.class,
                () -> new CompilationService(unhandled, "UnhandledDataConstruction" + suffix).resolve());
    }

    @Test
    public void exportsDataClassShapeAndConstructorEffectsForSeparateCompilation() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "dataclasslib" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "data-class-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "data-class-client-");
        try {
            final var library = parseUnit("library/Source.zn", """
                    package %s;
                    public effect InitializationFailure {}
                    public data class Source(value: Int) {
                        Source raises InitializationFailure {}
                    }
                    """.formatted(libraryPackage));
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(library), libraryPackage + ".Library", libraryPackage,
                    List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFrom(libraryOutput.resolve(
                    com.maruseron.zeron.ZeronVersions.API_INDEX_RELATIVE_PATH));
            final var sourceExport = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ClassExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(libraryPackage + ".Source"))
                    .findFirst().orElseThrow();
            assertTrue(sourceExport.dataClass());
            assertEquals(1, sourceExport.canonicalConstructorEffects().size());

            final var client = parseUnit("client/Main.zn", """
                    package client;
                    import %s.Source;
                    import %s.InitializationFailure;
                    import zeron.lang.Eq;
                    fn create(): Source raises InitializationFailure = Source.new(1);
                    fn equal(): Boolean raises InitializationFailure =
                        Source.new(1) == Source.new(1);
                    """.formatted(libraryPackage, libraryPackage));
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), "client.Main", "client", List.of(index), true, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertTrue((Boolean) loader.loadClass("client.Main").getMethod("equal").invoke(null));
            }

            final var invalidClient = parseUnit("client/Invalid.zn", """
                    package client;
                    import %s.Source;
                    fn create(): Source = Source.new(1);
                    """.formatted(libraryPackage));
            final var invalidCompiler = CompilationService.forCompilationUnits(
                    List.of(invalidClient), "client.Invalid", "client", List.of(index), true, List.of(), clientOutput);
            org.junit.Assert.assertThrows(ResolutionError.class, invalidCompiler::resolve);
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
        }
    }

    @Test
    public void readsComponentsAndDestructuresDataClassesPositionally() throws Exception {
        final var source = """
                data class Point(x: Int, y: Int) {}
                data class Segment(start: Point, end: Point) {}

                fn lengthSquared(point: Point): Int =
                    point.x * point.x + point.y * point.y;

                fn classify(point: Point): String = match (point) {
                    case Point(0, 0) -> "origin";
                    case Point(0, y) -> "y " + y;
                    case Point(x, y) -> "point " + x + " " + y;
                };

                fn segmentLengthSquared(segment: Segment): Int = match (segment) {
                    case Segment(Point(x1, y1), Point(x2, y2)) ->
                        (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1);
                };

                fn classifyOrigin(): String = classify(Point.new(0, 0));
                fn classifyAxis(): String = classify(Point.new(0, 5));
                fn classifyPoint(): String = classify(Point.new(2, 3));
                fn length(): Int = lengthSquared(Point.new(3, 4));
                fn segment(): Int =
                    segmentLengthSquared(Segment.new(Point.new(1, 1), Point.new(4, 5)));
                """;
        final var programName = "DataPatternProgram" + UUID.randomUUID().toString().replace("-", "");
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals(25, program.getMethod("length").invoke(null));
                assertEquals("origin", program.getMethod("classifyOrigin").invoke(null));
                assertEquals("y 5", program.getMethod("classifyAxis").invoke(null));
                assertEquals("point 2 3", program.getMethod("classifyPoint").invoke(null));
                assertEquals(25, program.getMethod("segment").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void genericDataClassPatternsInferTypeArgumentsAndIgnoreAuxiliaryProperties()
            throws Exception {
        final var source = """
                data class Box<A: mut>(value: A) {
                    public property doubled: A {
                        get = value;
                    }
                }

                fn inferred(box: Box<Int>): Int = match (box) {
                    case Box(x) -> x;
                };

                fn explicit(box: Box<Int>): String = match (box) {
                    case Box<Int>(0) -> "zero";
                    case Box<Int>(1) -> "one";
                    case Box<Int>(other) -> "other " + other;
                };

                fn auxiliary(box: Box<Int>): Int = box.doubled;
                fn inferredValue(): Int = inferred(Box<Int>.new(7));
                fn zeroValue(): String = explicit(Box<Int>.new(0));
                fn oneValue(): String = explicit(Box<Int>.new(1));
                fn otherValue(): String = explicit(Box<Int>.new(9));
                fn auxiliaryValue(): Int = auxiliary(Box<Int>.new(11));
                """;
        final var programName = "GenericDataPatternProgram" + UUID.randomUUID().toString().replace("-", "");
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals(7, program.getMethod("inferredValue").invoke(null));
                assertEquals("zero", program.getMethod("zeroValue").invoke(null));
                assertEquals("one", program.getMethod("oneValue").invoke(null));
                assertEquals("other 9", program.getMethod("otherValue").invoke(null));
                assertEquals(11, program.getMethod("auxiliaryValue").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void rejectsWrongDataClassPatternArityAndComponentTypes() {
        final var arity = """
                data class Point(x: Int, y: Int) {}
                fn bad(point: Point): Int = match (point) {
                    case Point(x) -> x;
                };
                """;
        final var arityError = org.junit.Assert.assertThrows(ResolutionError.class,
                () -> new CompilationService(parse(arity), "BadDataArity").resolve());
        assertTrue(arityError.getMessage().contains("expects 2 component patterns"));

        final var componentType = """
                data class Point(x: Int, y: Int) {}
                fn bad(point: Point): Int = match (point) {
                    case Point("x", y) -> y;
                };
                """;
        org.junit.Assert.assertThrows(ResolutionError.class,
                () -> new CompilationService(parse(componentType), "BadDataComponent").resolve());
    }

    private static List<com.maruseron.zeron.ast.Stmt> parse(final String source) {
        final var result = Parser.of(Scanner.from(source, "DataClassTest.zn").scanTokens())
                .parseCompilationUnitWithDiagnostics("DataClassTest.zn");
        if (!result.diagnostics().isEmpty()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.compilationUnit().declarations();
    }

    private static CompilationUnit parseUnit(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens()).parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
