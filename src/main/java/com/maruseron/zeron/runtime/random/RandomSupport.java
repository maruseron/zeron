package com.maruseron.zeron.runtime.random;

import java.security.SecureRandom;

/// Host support for the few operations that need unsigned or entropy-backed behavior.
public final class RandomSupport {
    private static final SecureRandom ENTROPY = new SecureRandom();

    private RandomSupport() {}

    /// Returns four independent seed words from the host entropy source.
    public static long[] entropyState() {
        final var state = new long[4];
        for (var index = 0; index < state.length; index++) {
            state[index] = ENTROPY.nextLong();
        }
        return state;
    }

    /// Returns the low 32 bits of `value` with Java's signed two's-complement interpretation.
    public static int lowInt(final long value) {
        return (int) value;
    }

    /// Returns the low eight bits of `value` as an unsigned byte.
    public static byte lowByte(final long value) {
        return (byte) (value & 0xffL);
    }

    /// Returns `value` modulo `bound` using unsigned arithmetic.
    public static long unsignedRemainder(final long value, final long bound) {
        return Long.remainderUnsigned(value, bound);
    }
}
