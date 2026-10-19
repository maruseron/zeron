package com.maruseron.zeron;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class MathApiTest {
    @Test
    public void exposesMathExtensionsConstantsAndJvmEdgeSemantics() throws Exception {
        final var className = "MathApi" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-math-");
        final var unit = parse("MathApi.zn", """
                import zeron.math.*;

                fn intAbs(value: Int): Int = value.abs();
                fn longAbs(value: Long): Long = value.abs();
                fn floatAbs(value: Float): Float = value.abs();
                fn intMin(left: Int, right: Int): Int = left.min(right);
                fn longMin(left: Long, right: Long): Long = left.min(right);
                fn floatMin(left: Float, right: Float): Float = left.min(right);
                fn intMax(left: Int, right: Int): Int = left.max(right);
                fn longMax(left: Long, right: Long): Long = left.max(right);
                fn floatMax(left: Float, right: Float): Float = left.max(right);
                fn intClamp(value: Int, min: Int, max: Int): Int = value.clamp(min, max);
                fn longClamp(value: Long, min: Long, max: Long): Long = value.clamp(min, max);
                fn floatClamp(value: Float, min: Float, max: Float): Float = value.clamp(min, max);
                fn sqrt(value: Float): Float = value.sqrt();
                fn pow(value: Float, exponent: Float): Float = value.pow(exponent);
                fn exp(value: Float): Float = value.exp();
                fn ln(value: Float): Float = value.ln();
                fn log10(value: Float): Float = value.log10();
                fn sin(value: Float): Float = value.sin();
                fn cos(value: Float): Float = value.cos();
                fn tan(value: Float): Float = value.tan();
                fn asin(value: Float): Float = value.asin();
                fn acos(value: Float): Float = value.acos();
                fn atan(value: Float): Float = value.atan();
                fn atan2(y: Float, x: Float): Float = y.atan2(x);
                fn floor(value: Float): Float = value.floor();
                fn ceil(value: Float): Float = value.ceil();
                fn trunc(value: Float): Float = value.trunc();
                fn round(value: Float): Long = value.round();
                fn pi(): Float = PI;
                fn tau(): Float = TAU;
                fn e(): Float = E;
                fn nan(): Float = Float.NaN;
                fn positiveInfinity(): Float = Float.POSITIVE_INFINITY;
                fn negativeInfinity(): Float = Float.NEGATIVE_INFINITY;
                """);

        try {
            deleteTree(Path.of("dist"));
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)), className, "",
                    List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(Integer.MIN_VALUE, invoke(generated, "intAbs",
                        new Class<?>[]{int.class}, Integer.MIN_VALUE));
                assertEquals(Long.MIN_VALUE, invoke(generated, "longAbs",
                        new Class<?>[]{long.class}, Long.MIN_VALUE));
                assertEquals(7, invoke(generated, "intMin",
                        new Class<?>[]{int.class, int.class}, 7, 9));
                assertEquals(7L, invoke(generated, "longMin",
                        new Class<?>[]{long.class, long.class}, 7L, 9L));
                assertEquals(-2, invoke(generated, "intMax",
                        new Class<?>[]{int.class, int.class}, -2, -5));
                assertEquals(-2L, invoke(generated, "longMax",
                        new Class<?>[]{long.class, long.class}, -2L, -5L));
                assertEquals(4, invoke(generated, "intClamp",
                        new Class<?>[]{int.class, int.class, int.class}, 9, 1, 4));
                assertEquals(4L, invoke(generated, "longClamp",
                        new Class<?>[]{long.class, long.class, long.class}, 9L, 1L, 4L));
                assertEquals(4.0d, (double) invoke(generated, "floatClamp",
                        new Class<?>[]{double.class, double.class, double.class}, 9.0d, 1.0d, 4.0d), 0.0d);

                assertEquals(3.0d, (double) invoke(generated, "sqrt",
                        new Class<?>[]{double.class}, 9.0d), 0.0d);
                assertEquals(8.0d, (double) invoke(generated, "pow",
                        new Class<?>[]{double.class, double.class}, 2.0d, 3.0d), 0.0d);
                assertEquals(Math.E, (double) invoke(generated, "exp",
                        new Class<?>[]{double.class}, 1.0d), 0.0d);
                assertEquals(1.0d, (double) invoke(generated, "ln",
                        new Class<?>[]{double.class}, Math.E), 0.0d);
                assertEquals(2.0d, (double) invoke(generated, "log10",
                        new Class<?>[]{double.class}, 100.0d), 0.0d);
                assertEquals(1.0d, (double) invoke(generated, "sin",
                        new Class<?>[]{double.class}, Math.PI / 2.0d), 1.0e-15d);
                assertEquals(1.0d, (double) invoke(generated, "cos",
                        new Class<?>[]{double.class}, 0.0d), 0.0d);
                assertEquals(0.0d, (double) invoke(generated, "tan",
                        new Class<?>[]{double.class}, 0.0d), 0.0d);
                assertEquals(Math.PI / 2.0d, (double) invoke(generated, "asin",
                        new Class<?>[]{double.class}, 1.0d), 0.0d);
                assertEquals(0.0d, (double) invoke(generated, "acos",
                        new Class<?>[]{double.class}, 1.0d), 0.0d);
                assertEquals(Math.PI / 4.0d, (double) invoke(generated, "atan",
                        new Class<?>[]{double.class}, 1.0d), 0.0d);
                assertEquals(Math.PI / 2.0d, (double) invoke(generated, "atan2",
                        new Class<?>[]{double.class, double.class}, 1.0d, 0.0d), 0.0d);

                assertEquals(1.0d, (double) invoke(generated, "floor",
                        new Class<?>[]{double.class}, 1.9d), 0.0d);
                assertEquals(2.0d, (double) invoke(generated, "ceil",
                        new Class<?>[]{double.class}, 1.1d), 0.0d);
                assertEquals(-1.0d, (double) invoke(generated, "trunc",
                        new Class<?>[]{double.class}, -1.9d), 0.0d);
                assertEquals(0L, invoke(generated, "round", new Class<?>[]{double.class}, -0.5d));
                assertEquals(2L, invoke(generated, "round", new Class<?>[]{double.class}, 1.5d));
                assertEquals(0L, invoke(generated, "round",
                        new Class<?>[]{double.class}, Double.NaN));
                assertEquals(Long.MAX_VALUE, invoke(generated, "round",
                        new Class<?>[]{double.class}, Double.POSITIVE_INFINITY));

                assertEquals(Math.PI, (double) invoke(generated, "pi", new Class<?>[]{}), 0.0d);
                assertEquals(2.0d * Math.PI, (double) invoke(generated, "tau", new Class<?>[]{}), 0.0d);
                assertEquals(Math.E, (double) invoke(generated, "e", new Class<?>[]{}), 0.0d);
                assertTrue(Double.isNaN((double) invoke(generated, "nan", new Class<?>[]{})));
                assertEquals(Double.POSITIVE_INFINITY,
                        (double) invoke(generated, "positiveInfinity", new Class<?>[]{}), 0.0d);
                assertEquals(Double.NEGATIVE_INFINITY,
                        (double) invoke(generated, "negativeInfinity", new Class<?>[]{}), 0.0d);

                final var minZero = (double) invoke(generated, "floatMin",
                        new Class<?>[]{double.class, double.class}, -0.0d, 0.0d);
                final var maxZero = (double) invoke(generated, "floatMax",
                        new Class<?>[]{double.class, double.class}, -0.0d, 0.0d);
                assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(minZero));
                assertEquals(Double.doubleToRawLongBits(0.0d), Double.doubleToRawLongBits(maxZero));
                assertTrue(Double.isNaN((double) invoke(generated, "floatMin",
                        new Class<?>[]{double.class, double.class}, Double.NaN, 1.0d)));
                assertTrue(Double.isNaN((double) invoke(generated, "sqrt",
                        new Class<?>[]{double.class}, -1.0d)));
                assertEquals(Double.NEGATIVE_INFINITY,
                        (double) invoke(generated, "ln", new Class<?>[]{double.class}, 0.0d), 0.0d);
                assertEquals(3.0d, (double) invoke(generated, "floatAbs",
                        new Class<?>[]{double.class}, -3.0d), 0.0d);
                assertEquals(0L, Double.doubleToRawLongBits((double) invoke(generated, "floatAbs",
                        new Class<?>[]{double.class}, -0.0d)));

                final var invalidClamp = assertThrows(InvocationTargetException.class,
                        () -> invoke(generated, "intClamp",
                                new Class<?>[]{int.class, int.class, int.class}, 2, 4, 1));
                assertTrue(invalidClamp.getCause() instanceof IllegalArgumentException);
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void publishesMathApiThroughCompiledLibraryIndex() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "mathClient" + suffix;
        final var temporaryRoot = Files.createTempDirectory(Path.of("target"), "zeron-math-library-");
        final var libraryOutput = temporaryRoot.resolve("stdlib");
        final var sourceFile = temporaryRoot.resolve("MathLibraryClient.zn");
        Files.writeString(sourceFile, """
                package %s;
                import zeron.math.*;
                fn squareRoot(): Float = 81.0.sqrt();
                fn pi(): Float = PI;
                fn clamp(value: Int): Int = value.clamp(0, 10);
                fn infinity(): Float = Float.POSITIVE_INFINITY;
                """.formatted(packageName));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--build-stdlib", libraryOutput.toString()));

            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli(sourceFile.toString(), "--stdlib", "compiled",
                    "--library", libraryOutput.toString()));

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var client = loader.loadClass(packageName + ".MathLibraryClient");
                assertEquals(9.0d, (double) client.getMethod("squareRoot").invoke(null), 0.0d);
                assertEquals(Math.PI, (double) client.getMethod("pi").invoke(null), 0.0d);
                assertEquals(10, client.getMethod("clamp", int.class).invoke(null, 12));
                assertEquals(Double.POSITIVE_INFINITY,
                        (double) client.getMethod("infinity").invoke(null), 0.0d);
            }
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(temporaryRoot);
        }
    }

    private static Object invoke(final Class<?> generated,
                                 final String methodName,
                                 final Class<?>[] parameterTypes,
                                 final Object... arguments) throws ReflectiveOperationException {
        return generated.getMethod(methodName, parameterTypes).invoke(null, arguments);
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
