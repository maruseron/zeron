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

    /*
    // FNV-1a
    public static long bigIntegerHash(final BigInteger value) {
        final var bytes = value.toByteArray();
        var hash = 0L;

        for (var i = 0; i < bytes.length; i += 1) {
            final var octet = bytes[i] & 0xff;
            hash = hash ^ octet;
            hash = hash * 1099511628211L;
        }

        hash = hash ^ (hash >>> 33);
        hash = hash * 0xff51afd7ed558ccdL;
        hash = hash ^ (hash >>> 33);
        hash = hash * 0xc4ceb9fe1a85ec53L;
        hash = hash ^ (hash >>> 33);

        return hash;
    }
    */
    public static int bigIntegerHash(final BigInteger value) {
        return value.hashCode();
    }
}
