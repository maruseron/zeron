package zeron.runtime;

import java.util.Arrays;

public final class JavaArraySupport {
    private JavaArraySupport() {}

    public static Object[] copyObjectArray(final Object[] values) {
        return values == null ? null : Arrays.copyOf(values, values.length);
    }

    public static int[] toJavaIntArray(final Object[] values) {
        if (values == null) return null;
        final var result = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (Integer) values[index];
        }
        return result;
    }

    public static Object[] fromJavaIntArray(final int[] values) {
        if (values == null) return null;
        final var result = new Object[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index];
        }
        return result;
    }

    public static long[] toJavaLongArray(final Object[] values) {
        if (values == null) return null;
        final var result = new long[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (Long) values[index];
        }
        return result;
    }

    public static Object[] fromJavaLongArray(final long[] values) {
        if (values == null) return null;
        final var result = new Object[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index];
        }
        return result;
    }

    public static double[] toJavaDoubleArray(final Object[] values) {
        if (values == null) return null;
        final var result = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (Double) values[index];
        }
        return result;
    }

    public static Object[] fromJavaDoubleArray(final double[] values) {
        if (values == null) return null;
        final var result = new Object[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index];
        }
        return result;
    }

    public static boolean[] toJavaBooleanArray(final Object[] values) {
        if (values == null) return null;
        final var result = new boolean[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (Boolean) values[index];
        }
        return result;
    }

    public static Object[] fromJavaBooleanArray(final boolean[] values) {
        if (values == null) return null;
        final var result = new Object[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index];
        }
        return result;
    }

    public static Object requireNonNull(final Object value) {
        if (value == null) throw new NullPointerException("Java interop result violated its non-null facade.");
        return value;
    }
}
