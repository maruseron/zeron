package com.maruseron.zeron.runtime;

import java.math.BigInteger;

public final class NumericSupport {
    private NumericSupport() {}

    public static BigInteger fromInt(final int value) {
        return BigInteger.valueOf(value);
    }

    public static BigInteger fromLong(final long value) {
        return BigInteger.valueOf(value);
    }

    public static BigInteger floorDivide(final BigInteger dividend, final BigInteger divisor) {
        final var result = dividend.divideAndRemainder(divisor);
        if (result[1].signum() != 0 && dividend.signum() != divisor.signum()) {
            return result[0].subtract(BigInteger.ONE);
        }
        return result[0];
    }

    public static BigInteger floorRemainder(final BigInteger dividend, final BigInteger divisor) {
        final var result = dividend.divideAndRemainder(divisor);
        if (result[1].signum() != 0 && dividend.signum() != divisor.signum()) {
            return result[1].add(divisor);
        }
        return result[1];
    }

    public static BigInteger signum(final BigInteger value) {
        return BigInteger.valueOf(value.signum());
    }

    public static boolean bigIntegerEquals(final BigInteger left, final BigInteger right) {
        return left.equals(right);
    }

    public static int bigIntegerCompare(final BigInteger left, final BigInteger right) {
        return left.compareTo(right);
    }

    // FNV-1a over the two's-complement bytes, finalized with fmix64.
    public static long bigIntegerHash(final BigInteger value) {
        final var bytes = value.toByteArray();
        var hash = 0xcbf29ce484222325L;
        for (final var octet : bytes) {
            hash ^= octet & 0xff;
            hash *= 0x100000001b3L;
        }
        return HashSupport.mix64(hash);
    }
}
