package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class ValueBlockTest {
    @Test
    public void valueBlocksProduceTheirFinalYieldAndKeepStatementBlocksUnchanged() throws Exception {
        final var output = Files.createTempDirectory("zeron-value-block-");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(parse("""
                            package app;

                            fn calculate(base: Int): Int {
                                let scaled = {
                                    let doubled = base * 2;
                                    yield doubled + 1;
                                };
                                return scaled;
                            }

                            fn nested(): Int = {
                                let inner = {
                                    yield 4;
                                };
                                yield inner * 3;
                            };

                            fn branch(flag: Boolean): Int =
                                if (flag) then { yield 10; } else { yield 20; };
                            """)), "app.Main", "app", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass("app.Main");
                assertEquals(9, main.getMethod("calculate", int.class).invoke(null, 4));
                assertEquals(12, main.getMethod("nested").invoke(null));
                assertEquals(10, main.getMethod("branch", boolean.class).invoke(null, true));
                assertEquals(20, main.getMethod("branch", boolean.class).invoke(null, false));
            }
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
