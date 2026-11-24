package com.maruseron.zeron.runtime.buffer;

/// Host support for operations whose exact unsigned behavior is clearer on the JVM.
public final class BufferSupport {
    private BufferSupport() {}

    public static byte toByte(final int value) { return (byte) value; }
    public static int bitCount(final int value) { return Integer.bitCount(value); }
    public static int bitCountLong(final long value) { return Long.bitCount(value); }
    public static int leadingZeros(final int value) { return Integer.numberOfLeadingZeros(value); }
    public static int leadingZerosLong(final long value) { return Long.numberOfLeadingZeros(value); }
    public static int trailingZeros(final int value) { return Integer.numberOfTrailingZeros(value); }
    public static int trailingZerosLong(final long value) { return Long.numberOfTrailingZeros(value); }
    public static int rotateLeft(final int value, final int bits) { return Integer.rotateLeft(value, bits); }
    public static int rotateRight(final int value, final int bits) { return Integer.rotateRight(value, bits); }
    public static long rotateLeftLong(final long value, final int bits) { return Long.rotateLeft(value, bits); }
    public static long rotateRightLong(final long value, final int bits) { return Long.rotateRight(value, bits); }
    public static int reverseBits(final int value) { return Integer.reverse(value); }
    public static long reverseBitsLong(final long value) { return Long.reverse(value); }
    public static int floatToBits(final double value) { return Float.floatToRawIntBits((float) value); }
    public static double bitsToFloat(final int value) { return Float.intBitsToFloat(value); }
}
