package com.maruseron.zeron.runtime;

import java.math.BigInteger;
import java.util.Objects;

public final class RationalValue {
    private final BigInteger numerator;
    private final BigInteger denominator;

    private RationalValue(final BigInteger numerator, final BigInteger denominator) {
        this.numerator = numerator;
        this.denominator = denominator;
    }

    public static RationalValue of(final BigInteger numerator, final BigInteger denominator) {
        Objects.requireNonNull(numerator);
        Objects.requireNonNull(denominator);
        if (denominator.signum() == 0) throw new ArithmeticException("Division by zero.");
        var normalizedNumerator = numerator;
        var normalizedDenominator = denominator;
        if (normalizedDenominator.signum() < 0) {
            normalizedNumerator = normalizedNumerator.negate();
            normalizedDenominator = normalizedDenominator.negate();
        }
        final var divisor = normalizedNumerator.gcd(normalizedDenominator);
        return new RationalValue(
                normalizedNumerator.divide(divisor),
                normalizedDenominator.divide(divisor));
    }

    public static RationalValue fromInteger(final BigInteger value) {
        return of(value, BigInteger.ONE);
    }

    public static RationalValue fromLiteralText(final String value) {
        Objects.requireNonNull(value);
        final var decimal = new java.math.BigDecimal(value.replace("_", ""));
        final var scale = decimal.scale();
        if (scale >= 0) {
            return of(decimal.unscaledValue(), BigInteger.TEN.pow(scale));
        }
        return of(decimal.unscaledValue().multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
    }

    public BigInteger numerator() {
        return numerator;
    }

    public BigInteger denominator() {
        return denominator;
    }

    public String numeratorText() {
        return numerator.toString();
    }

    public String denominatorText() {
        return denominator.toString();
    }

    public boolean equalTo(final RationalValue other) {
        return numerator.equals(other.numerator) && denominator.equals(other.denominator);
    }

    public int compareTo(final RationalValue other) {
        return numerator.multiply(other.denominator)
                .compareTo(other.numerator.multiply(denominator));
    }

    // fmix64
    public static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    /*
    public long hashValue() {
        final var numeratorHash = NumericSupport.bigIntegerHash(numerator);
        final var denominatorHash = NumericSupport.bigIntegerHash(denominator);

        return mix64(numeratorHash ^ Long.rotateLeft(denominatorHash, 32));
    }
    */

    public int hashValue() {
        return 31 * numerator().hashCode() + denominator().hashCode();
    }

    public String displayText() {
        return denominator.equals(BigInteger.ONE)
                ? numerator.toString()
                : numerator + "/" + denominator;
    }

    public RationalValue add(final RationalValue other) {
        return of(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public RationalValue subtract(final RationalValue other) {
        return of(numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public RationalValue multiply(final RationalValue other) {
        return of(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    public RationalValue divide(final RationalValue other) {
        if (other.numerator.signum() == 0) throw new ArithmeticException("Division by zero.");
        return of(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }

    public RationalValue negate() {
        return of(numerator.negate(), denominator);
    }

    public RationalValue abs() {
        return of(numerator.abs(), denominator);
    }

    public RationalValue signum() {
        return fromInteger(BigInteger.valueOf(numerator.signum()));
    }

    public RationalValue reciprocal() {
        if (numerator.signum() == 0) throw new ArithmeticException("Division by zero.");
        return of(denominator, numerator);
    }

    public double toDouble() {
        if (numerator.signum() == 0) return 0.0d;

        final boolean negative = numerator.signum() < 0;
        final var absoluteNumerator = numerator.abs();
        int exponent = absoluteNumerator.bitLength() - denominator.bitLength();
        if (compareRatioToPowerOfTwo(absoluteNumerator, denominator, exponent) < 0) exponent--;

        final long signBit = negative ? Long.MIN_VALUE : 0L;
        if (exponent > 1023) return Double.longBitsToDouble(signBit | 0x7ff0_0000_0000_0000L);

        if (exponent < -1022) {
            final var significand = roundedQuotient(absoluteNumerator.shiftLeft(1074), denominator);
            if (significand.signum() == 0) return Double.longBitsToDouble(signBit);
            final var minimumNormal = BigInteger.ONE.shiftLeft(52);
            if (significand.compareTo(minimumNormal) >= 0) {
                return Double.longBitsToDouble(signBit | 0x0010_0000_0000_0000L);
            }
            return Double.longBitsToDouble(signBit | significand.longValueExact());
        }

        final int shift = 52 - exponent;
        final var scaledNumerator = shift >= 0 ? absoluteNumerator.shiftLeft(shift) : absoluteNumerator;
        final var scaledDenominator = shift >= 0 ? denominator : denominator.shiftLeft(-shift);
        var significand = roundedQuotient(scaledNumerator, scaledDenominator);
        final var carry = BigInteger.ONE.shiftLeft(53);
        if (significand.compareTo(carry) >= 0) {
            significand = significand.shiftRight(1);
            exponent++;
            if (exponent > 1023) {
                return Double.longBitsToDouble(signBit | 0x7ff0_0000_0000_0000L);
            }
        }
        final long exponentBits = (long) (exponent + 1023) << 52;
        final long fractionBits = significand.subtract(BigInteger.ONE.shiftLeft(52)).longValueExact();
        return Double.longBitsToDouble(signBit | exponentBits | fractionBits);
    }

    private static int compareRatioToPowerOfTwo(
            final BigInteger numerator, final BigInteger denominator, final int exponent) {
        return exponent >= 0
                ? numerator.compareTo(denominator.shiftLeft(exponent))
                : numerator.shiftLeft(-exponent).compareTo(denominator);
    }

    private static BigInteger roundedQuotient(final BigInteger numerator, final BigInteger denominator) {
        final var result = numerator.divideAndRemainder(denominator);
        final int halfComparison = result[1].shiftLeft(1).compareTo(denominator);
        if (halfComparison > 0 || halfComparison == 0 && result[0].testBit(0)) {
            return result[0].add(BigInteger.ONE);
        }
        return result[0];
    }
}
