package com.maruseron.zeron.domain;

public final class IntDescriptor implements TypeDescriptor {
    public static final IntDescriptor INT = new IntDescriptor();
    public static final IntDescriptor NULLABLE_INT = new IntDescriptor();

    private IntDescriptor() {}

    @Override
    public String name() {
        return "Int";
    }

    @Override
    public boolean isNullable() {
        return this == NULLABLE_INT;
    }

    @Override
    public IntDescriptor toNullable() {
        return NULLABLE_INT;
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Int" + (isNullable() ? "?" : "");
    }
}