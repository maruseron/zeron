package com.maruseron.zeron.domain;

public interface Nullable {
    // T -> T?
    Nullable toNullable();
    boolean isNullable();
}
