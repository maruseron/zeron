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

public final class CollectionContractsTest {
    private static final String SOURCE = """
            package app;

            import zeron.collections.*;
            import zeron.lang.Option;

            fn total(values: List<Int>): Int {
                let mut sum = 0;
                for (let value in values) sum = sum + value;
                return sum;
            }

            fn sizeOf(values: Sized<Int>): Int = values.size;

            fn lookup(values: Map<String, Int>, key: String): Int {
                return match (values.get(key)) {
                    case Option.Some(found) -> found;
                    case Option.None() -> 0;
                };
            }

            fn result(): Int {
                let list = ArrayList<Int>.of(1, 2, 3);
                let set = HashSet<Int>.of(2, 3);
                let mut map = HashMap<String, Int>.empty();
                map.put("a", 7);
                let mut editable = ArrayList<Int>.of(1, 3);
                let mut score = total(list) + lookup(map, "a");
                if (set.contains(2)) score = score + 10;
                if (editable.insert(1, 2)) score = score + 100;
                if (editable.insert(99, 4)) score = score + 1000;
                return score + sizeOf(editable);
            }
            """;

    @Test
    public void contractsAcceptTheConcreteImplementations() throws Exception {
        final var output = Files.createTempDirectory("zeron-collection-contracts-");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(parse(SOURCE)), "app.Main", "app", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(126,
                        loader.loadClass("app.Main").getMethod("result").invoke(null));
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
