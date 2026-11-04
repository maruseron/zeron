package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class GenericCallbackInferenceTest {
    private static final String BROAD_CALLBACK = """
            package app;

            import zeron.io.println;

            fn apply<E: effect...>(callback: (Int) -> Unit raises E): Unit raises E {
                callback(1);
            }

            fn throughReference(): Unit = apply(println);

            fn throughValue(): Unit {
                let printer: (Any?) -> Unit = println;
                apply(printer);
            }

            fn result(): Int = 7;
            """;

    private static final String NARROW_CALLBACK = """
            package app;

            fn apply<E: effect...>(callback: (Int) -> Unit raises E): Unit raises E {
                callback(1);
            }

            fn broken(): Unit = apply((text: String) -> ());
            """;

    @Test
    public void genericMethodsAcceptBroadlyAcceptingCallbacks() throws Exception {
        final var output = Files.createTempDirectory("zeron-generic-callback-");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(parse(BROAD_CALLBACK)), "app.Main", "app", List.of(), true,
                    List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(7, loader.loadClass("app.Main").getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsCallbacksThatCannotAcceptTheExpectedArgument() throws Exception {
        final var output = Files.createTempDirectory("zeron-generic-callback-reject-");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(parse(NARROW_CALLBACK)), "app.Main", "app", List.of(), true,
                    List.of(), output);
            assertThrows(ResolutionError.class, compiler::resolve);
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final String source) {
        final var result = Parser.of(Scanner.from(source, "Main.zn").scanTokens())
                .parseCompilationUnitWithDiagnostics("Main.zn");
        if (!result.diagnostics().isEmpty()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.compilationUnit();
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
