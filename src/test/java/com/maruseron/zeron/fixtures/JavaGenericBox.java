package com.maruseron.zeron.fixtures;

public final class JavaGenericBox<T> {
    private final T value;

    public <U> JavaGenericBox(final T value, final U ignored) {
        this.value = value;
    }

    public T value() {
        return value;
    }

    public <U> U echo(final U value) {
        return value;
    }
}
