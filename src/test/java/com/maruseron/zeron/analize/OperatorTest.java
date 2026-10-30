package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
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
    public void compilesNumericOperatorsThroughWitnessesAndGenericBounds() throws Exception {
        final var className = "NumericOperators" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                import zeron.numeric.Fractional;
                import zeron.numeric.Integer;
                import zeron.numeric.Numeric;
                class Vector {
                    public property value: Int;
                    public constructor new;
                }
                witness Numeric<Vector> for Vector {
                    fn add(other: Vector): Vector = Vector.new(this.value + other.value);
                    fn subtract(other: Vector): Vector = Vector.new(this.value - other.value);
                    fn multiply(other: Vector): Vector = Vector.new(this.value * other.value);
                    fn negate(): Vector = Vector.new(-this.value);
                    fn abs(): Vector = Vector.new(this.value.abs());
                    fn signum(): Vector = Vector.new(this.value.signum());
                    static fn fromInteger(value: Integer): Vector = Vector.new(value.toIntExact());
                }
                fn add<T: mut + Numeric<T>>(left: T, right: T): T = left + right;
                fn subtract<T: mut + Numeric<T>>(left: T, right: T): T = left - right;
                fn negate<T: mut + Numeric<T>>(value: T): T = -value;
                fn multiply<T: mut + Numeric<T>>(left: T, right: T): T = left * right;
                fn reciprocal<T: mut + Fractional<T>>(value: T): T = value.reciprocal();
                fn forwarded<T: mut + Numeric<T>>(left: T, right: T): T = subtract(left, right);
                fn builtinAdd(): Int = add(2, 3);
                fn builtinLongAdd(): Long = add(2L, 3L);
                fn builtinFloatAdd(): Float = add(2.0, 3.0);
                fn builtinSubtract(): Int = subtract(2, 3);
                fn builtinFloatSubtract(): Float = subtract(5.0, 2.0);
                fn builtinNegate(): Int = negate(3);
                fn builtinMultiply(): Int = multiply(2, 3);
                fn builtinLongMultiply(): Long = multiply(2L, 3L);
                fn builtinFloatMultiply(): Float = multiply(2.0, 3.0);
                fn builtinReciprocal(): Float = reciprocal(2.0);
                fn forwardedEvidence(): Int = forwarded(5, 2);
                fn witnessAdd(): Int = add(Vector.new(2), Vector.new(3)).value;
                fn witnessSubtract(): Int = subtract(Vector.new(5), Vector.new(2)).value;
                fn witnessNegate(): Int = negate(Vector.new(3)).value;
                fn witnessMultiply(): Int = multiply(Vector.new(2), Vector.new(3)).value;
                fn directSubtract(): Int = (Vector.new(5) - Vector.new(2)).value;
                """;
        final var classFile = Path.of("dist", className + ".class");
        final var vectorFile = Path.of("dist", "Vector.class");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(parseUnit(source)), className, "", List.of(), true, List.of());
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(5, generated.getMethod("builtinAdd").invoke(null));
                assertEquals(5L, generated.getMethod("builtinLongAdd").invoke(null));
                assertEquals(5.0, generated.getMethod("builtinFloatAdd").invoke(null));
                assertEquals(-1, generated.getMethod("builtinSubtract").invoke(null));
                assertEquals(3.0, generated.getMethod("builtinFloatSubtract").invoke(null));
                assertEquals(-3, generated.getMethod("builtinNegate").invoke(null));
                assertEquals(6, generated.getMethod("builtinMultiply").invoke(null));
                assertEquals(6L, generated.getMethod("builtinLongMultiply").invoke(null));
                assertEquals(6.0, generated.getMethod("builtinFloatMultiply").invoke(null));
                assertEquals(0.5, generated.getMethod("builtinReciprocal").invoke(null));
                assertEquals(3, generated.getMethod("forwardedEvidence").invoke(null));
                assertEquals(5, generated.getMethod("witnessAdd").invoke(null));
                assertEquals(3, generated.getMethod("witnessSubtract").invoke(null));
                assertEquals(-3, generated.getMethod("witnessNegate").invoke(null));
                assertEquals(6, generated.getMethod("witnessMultiply").invoke(null));
                assertEquals(3, generated.getMethod("directSubtract").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(vectorFile);
        }
    }

    @Test
    public void requiresNumericEvidenceForEachOperator() {
        for (final var source : List.of(
                "fn invalid<T>(left: T, right: T): T = left + right;",
                """
                import zeron.numeric.Numeric;
                fn invalid<T: Numeric<T>>(left: T, right: T): T = left / right;
                """,
                """
                class Fake {
                    public constructor new;
                    public add(other: Fake): Fake = this;
                }
                fn invalid(left: Fake, right: Fake): Fake = left + right;
                """)) {
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
    public void comparesCustomTypesThroughComparableOrdering() throws Exception {
        final var className = "ComparableOperators" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                class Rank {
                    public property value: Int;
                    public constructor new;
                }
                witness zeron.lang.Comparable<Rank> for Rank {
                    fn compareTo(other: Rank): zeron.lang.Ordering =
                        if (this.value < other.value) then zeron.lang.Ordering.LT()
                        else if (this.value > other.value) then zeron.lang.Ordering.GT()
                        else zeron.lang.Ordering.EQ();
                }
                class NominalRank is zeron.lang.Comparable<NominalRank> {
                    public property value: Int;
                    public constructor new;
                    public compareTo(other: NominalRank): zeron.lang.Ordering =
                        if (this.value < other.value) then zeron.lang.Ordering.LT()
                        else if (this.value > other.value) then zeron.lang.Ordering.GT()
                        else zeron.lang.Ordering.EQ();
                }
                fn rankLess(left: Rank, right: Rank): Boolean = left < right;
                fn rankLessOrEqual(left: Rank, right: Rank): Boolean = left <= right;
                fn rankGreater(left: Rank, right: Rank): Boolean = left > right;
                fn rankGreaterOrEqual(left: Rank, right: Rank): Boolean = left >= right;
                fn less(): Boolean = rankLess(Rank.new(1), Rank.new(2));
                fn lessFalse(): Boolean = rankLess(Rank.new(3), Rank.new(2));
                fn lessOrEqual(): Boolean = rankLessOrEqual(Rank.new(2), Rank.new(2));
                fn lessOrEqualFalse(): Boolean = rankLessOrEqual(Rank.new(3), Rank.new(2));
                fn greater(): Boolean = rankGreater(Rank.new(3), Rank.new(2));
                fn greaterFalse(): Boolean = rankGreater(Rank.new(1), Rank.new(2));
                fn greaterOrEqual(): Boolean = rankGreaterOrEqual(Rank.new(2), Rank.new(2));
                fn greaterOrEqualFalse(): Boolean = rankGreaterOrEqual(Rank.new(1), Rank.new(2));
                fn nominalLess(): Boolean {
                    let left: NominalRank = NominalRank.new(1);
                    let right: NominalRank = NominalRank.new(2);
                    return left < right;
                }
                fn generic<T: zeron.lang.Comparable<T>>(left: T, right: T): Boolean = left < right;
                fn genericLess(): Boolean = generic<Rank>(Rank.new(1), Rank.new(2));
                """;
        final var classFile = Path.of("dist", className + ".class");
        try {
            final var compiler = new CompilationService(parse(source), className);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(true, generated.getMethod("less").invoke(null));
                assertEquals(false, generated.getMethod("lessFalse").invoke(null));
                assertEquals(true, generated.getMethod("lessOrEqual").invoke(null));
                assertEquals(false, generated.getMethod("lessOrEqualFalse").invoke(null));
                assertEquals(true, generated.getMethod("greater").invoke(null));
                assertEquals(false, generated.getMethod("greaterFalse").invoke(null));
                assertEquals(true, generated.getMethod("greaterOrEqual").invoke(null));
                assertEquals(false, generated.getMethod("greaterOrEqualFalse").invoke(null));
                assertEquals(true, generated.getMethod("nominalLess").invoke(null));
                assertEquals(true, generated.getMethod("genericLess").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(Path.of("dist", "Rank.class"));
            Files.deleteIfExists(Path.of("dist", "NominalRank.class"));
        }
    }

    @Test
    public void requiresComparableEvidenceForCustomRelationalOperands() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                class Rank { public constructor new; }
                fn invalid(left: Rank, right: Rank): Boolean = left < right;
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                fn invalid<T>(left: T, right: T): Boolean = left < right;
                """)));
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

    private static CompilationUnit parseUnit(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(null);
    }
}