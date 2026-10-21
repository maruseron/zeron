package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ResolutionService;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.TypeFormatter;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class TypedHandlerTest {
    @Test
    public void namedAndAnonymousHandlersWorkAcrossLibraryBoundary() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "handlerlib" + suffix;
        final var clientPackage = "handlerclient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-handler-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-handler-client-");
        final var libraryUnit = parse("Library.zn", """
                package %s;
                public effect Failure {
                    public property message: String;
                }
                public handler catchFailure {
                    case Failure as failure -> failure.message;
                }
                """.formatted(libraryPackage));
        final var clientUnit = parse("Main.zn", """
                package %s;
                import %s.Failure;
                import %s.catchFailure;
                fn apply(handler: String ~> {Failure}): String =
                    handle (raise Failure.new("passed")) with handler;
                fn named(): String =
                    handle (raise Failure.new("named")) with catchFailure;
                fn anonymous(): String = apply(handler {
                    case Failure.message(message) -> message;
                });
                fn captured(): String {
                    let suffix = "!";
                    let handler = handler {
                        case Failure.message(message) -> message + suffix;
                    };
                    return handle (raise Failure.new("captured")) with handler;
                }
                effect Audit {}
                fn propagated(): String raises Audit = handle (
                    raise Failure.new("audit")
                ) with handler {
                    case Failure as failure ->
                        if (false) then failure.message else raise Audit.new();
                };
                fn partial(): String raises Audit = handle (
                    raise Audit.new()
                ) with handler {
                    case Failure -> "unused";
                };
                fn guarded(): String raises Failure = handle (
                    raise Failure.new("guarded")
                ) with handler {
                    case Failure.message(message) if false -> message;
                };
                """.formatted(clientPackage, libraryPackage, libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(List.of(libraryUnit),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var exportedHandler = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ValueExport.class::isInstance)
                    .map(ZeronLibraryIndex.ValueExport.class::cast)
                    .filter(value -> value.qualifiedName().equals(libraryPackage + ".catchFailure"))
                    .findFirst().orElseThrow();
            assertTrue(exportedHandler.type() instanceof FunctionDescriptor function && function.isHandler());
            assertEquals("String ~> {" + libraryPackage + ".Failure}",
                    TypeFormatter.format(exportedHandler.type()));

            final var clientCompiler = CompilationService.forCompilationUnits(List.of(clientUnit),
                    clientPackage + ".Main", clientPackage, List.of(index), true, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(clientPackage + ".Main");
                assertEquals("named", main.getMethod("named").invoke(null));
                assertEquals("passed", main.getMethod("anonymous").invoke(null));
                assertEquals("captured!", main.getMethod("captured").invoke(null));
                assertRaisedEffect(main, "propagated", clientPackage + ".Audit");
                assertRaisedEffect(main, "partial", clientPackage + ".Audit");
                assertRaisedEffect(main, "guarded", libraryPackage + ".Failure");
            }
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
        }
    }

    @Test
    public void handlerTypeRequiresExactShape() {
        final var packageName = "invalidhandler" + UUID.randomUUID().toString().replace("-", "");
        final var source = parse("Invalid.zn", """
                package %s;
                effect Failure {}
                effect Other {}
                fn apply(handler: String ~> {Failure}): String =
                    handle (raise Failure.new()) with handler;
                fn mismatch(): String = apply(handler {
                    case Other -> "wrong shape";
                });
                """.formatted(packageName));
        assertTrue(new ResolutionService().resolveUnitsWithDiagnostics(List.of(source)).errors().stream()
                .anyMatch(error -> error.message().contains("Expected String ~>")));
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnit(sourcePath);
    }

    private static void assertRaisedEffect(final Class<?> main, final String method,
                                           final String expectedPayloadClass) throws Exception {
        try {
            final var target = java.util.Arrays.stream(main.getMethods())
                    .filter(candidate -> candidate.getName().equals(method))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing method " + method + ": "
                            + java.util.Arrays.toString(main.getMethods())));
            target.invoke(null);
            throw new AssertionError("Expected a raised typed effect.");
        } catch (final java.lang.reflect.InvocationTargetException exception) {
            final var carrier = exception.getCause();
            assertEquals("zeron.runtime.RaisedEffect", carrier.getClass().getName());
            assertEquals(expectedPayloadClass,
                    carrier.getClass().getMethod("payload").invoke(carrier).getClass().getName());
        }
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
