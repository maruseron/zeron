package com.maruseron.zeron.fixtures;

public class JavaInteropFixture {
    private int value;

    public JavaInteropFixture(final int value) {
        this.value = value;
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
