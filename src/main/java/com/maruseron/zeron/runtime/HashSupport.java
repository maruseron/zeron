package com.maruseron.zeron.runtime;

import java.lang.invoke.MethodHandle;

/// Runtime implementations of Zeron's built-in 64-bit hash operations.
///
/// The collection layer owns bucket mixing; these methods only provide equality-consistent
/// full-width hashes for the built-in scalar types. Derived implementations use [#combine] and
/// [#finalizeHash] so that every compiler-generated hash applies the same combiner.
public final class HashSupport {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long GOLDEN_RATIO = 0x9E3779B97F4A7C15L;

    private HashSupport() {}

    public static long hashInt(final int value) {
        return value;
    }

    public static long hashLong(final long value) {
        return value;
    }

    public static long hashFloat(final double value) {
        // doubleToLongBits canonicalizes every NaN to one bit pattern and distinguishes -0.0.
        return Double.doubleToLongBits(value);
    }

    public static long hashBoolean(final boolean value) {
        return value ? 1231L : 1237L;
    }

    public static long hashString(final String value) {
        var hash = FNV_OFFSET_BASIS;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= FNV_PRIME;
        }
        return mix64(hash);
    }

    public static long hashUnit() {
        return 0L;
    }

    public static long hashNullableWithHandle(final Object value, final MethodHandle handle) {
        if (value == null) return 0L;
        try {
            return (long) handle.invoke(value);
        } catch (final Throwable throwable) {
            throw new IllegalStateException("Nullable hash evidence failed.", throwable);
        }
    }

    public static long combine(final long accumulator, final long component) {
        return Long.rotateLeft(accumulator, 27) * GOLDEN_RATIO + component;
    }

    public static long finalizeHash(final long accumulator) {
        return mix64(accumulator);
    }

    public static long caseSeed(final int caseIndex) {
        return mix64((caseIndex + 1L) * GOLDEN_RATIO);
    }

    public static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }
}
