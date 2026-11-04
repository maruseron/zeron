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

public final class IterableDefaultsTest {
    private static final String SOURCE = """
            package app;

            import zeron.collections.*;

            fn result(): Int {
                let values = ArrayList<Int>.of(1, 2, 3);
                let empty = ArrayList<Int>.of();
                let folded = values.fold(0, (sum, value) -> sum + value);
                let streamed = Stream<Int>.of(4, 5).fold(0, (sum, value) -> sum + value);
                let counted = values.count() + Stream<Int>.of(1, 2, 3).count();
                let mut total = folded + streamed + counted;
                if (empty.isEmpty()) total += 10;
                if (values.isEmpty()) total += 1000;
                if (values.any(value -> value > 2)) total += 100;
                if (Stream<Int>.of(1, 2, 3).all(value -> value > 0)) total += 1000;
                return total;
            }
            """;

    @Test
    public void iterableTerminalDefaultsWorkAcrossListsAndStreams() throws Exception {
        final var output = Files.createTempDirectory("zeron-iterable-defaults-");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(parse(SOURCE)), "app.Main", "app", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(1131, loader.loadClass("app.Main").getMethod("result").invoke(null));
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
