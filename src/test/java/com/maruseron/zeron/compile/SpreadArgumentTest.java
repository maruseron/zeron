package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class SpreadArgumentTest {
    @Test
    public void spreadsArraysIterablesAndMixedTails() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-spread-");
        final var unit = parse("""
                package app;
                import zeron.collections.ArrayList;

                fn total(values: Int...): Int {
                    let mut sum = 0;
                    for (let value in values) {
                        sum += value;
                    }
                    return sum;
                }

                fn tagged(prefix: String = "tag", values: String...): String {
                    let mut result = prefix;
                    for (let value in values) {
                        result += value;
                    }
                    return result;
                }

                fn firstOf<T admits nullable>(fallback: T, values: T...): T =
                    if (values.length > 0) then values[0] else fallback;

                public class Summer {
                    public constructor new;
                    public constructor from(values: Int...) = Summer.new();
                    public sum(values: Int...): Int = total(...values);
                }

                fn directArray(): Int {
                    let values = [1, 2, 3];
                    return total(...values);
                }

                fn mixedArray(): Int = total(1, ...[2, 3]);

                fn fromList(): Int = total(...ArrayList.of(4, 5, 6));

                fn withDefault(): String = tagged(...["a", "b"]);

                fn withPrefix(): String = tagged("sum:", ...["<1>", "<2>"]);

                fn genericInference(): Int = firstOf(0, ...[7, 8]);

                fn methodSpread(): Int {
                    let values = [9, 10];
                    return Summer.new().sum(...values);
                }

                fn namedConstructorSpread(): Int {
                    let values = [11, 12];
                    return Summer.from(...values).sum(...values);
                }
                """);
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    "app.Main", "app", List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass("app.Main");
                assertEquals(6, main.getMethod("directArray").invoke(null));
                assertEquals(6, main.getMethod("mixedArray").invoke(null));
                assertEquals(15, main.getMethod("fromList").invoke(null));
                assertEquals("tagab", main.getMethod("withDefault").invoke(null));
                assertEquals("sum:<1><2>", main.getMethod("withPrefix").invoke(null));
                assertEquals(7, main.getMethod("genericInference").invoke(null));
                assertEquals(19, main.getMethod("methodSpread").invoke(null));
                assertEquals(23, main.getMethod("namedConstructorSpread").invoke(null));
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
            for (final var entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
