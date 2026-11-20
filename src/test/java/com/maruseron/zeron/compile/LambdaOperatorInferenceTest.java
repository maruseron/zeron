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

public final class LambdaOperatorInferenceTest {
    @Test
    public void infersNumericFractionalAndComparableBoundsPerUse() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-lambda-operator-");
        final var unit = parse("""
                package app;

                fn addInts(): Int {
                    let add = (a, b) -> a + b;
                    return add(2, 3);
                }

                fn addFloats(): Float {
                    let add = (a, b) -> a + b;
                    return add(1.5, 2.25);
                }

                fn multiply(): Int {
                    let multiply = (a, b) -> a * b;
                    return multiply(4, 5);
                }

                fn subtract(): Int {
                    let subtract = (a, b) -> a - b;
                    return subtract(9, 4);
                }

                fn negate(): Int {
                    let negate = value -> -value;
                    return negate(7);
                }

                fn divide(): Float {
                    let divide = (a, b) -> a / b;
                    return divide(9.0, 2.0);
                }

                fn less(): Boolean {
                    let less = (a, b) -> a < b;
                    return less(3, 8);
                }

                fn greaterOrEqual(): Boolean {
                    let greaterOrEqual = (a, b) -> a >= b;
                    return greaterOrEqual(3, 2);
                }

                fn numericAndComparable(): Int {
                    let combine = (a, b) -> if (a < b) then a + b else a - b;
                    return combine(2, 5);
                }

                fn literalOperand(): Int {
                    let increment = value -> value + 1;
                    return increment(41);
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
                assertEquals(5, main.getMethod("addInts").invoke(null));
                assertEquals(3.75, (Double) main.getMethod("addFloats").invoke(null), 0.0001);
                assertEquals(20, main.getMethod("multiply").invoke(null));
                assertEquals(5, main.getMethod("subtract").invoke(null));
                assertEquals(-7, main.getMethod("negate").invoke(null));
                assertEquals(4.5, (Double) main.getMethod("divide").invoke(null), 0.0001);
                assertEquals(Boolean.TRUE, main.getMethod("less").invoke(null));
                assertEquals(Boolean.TRUE, main.getMethod("greaterOrEqual").invoke(null));
                assertEquals(7, main.getMethod("numericAndComparable").invoke(null));
                assertEquals(42, main.getMethod("literalOperand").invoke(null));
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
