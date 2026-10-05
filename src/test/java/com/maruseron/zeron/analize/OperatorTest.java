package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class OperatorTest {
    @Test
    public void compilesIntegerAndFloatUnarySigns() throws Exception {
        final var className = "UnaryOperators" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn negateInt(value: Int): Int = -value;
                fn negateFloat(value: Float): Float = -value;
                fn positiveInt(value: Int): Int = +value;
                fn positiveFloat(value: Float): Float = +value;
                fn groupedNegation(): Int = -(2 + 3);
                fn negativeIntLiteral(): Int = -3;
                fn negativeFloatLiteral(): Float = -2.5;
                """);

        try {
            final var compiler = new CompilationService(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(-7, generated.getMethod("negateInt", int.class).invoke(null, 7));
                assertEquals(-2.5, generated.getMethod("negateFloat", double.class).invoke(null, 2.5));
                assertEquals(7, generated.getMethod("positiveInt", int.class).invoke(null, 7));
                assertEquals(2.5, generated.getMethod("positiveFloat", double.class).invoke(null, 2.5));
                assertEquals(-5, generated.getMethod("groupedNegation").invoke(null));
                assertEquals(-3, generated.getMethod("negativeIntLiteral").invoke(null));
                assertEquals(-2.5, generated.getMethod("negativeFloatLiteral").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsUnarySignsOnNonnumericValues() {
        for (final var source : List.of(
                "fn invalid(): Int = -true;",
                "fn invalid(): String = +\"text\";")) {
            assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
        }
    }

    @Test
    public void compilesRemainderBitwiseAndShiftOperators() throws Exception {
        final var className = "IntegerOperators" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn integerRemainder(): Int = 17 % 5;
                fn negativeRemainder(): Int = -17 % 5;
                fn floatRemainder(): Float = 7.5 % 2.0;
                fn bitwiseAnd(): Int = 12 & 10;
                fn bitwiseOr(): Int = 8 | 3;
                fn bitwiseXor(): Int = 11 ^ 3;
                fn bitwiseComplement(): Int = ~0;
                fn bitwisePrecedence(): Int = 1 | 2 ^ 4 & 7;
                fn leftShift(): Int = 1 << 3;
                fn signedRightShift(): Int = -8 >> 1;
                fn unsignedRightShift(): Int = -8 >>> 1;
                fn additiveBeforeShift(): Int = 1 + 2 << 2;
                fn shiftBeforeComparison(): Boolean = 1 << 2 == 4;
                fn compoundOperators(): Int {
                    let mut value = 42;
                    value %= 5;
                    value |= 8;
                    value ^= 3;
                    value &= 7;
                    value <<= 3;
                    value >>= 1;
                    value >>>= 1;
                    return value;
                }
                """);

        try {
            final var compiler = new CompilationService(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(2, generated.getMethod("integerRemainder").invoke(null));
                assertEquals(-2, generated.getMethod("negativeRemainder").invoke(null));
                assertEquals(1.5, (Double) generated.getMethod("floatRemainder").invoke(null), 0.0);
                assertEquals(8, generated.getMethod("bitwiseAnd").invoke(null));
                assertEquals(11, generated.getMethod("bitwiseOr").invoke(null));
                assertEquals(8, generated.getMethod("bitwiseXor").invoke(null));
                assertEquals(-1, generated.getMethod("bitwiseComplement").invoke(null));
                assertEquals(7, generated.getMethod("bitwisePrecedence").invoke(null));
                assertEquals(8, generated.getMethod("leftShift").invoke(null));
                assertEquals(-4, generated.getMethod("signedRightShift").invoke(null));
                assertEquals(2147483644, generated.getMethod("unsignedRightShift").invoke(null));
                assertEquals(12, generated.getMethod("additiveBeforeShift").invoke(null));
                assertEquals(true, generated.getMethod("shiftBeforeComparison").invoke(null));
                assertEquals(2, generated.getMethod("compoundOperators").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsBitwiseAndRemainderOperatorsForUnsupportedTypes() {
        for (final var source : List.of(
                "fn invalid(): Int = 1.0 & 1.0;",
                "fn invalid(): Int = 1 | true;",
                "fn invalid(): String = \"text\" % \"other\";",
                "fn invalid(): Int = ~1.0;")) {
            assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
        }
    }

    @Test
    public void parsesNestedGenericClosersAlongsideRightShiftOperators() {
        final var statements = parse("""
                fn nested(values: Array<Array<Int>>): Int = values[0][0];
                fn signedShift(value: Int): Int = value >> 1;
                fn unsignedShift(value: Int): Int = value >>> 1;
                """);

        assertEquals(3, statements.size());
        statements.forEach(org.junit.Assert::assertNotNull);
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}