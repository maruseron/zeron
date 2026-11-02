package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class EffectRowCompilationTest {
    @Test
    public void rowPolymorphicCallsRunAndPropagateRaisedEffects() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "effectRows" + suffix;
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-effect-rows-");
        final var unit = parse("Main.zn", """
                package %s;
                public effect Failure {
                    public property code: Int;
                }
                public fn apply<T, R, E: effect...>(
                    value: T,
                    transform: (T) -> R raises E
                ): R raises E = transform(value);
                fn pure(): Int = apply(1, value -> value + 1);
                fn handled(): Int = handle (apply(1, value -> raise Failure.new(7))) with {
                    case Failure.code(code) -> code;
                };
                fn propagated(): Int raises Failure = apply(1, value -> raise Failure.new(9));
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(2, main.getMethod("pure").invoke(null));
                assertEquals(7, main.getMethod("handled").invoke(null));
                try {
                    main.getMethod("propagated").invoke(null);
                    throw new AssertionError("The propagated row must escape as a raised effect.");
                } catch (final InvocationTargetException exception) {
                    final var carrier = exception.getCause();
                    assertEquals("zeron.runtime.RaisedEffect", carrier.getClass().getName());
                    final var payload = carrier.getClass().getMethod("payload").invoke(carrier);
                    assertEquals(packageName + ".Failure", payload.getClass().getName());
                    assertEquals(9, payload.getClass().getMethod("$zeron$get$code").invoke(payload));
                }
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rowMetadataSurvivesLibraryIndexRoundTrip() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "rowLibrary" + suffix;
        final var clientPackage = "rowClient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-row-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-row-client-");
        final var libraryUnit = parse("Library.zn", """
                package %s;
                public effect Failure {
                    public property code: Int;
                }
                public fn apply<T, R, E: effect...>(
                    value: T,
                    transform: (T) -> R raises E
                ): R raises E = transform(value);
                """.formatted(libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(List.of(libraryUnit),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(),
                    libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();

            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var apply = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(libraryPackage + ".apply"))
                    .findFirst().orElseThrow();

            final var typeParameters = apply.signature().typeParameters();
            assertEquals(3, typeParameters.size());
            final var rowParameter = typeParameters.get(2);
            assertTrue(rowParameter.isEffectRowKind());
            assertFalse(rowParameter.isEffectKind());

            final var transform = (FunctionDescriptor) apply.signature().parameters().get(1);
            assertEquals(1, transform.raisedEffects().size());
            final var raised = transform.raisedEffects().getFirst();
            assertTrue(raised instanceof TypeParameterDescriptor);
            final var raisedParameter = (TypeParameterDescriptor) raised;
            assertEquals("E", raisedParameter.name());
            assertTrue(raisedParameter.isEffectRowKind());

            final var clientUnit = parse("Client.zn", """
                    package %s;
                    import %s.Failure;
                    import %s.apply;
                    fn pure(): Int = apply(1, value -> value + 1);
                    fn effectful(): Int raises Failure = apply(1, value -> raise Failure.new(11));
                    """.formatted(clientPackage, libraryPackage, libraryPackage));
            final var clientCompiler = CompilationService.forCompilationUnits(List.of(clientUnit),
                    clientPackage + ".Client", clientPackage, List.of(index), true, List.of(),
                    clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var client = loader.loadClass(clientPackage + ".Client");
                assertEquals(2, client.getMethod("pure").invoke(null));
                try {
                    client.getMethod("effectful").invoke(null);
                    throw new AssertionError("The imported row must propagate the raised effect.");
                } catch (final InvocationTargetException exception) {
                    final var payload = exception.getCause().getClass()
                            .getMethod("payload").invoke(exception.getCause());
                    assertEquals(libraryPackage + ".Failure", payload.getClass().getName());
                    assertEquals(11, payload.getClass().getMethod("$zeron$get$code").invoke(payload));
                }
            }
        } finally {
            deleteTree(libraryOutput);
            deleteTree(clientOutput);
        }
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
