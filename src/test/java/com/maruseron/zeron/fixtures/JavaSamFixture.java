package com.maruseron.zeron.fixtures;

import java.util.Comparator;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.concurrent.Callable;

public final class JavaSamFixture {
    private JavaSamFixture() {}

    public static String applyFunction(final Function<String, String> function, final String value) {
        return function.apply(value);
    }

    public static boolean testPredicate(final Predicate<String> predicate, final String value) {
        return predicate.test(value);
    }

    public static void acceptConsumer(final Consumer<String> consumer, final String value) {
        consumer.accept(value);
    }

    public static String supply(final Supplier<String> supplier) {
        return supplier.get();
    }

    public static int compare(final Comparator<String> comparator, final String left, final String right) {
        return comparator.compare(left, right);
    }

    public static String applyUnary(final UnaryOperator<String> operator, final String value) {
        return operator.apply(value);
    }

    public static int combine(final BiFunction<String, Integer, Integer> function,
                              final String value, final int number) {
        return function.apply(value, number);
    }

    public static int applyIntUnary(final IntUnaryOperator operator, final int value) {
        return operator.applyAsInt(value);
    }

    public static String transform(final TextTransformer transformer, final String value) {
        return transformer.transform(value);
    }

    public static String transformGeneric(final GenericTransformer<String> transformer,
                                          final String value) {
        return transformer.transform(value);
    }

    public static void run(final Runnable runnable) {
        runnable.run();
    }

    public static String call(final Callable<String> callable) throws Exception {
        return callable.call();
    }

    public static String ambiguous(final Function<String, String> function, final String value) {
        return "function:" + function.apply(value);
    }

    public static String ambiguous(final TextTransformer transformer, final String value) {
        return "transformer:" + transformer.transform(value);
    }
}
