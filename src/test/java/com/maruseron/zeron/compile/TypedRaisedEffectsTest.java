package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ResolutionService;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TypedRaisedEffectsTest {
    @Test
    public void effectCarriersAreCaughtAndCompiledLibrariesRetainRaisedSets() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "effects" + suffix;
        final var appPackage = "effectClient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-effects-library-");
        final var appOutput = Files.createTempDirectory(Path.of("target"), "zeron-effects-client-");
        final var libraryUnit = parse("Library.zn", """
                package %s;
                public effect Failure {
                    public property message: String;
                }
                public effect Startup {
                    public property message: String;
                }
                public class Worker {
                    public work(): Int raises Failure = raise Failure.new("method");
                    public constructor start() raises Startup = raise Startup.new("constructor");
                }
                public fn fail(): String raises Failure = raise Failure.new("library");
                public fn callback(value: Int): Int raises Failure = raise Failure.new("callback");
                """.formatted(libraryPackage));

        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(List.of(libraryUnit),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();

            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var failure = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ClassExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(libraryPackage + ".Failure"))
                    .findFirst().orElseThrow();
            assertTrue(failure.effect());
            final var failExport = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(libraryPackage + ".fail"))
                    .findFirst().orElseThrow();
            assertEquals(List.of(com.maruseron.zeron.domain.TypeDescriptor.ofName(
                    libraryPackage + ".Failure")), failExport.signature().raisedEffects());

            final var appUnit = parse("Main.zn", """
                    package %s;
                    import %s.Failure;
                    import %s.Startup;
                    import %s.Worker;
                    import %s.fail;
                    import %s.callback;
                    fn handled(): String = handle (fail()) with {
                        case Failure.message(message) -> message;
                    };
                    fn captured(): () -> String = handle (raise Failure.new("captured")) with {
                        case Failure as error -> () -> error.message;
                    };
                    fn work(): Int raises Failure = Worker.new().work();
                    fn start(): Worker raises Startup = Worker.start();
                    fn callbackType(): (Int) -> Int raises Failure = callback;
                    class Details {
                        public property first: String;
                        public property second: String;
                        public pattern pair(left: String, right: String) {
                            left = this.first;
                            right = this.second;
                        }
                    }
                    effect StructuredFailure {
                        public property details: Details;
                    }
                    fn structured(): String = handle (
                        raise StructuredFailure.new(Details.new("nested", "pattern"))
                    ) with {
                        case StructuredFailure.details(Details.pair(left, right))
                            if left == "skip" -> "guarded";
                        case StructuredFailure as failure -> failure.details.first + failure.details.second;
                    };
                    fn alternatives(): String = handle (
                        raise StructuredFailure.new(Details.new("a", "b"))
                    ) with {
                        case StructuredFailure.details(Details.pair(left, right))
                            | StructuredFailure.details(Details.pair(left, right)) -> left + right;
                    };
                    fn guardedPropagation(): String raises StructuredFailure = handle (
                        raise StructuredFailure.new(Details.new("a", "b"))
                    ) with {
                        case StructuredFailure.details(Details.pair(left, right))
                            if left == "skip" -> "handled";
                    };
                    fn javaException(): Int = handle (1 / 0) with {
                        case Failure -> 1;
                    };
                    """.formatted(appPackage, libraryPackage, libraryPackage, libraryPackage,
                    libraryPackage, libraryPackage));
            final var appCompiler = CompilationService.forCompilationUnits(List.of(appUnit),
                    appPackage + ".Main", appPackage, List.of(index), true, List.of(), appOutput);
            appCompiler.resolve();
            appCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    appOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(appPackage + ".Main");
                assertEquals("library", main.getMethod("handled").invoke(null));
                assertEquals("nestedpattern", main.getMethod("structured").invoke(null));
                assertEquals("ab", main.getMethod("alternatives").invoke(null));
                final var captured = main.getMethod("captured").invoke(null);
                assertEquals("captured", captured.getClass().getInterfaces()[0]
                        .getMethod("invoke").invoke(captured));
                try {
                    main.getMethod("guardedPropagation").invoke(null);
                    throw new AssertionError("A failed guarded pattern must propagate the effect.");
                } catch (final InvocationTargetException exception) {
                    final var carrier = exception.getCause();
                    assertEquals("zeron.runtime.RaisedEffect", carrier.getClass().getName());
                    assertEquals(appPackage + ".StructuredFailure",
                            carrier.getClass().getMethod("payload").invoke(carrier).getClass().getName());
                }
                try {
                    main.getMethod("javaException").invoke(null);
                    throw new AssertionError("Effect handlers must not catch Java exceptions.");
                } catch (final InvocationTargetException exception) {
                    assertEquals(ArithmeticException.class, exception.getCause().getClass());
                }
                try {
                    main.getMethod("start").invoke(null);
                    throw new AssertionError("Unmatched effects must be rethrown.");
                } catch (final InvocationTargetException exception) {
                    final var carrier = exception.getCause();
                    assertEquals("zeron.runtime.RaisedEffect", carrier.getClass().getName());
                    assertEquals(libraryPackage + ".Startup",
                            carrier.getClass().getMethod("payload").invoke(carrier).getClass().getName());
                }
            }

            final var unhandled = parse("Invalid.zn", """
                    package %s;
                    import %s.fail;
                    fn missingDeclaration(): String = fail();
                    """.formatted(appPackage, libraryPackage));
            final var failedResolution = new ResolutionService().resolveUnitsWithDiagnostics(
                    List.of(unhandled, index.toCompilationUnits("library-index").getFirst()));
            assertFalse(failedResolution.errors().isEmpty());
            assertTrue(failedResolution.errors().stream()
                    .anyMatch(error -> error.message().contains("Undeclared raised effects")));
        } finally {
            deleteTree(libraryOutput);
            deleteTree(appOutput);
        }
    }

    @Test
    public void extensionRaisedClausesParseIntoTheirFunctionTypes() {
        final var unit = parse("Extension.zn", """
                effect Failure { public property message: String; }
                class Worker {}
                extension Worker {
                    fn run(): Int raises Failure = raise Failure.new("extension");
                }
                """);
        final var namespace = (com.maruseron.zeron.ast.Stmt.Namespace) unit.declarations().get(2);
        final var extension = (com.maruseron.zeron.ast.Stmt.ExtensionMethod) namespace.members().getFirst();
        assertEquals(List.of(com.maruseron.zeron.domain.TypeDescriptor.ofName("Failure")),
                extension.typeDescriptor().raisedEffects());
    }

    @Test
    public void entryPointMayDeclareEffectsButTopLevelInitializersCannotRaiseThem() {
        final var entryPoint = parse("Main.zn", """
                effect Failure {}
                fn main(): Unit raises Failure = raise Failure.new();
                """);
        final var entryPointResult = new ResolutionService().resolveUnitsWithDiagnostics(List.of(entryPoint));
        assertTrue(entryPointResult.errors().isEmpty());

        final var initializer = parse("Initializer.zn", """
                effect Failure {}
                let failure: Failure = raise Failure.new();
                """);
        final var initializerResult = new ResolutionService().resolveUnitsWithDiagnostics(List.of(initializer));
        assertTrue(initializerResult.errors().stream()
                .anyMatch(error -> error.message().contains("top-level initializers")));

        final var armEffects = parse("ArmEffects.zn", """
                effect Original {}
                effect FromHandler {}
                fn original(): Int raises Original = raise Original.new();
                fn handlesAndDeclares(): Int raises FromHandler = handle (original()) with {
                    case Original -> raise FromHandler.new();
                };
                fn handlesButOmitsArmEffect(): Int = handle (original()) with {
                    case Original -> raise FromHandler.new();
                };
                fn guardedDoesNotHandle(): Int = handle (original()) with {
                    case Original if true -> 1;
                };
                """);
        final var armEffectResult = new ResolutionService().resolveUnitsWithDiagnostics(List.of(armEffects));
        assertTrue(armEffectResult.errors().stream()
                .anyMatch(error -> error.message().contains("Undeclared raised effects")
                        && error.message().contains("FromHandler")));
        assertTrue(armEffectResult.errors().stream()
                .anyMatch(error -> error.message().contains("Undeclared raised effects")
                        && error.message().contains("Original")));
    }

    @Test
    public void unhandledMainEffectEscapesAsTheRaisedEffectCarrier() throws Exception {
        final var packageName = "effectBoundary" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-effects-boundary-");
        final var unit = parse("Main.zn", """
                package %s;
                public effect Startup {}
                fn main(): Unit raises Startup = raise Startup.new();
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                try {
                    loader.loadClass(packageName + ".Main").getMethod("main").invoke(null);
                    throw new AssertionError("The host boundary must not swallow an unhandled effect.");
                } catch (final InvocationTargetException exception) {
                    final var carrier = exception.getCause();
                    assertEquals("zeron.runtime.RaisedEffect", carrier.getClass().getName());
                    assertEquals(packageName + ".Startup",
                            carrier.getClass().getMethod("payload").invoke(carrier).getClass().getName());
                }
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void genericEffectParametersPropagateAndValidateRuntimeValues() throws Exception {
        final var packageName = "genericEffect" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-generic-effects-");
        final var unit = parse("Main.zn", """
                package %s;
                public effect Failure {}
                fn relay<E: effect>(value: E): Int raises E = raise value;
                fn handled(): Int = handle (relay(Failure.new())) with {
                    case Failure -> 7;
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
                assertEquals(7, main.getMethod("handled").invoke(null));
                try {
                    main.getMethod("relay", Object.class).invoke(null, "not an effect");
                    throw new AssertionError("The generic effect boundary must reject non-effect values.");
                } catch (final InvocationTargetException exception) {
                    assertEquals(ClassCastException.class, exception.getCause().getClass());
                }
            }
        } finally {
            deleteTree(output);
        }

        final var unresolvedCase = parse("GenericHandler.zn", """
                fn invalid<E: effect>(value: E): Int = handle (raise value) with {
                    case E -> 1;
                };
                """);
        assertTrue(new ResolutionService().resolveUnitsWithDiagnostics(List.of(unresolvedCase)).errors().stream()
                .anyMatch(error -> error.message().contains("concrete effect type")));
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
