package com.maruseron.zeron.fixtures;

import java.util.List;

public class JavaGenericCallableFixture {
    public JavaGenericCallableFixture() {}

    public <T> JavaGenericCallableFixture(final T value) {}

    public static <T> T identity(final T value) {
        return value;
    }

    public static <T> T empty() {
        return null;
    }

    public static <T extends String> T stringOnly(final T value) {
        return value;
    }

    public static <T extends Number> T numberIdentity(final T value) {
        return value;
    }

    public static <T> List<T> singleton(final T value) {
        return List.of(value);
    }
}
