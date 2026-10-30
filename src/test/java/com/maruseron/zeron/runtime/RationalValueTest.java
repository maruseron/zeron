package com.maruseron.zeron.runtime;

import org.junit.Test;

import java.math.BigInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class RationalValueTest {
    @Test
    public void normalizesValuesAndParsesExactDecimalText() {
        final var normalized = RationalValue.of(BigInteger.valueOf(6), BigInteger.valueOf(-8));
        final var decimal = RationalValue.fromLiteralText("1.2e-3");

        assertEquals(BigInteger.valueOf(-3), normalized.numerator());
        assertEquals(BigInteger.valueOf(4), normalized.denominator());
        assertEquals(BigInteger.valueOf(3), decimal.numerator());
        assertEquals(BigInteger.valueOf(2500), decimal.denominator());
    }

    @Test
    public void arithmeticRemainsExactAndDivisionByZeroPropagates() {
        final var oneThird = RationalValue.fromLiteralText("1").divide(
                RationalValue.fromLiteralText("3"));
        final var sum = RationalValue.fromLiteralText("0.1").add(
                RationalValue.fromLiteralText("0.2"));

        assertEquals(BigInteger.ONE, oneThird.numerator());
        assertEquals(BigInteger.valueOf(3), oneThird.denominator());
        assertEquals(BigInteger.valueOf(3), sum.numerator());
        assertEquals(BigInteger.TEN, sum.denominator());
        assertThrows(ArithmeticException.class,
                () -> RationalValue.fromLiteralText("1").divide(RationalValue.fromLiteralText("0")));
    }

    @Test
    public void convertsToBinary64WithCorrectRounding() {
        assertEquals(0.1d, RationalValue.fromLiteralText("0.1").toDouble(), 0.0d);
        assertEquals(Double.MIN_VALUE,
                RationalValue.of(BigInteger.ONE, BigInteger.ONE.shiftLeft(1074)).toDouble(), 0.0d);
        assertEquals(Double.POSITIVE_INFINITY,
                RationalValue.of(BigInteger.ONE.shiftLeft(2048), BigInteger.ONE).toDouble(), 0.0d);
    }
}
