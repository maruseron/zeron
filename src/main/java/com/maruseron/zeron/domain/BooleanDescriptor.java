package com.maruseron.zeron.domain;

public final class BooleanDescriptor implements TypeDescriptor {
    public static final BooleanDescriptor BOOLEAN = new BooleanDescriptor();
    public static final BooleanDescriptor NULLABLE_BOOLEAN = new BooleanDescriptor();

    private BooleanDescriptor() {}

    @Override
    public String name() {
        return "Boolean";
    }

    @Override
    public boolean isNullable() {
        return this == NULLABLE_BOOLEAN;
    }

    @Override
    public BooleanDescriptor toNullable() {
        return NULLABLE_BOOLEAN;
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Boolean" + (isNullable() ? "?" : "");
    }
}
