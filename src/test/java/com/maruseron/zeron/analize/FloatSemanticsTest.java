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
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FloatSemanticsTest {
    @Test
    public void exposesNanPredicatesAndAnUnorderedSign() throws Exception {
        final var className = "FloatSemantics" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-float-");
        final var unit = parse("FloatSemantics.zn", """
                fn nan(): Float = Float.NaN;
                fn arithmeticNan(): Float = 0.0 / 0.0;
                fn positiveInfinity(): Float = 1.0 / 0.0;
                fn negativeInfinity(): Float = -1.0 / 0.0;
                fn isNan(value: Float): Boolean = value.isNaN();
                fn isFinite(value: Float): Boolean = value.isFinite();
                fn signIsSome(value: Float): Boolean = value.sign.isSome();
                fn signOr(value: Float): Int = value.sign.getOrElse(99);
                fn equal(left: Float, right: Float): Boolean = left == right;
                fn notEqual(left: Float, right: Float): Boolean = left != right;
                fn less(left: Float, right: Float): Boolean = left < right;
                fn lessOrEqual(left: Float, right: Float): Boolean = left <= right;
                fn greater(left: Float, right: Float): Boolean = left > right;
                fn greaterOrEqual(left: Float, right: Float): Boolean = left >= right;
                """);

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), className, "", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                final var nan = (Double) generated.getMethod("nan").invoke(null);
                final var arithmeticNan = (Double) generated.getMethod("arithmeticNan").invoke(null);
                final var positiveInfinity = (Double) generated.getMethod("positiveInfinity").invoke(null);
                final var negativeInfinity = (Double) generated.getMethod("negativeInfinity").invoke(null);

                assertTrue(Double.isNaN(nan));
                assertTrue(Double.isNaN(arithmeticNan));
                assertTrue((Boolean) generated.getMethod("isNan", double.class).invoke(null, nan));
                assertTrue((Boolean) generated.getMethod("isNan", double.class).invoke(null, arithmeticNan));
                assertFalse((Boolean) generated.getMethod("isFinite", double.class).invoke(null, nan));
                assertFalse((Boolean) generated.getMethod("isFinite", double.class)
                        .invoke(null, positiveInfinity));
                assertFalse((Boolean) generated.getMethod("isFinite", double.class)
                        .invoke(null, negativeInfinity));
                assertTrue((Boolean) generated.getMethod("isFinite", double.class).invoke(null, -0.0d));

                assertFalse((Boolean) generated.getMethod("signIsSome", double.class).invoke(null, nan));
                assertEquals(99, generated.getMethod("signOr", double.class).invoke(null, nan));
                assertEquals(1, generated.getMethod("signOr", double.class).invoke(null, positiveInfinity));
                assertEquals(-1, generated.getMethod("signOr", double.class).invoke(null, negativeInfinity));
                assertEquals(0, generated.getMethod("signOr", double.class).invoke(null, -0.0d));

                assertTrue((Boolean) generated.getMethod("equal", double.class, double.class)
                        .invoke(null, nan, arithmeticNan));
                assertFalse((Boolean) generated.getMethod("notEqual", double.class, double.class)
                        .invoke(null, nan, arithmeticNan));
                assertFalse((Boolean) generated.getMethod("less", double.class, double.class)
                        .invoke(null, nan, 1.0d));
                assertFalse((Boolean) generated.getMethod("lessOrEqual", double.class, double.class)
                        .invoke(null, nan, 1.0d));
                assertFalse((Boolean) generated.getMethod("greater", double.class, double.class)
                        .invoke(null, nan, 1.0d));
                assertFalse((Boolean) generated.getMethod("greaterOrEqual", double.class, double.class)
                        .invoke(null, nan, 1.0d));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens())
                .parseCompilationUnit(sourceName);
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
