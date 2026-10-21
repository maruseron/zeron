package com.maruseron.zeron.fixtures;

public class JavaInteropFixture {
    private static final int[] SHARED_INT_VALUES = {6};
    private static final Object[] SHARED_OBJECT_VALUES = {"original"};
    private int value;
    public int publicValue;
    public int[] publicArray = new int[0];
    public float publicFloat = 3.25f;
    public final int finalValue = 17;
    public static int publicStaticValue = 23;
    public static final int finalStaticValue = 29;

    public JavaInteropFixture(final int value) {
        this.value = value;
    }

    public JavaInteropFixture(final int[] values) {
        this.value = values.length;
    }

    public static int add(final int left, final int right) {
        return left + right;
    }

    public static String format(final String format, final Object... values) {
        return String.format(format, values);
    }

    public static String join(final String separator, final String... values) {
        return String.join(separator, values);
    }

    public static int sum(final int... values) {
        var result = 0;
        for (final var value : values) result += value;
        return result;
    }

    public static double sum(final double... values) {
        var result = 0.0;
        for (final var value : values) result += value;
        return result;
    }

    public static int choose(final int value) {
        return value;
    }

    public static double choose(final double value) {
        return value;
    }

    public static String choose(final String value) {
        return value;
    }

    public static String choose(final String... values) {
        return String.join(",", values);
    }

    public static String ambiguous(final String... values) {
        return String.join(",", values);
    }

    public static String ambiguous(final Integer... values) {
        return Integer.toString(values.length);
    }

    public static int choose(final JavaInteropFixture value) {
        return value.value;
    }

    public static <T> T generic(final T value) {
        return value;
    }

    public static String echo(final String value) {
        return value;
    }

    public static Object[] echoObjects(final Object[] values) {
        return values;
    }

    public static int objectArrayLength(final Object[] values) {
        return values.length;
    }

    public static void overwriteFirstObject(final Object[] values) {
        values[0] = "changed";
    }

    public static String[] echoStrings(final String[] values) {
        return values;
    }

    public static int[] echoInts(final int[] values) {
        return values;
    }

    public static int[] nullableIntArray() {
        return null;
    }

    public static long[] echoLongs(final long[] values) {
        return values;
    }

    public static double[] echoDoubles(final double[] values) {
        return values;
    }

    public static boolean[] echoBooleans(final boolean[] values) {
        return values;
    }

    public static int[] sharedIntValues() {
        return SHARED_INT_VALUES;
    }

    public static void overwriteSharedInt() {
        SHARED_INT_VALUES[0] = 99;
    }

    public static void resetSharedInt() {
        SHARED_INT_VALUES[0] = 6;
    }

    public static Object[] sharedObjectValues() {
        return SHARED_OBJECT_VALUES;
    }

    public static void overwriteSharedObject() {
        SHARED_OBJECT_VALUES[0] = "changed";
    }

    public static void resetSharedObject() {
        SHARED_OBJECT_VALUES[0] = "original";
    }

    public static void overwriteFirstInt(final int[] values) {
        values[0] = 99;
    }

    public static float floatResult() {
        return 1.25f;
    }

    public static float floatParameter(final float value) {
        return value;
    }

    public static String nullableString() {
        return null;
    }

    public static int readValue(final JavaInteropFixture fixture) {
        return fixture.value;
    }

    public int increment(final int amount) {
        value += amount;
        return value;
    }

    public int current() {
        return value;
    }

    public void reset() {
        value = 0;
    }
}
