package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ResolutionService;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class EffectSubtypingTest {
    @Test
    public void subeffectsSatisfyRaisesAndHandlersUseInheritedPayload() throws Exception {
        final var packageName = "effectSubtyping" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-effect-subtyping-");
        final var unit = parse("Main.zn", """
                package %s;
                import java.lang.Exception;
                public effect IOError {
                    public property path: String;
                }
                public effect Retryable {}
                public effect FileNotFound under IOError, Retryable {
                    public property path: String;
                    public property code: Int;
                }
                public effect Parent {
                    public property name: String;
                }
                public effect Child under Parent {
                    public property name: String;
                    public property code: Int;
                }
                fn failFile(): Int raises FileNotFound = raise FileNotFound.new("boom", 42);
                fn broad(): Int raises IOError = failFile();
                fn inheritedView(): String = handle (raise FileNotFound.new("boom", 42)) with {
                    case IOError as error -> error.path;
                };
                fn stateless(): Int = handle (raise Retryable.new()) with {
                    case Retryable -> 5;
                };
                fn mostSpecific(): Int = handle (raise Child.new("child", 3)) with {
                    case Parent -> 1;
                    case Child as child -> child.code;
                };
                fn catchAll(): String = handle (raise Child.new("child", 3)) with {
                    case Effect -> "caught";
                };
                fn nested(): Int = handle (
                    handle (raise Child.new("child", 3)) with {
                        case Parent -> 9;
                    }
                ) with {
                    case Parent -> 1;
                };
                public effect MutableBase {
                    public mut property label: String;
                }
                public effect MutableChild under MutableBase {
                    public mut property label: String;
                }
                fn rename(value: &MutableBase): String {
                    value.label = "renamed";
                    return value.label;
                }
                fn mutateThroughView(): String = handle (raise MutableChild.new("initial")) with {
                    case MutableBase as value -> rename(value);
                };
                fn widenedFunction(): () -> Int raises IOError = failFile;
                fn asEffect(value: Effect): Effect = value;
                fn wrapHostFailure(): String = handle (
                    raise Effect.fromException(Exception.new("host"))
                ) with {
                    case Effect -> "wrapped";
                };
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals("boom", main.getMethod("inheritedView").invoke(null));
                assertEquals(5, main.getMethod("stateless").invoke(null));
                assertEquals(3, main.getMethod("mostSpecific").invoke(null));
                assertEquals("caught", main.getMethod("catchAll").invoke(null));
                assertEquals(9, main.getMethod("nested").invoke(null));
                assertEquals("renamed", main.getMethod("mutateThroughView").invoke(null));
                assertEquals("wrapped", main.getMethod("wrapHostFailure").invoke(null));
                try {
                    main.getMethod("broad").invoke(null);
                    throw new AssertionError("An unhandled FileNotFound must escape the boundary.");
                } catch (final InvocationTargetException exception) {
                    final var carrier = exception.getCause();
                    assertEquals("zeron.runtime.RaisedEffect", carrier.getClass().getName());
                    assertEquals(packageName + ".FileNotFound",
                            carrier.getClass().getMethod("payload").invoke(carrier).getClass().getName());
                }
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void invalidEffectHierarchiesAndAmbiguousCasesAreRejected() {
        assertDiagnostic("""
                effect Base { property name: String; }
                effect Missing under Base {}
                """, "must redeclare inherited payload member `name`");
        assertDiagnostic("""
                effect Left { property left: Int; }
                effect Right { property right: Int; }
                effect Conflicting under Left, Right {
                    property left: Int;
                    property right: Int;
                }
                """, "more than one payload-bearing supertype");
        assertDiagnostic("""
                effect First under Second {}
                effect Second under First {}
                """, "form a cycle");
        assertDiagnostic("""
                class Ordinary {}
                effect NotAnEffect under Ordinary {}
                """, "is not an effect type");
        assertDiagnostic("""
                effect First {}
                effect Second {}
                effect Both under First, Second {}
                fn ambiguous(): Int = handle (raise Both.new()) with {
                    case First -> 1;
                    case Second -> 2;
                };
                """, "Ambiguous handler cases");
    }

    @Test
    public void separatelyCompiledLibrariesPreserveUnderEdgesAndPayload() throws Exception {
        final var suffix = suffix();
        final var libraryPackage = "effectLibrary" + suffix;
        final var appPackage = "effectLibraryClient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-effect-under-library-");
        final var appOutput = Files.createTempDirectory(Path.of("target"), "zeron-effect-under-client-");
        final var library = parse("Library.zn", """
                package %s;
                public effect Parent {
                    public property name: String;
                }
                public effect Child under Parent {
                    public property name: String;
                    public property code: Int;
                }
                public fn fail(): Int raises Child = raise Child.new("library", 8);
                """.formatted(libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(List.of(library),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();

            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var child = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ClassExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(libraryPackage + ".Child"))
                    .findFirst().orElseThrow();
            assertEquals(List.of(libraryPackage + ".Parent"), child.underEffects());

            final var app = parse("Main.zn", """
                    package %s;
                    import %s.Parent;
                    import %s.fail;
                    fn broaden(): Int raises Parent = fail();
                    fn handled(): Int = handle (fail()) with {
                        case Parent -> 1;
                    };
                    """.formatted(appPackage, libraryPackage, libraryPackage));
            final var appCompiler = CompilationService.forCompilationUnits(List.of(app),
                    appPackage + ".Main", appPackage, List.of(index), true, List.of(), appOutput);
            appCompiler.resolve();
            appCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    appOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(appPackage + ".Main");
                assertEquals(1, main.getMethod("handled").invoke(null));
            }
        } finally {
            deleteTree(libraryOutput);
            deleteTree(appOutput);
        }
    }

    @Test
    public void lateLibraryAmbiguityAbortsDispatchInsteadOfChoosingBySourceOrder() throws Exception {
        final var suffix = suffix();
        final var libraryPackage = "ambiguousLibrary" + suffix;
        final var appPackage = "ambiguousClient" + suffix;
        final var libraryV1 = Files.createTempDirectory(Path.of("target"), "zeron-ambiguity-v1-");
        final var libraryV2 = Files.createTempDirectory(Path.of("target"), "zeron-ambiguity-v2-");
        final var appOutput = Files.createTempDirectory(Path.of("target"), "zeron-ambiguity-app-");
        final var firstVersion = parse("Library.zn", """
                package %s;
                public effect First {}
                public effect Second {}
                public fn make(): Effect raises Effect = raise First.new();
                """.formatted(libraryPackage));
        final var secondVersion = parse("Library.zn", """
                package %s;
                public effect First {}
                public effect Second {}
                public effect Both under First, Second {}
                public fn make(): Effect raises Effect = raise Both.new();
                """.formatted(libraryPackage));
        try {
            final var v1Compiler = CompilationService.forCompilationUnits(List.of(firstVersion),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(), libraryV1);
            v1Compiler.resolve();
            v1Compiler.compile();
            final var index = ZeronLibraryIndex.readFromDirectory(libraryV1);

            final var app = parse("Main.zn", """
                    package %s;
                    import %s.First;
                    import %s.Second;
                    import %s.make;
                    fn dispatch(): Int raises Effect = handle (raise make()) with {
                        case First -> 1;
                        case Second -> 2;
                    };
                    """.formatted(appPackage, libraryPackage, libraryPackage, libraryPackage));
            final var appCompiler = CompilationService.forCompilationUnits(List.of(app),
                    appPackage + ".Main", appPackage, List.of(index), true, List.of(), appOutput);
            appCompiler.resolve();
            appCompiler.compile();

            final var v2Compiler = CompilationService.forCompilationUnits(List.of(secondVersion),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(), libraryV2);
            v2Compiler.resolve();
            v2Compiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    appOutput.toUri().toURL(), libraryV2.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(appPackage + ".Main");
                try {
                    main.getMethod("dispatch").invoke(null);
                    throw new AssertionError("An incomparable late subtype must abort dispatch.");
                } catch (final InvocationTargetException exception) {
                    final var cause = exception.getCause();
                    assertEquals(IllegalStateException.class, cause.getClass());
                    assertTrue(cause.getMessage().contains("Ambiguous effect handler"));
                    assertTrue(cause.getMessage().contains(libraryPackage + ".First"));
                    assertTrue(cause.getMessage().contains(libraryPackage + ".Second"));
                }
            }
        } finally {
            deleteTree(libraryV1);
            deleteTree(libraryV2);
            deleteTree(appOutput);
        }
    }

    private static void assertDiagnostic(final String source, final String expectedMessage) {
        final var unit = parse("Diagnostic.zn", source);
        final var errors = new ResolutionService().resolveUnitsWithDiagnostics(List.of(unit)).errors();
        assertTrue("Expected a diagnostic containing: " + expectedMessage + " but got " + errors,
                errors.stream().anyMatch(error -> error.message().contains(expectedMessage)));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
