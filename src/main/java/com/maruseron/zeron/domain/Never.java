package com.maruseron.zeron.domain;

public final class Never implements TypeDescriptor, Nullable {
    public static final Never NEVER = new Never();
    public static final Never NULLABLE_NEVER = new Never();

    private Never() {}

    @Override
    public String descriptor() {
        return isNullable() ? "?:Never" : "Never";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Never" + (isNullable() ? "?" : "");
    }

    @Override
    public Never toNullable() {
        return NULLABLE_NEVER;
    }

    @Override
    public boolean isNullable() {
        return this == NULLABLE_NEVER;
    }
}
