package com.maruseron.zeron.domain;

public final class StringDescriptor implements TypeDescriptor {
    public static final StringDescriptor STRING = new StringDescriptor();
    public static final StringDescriptor NULLABLE_STRING = new StringDescriptor();

    private StringDescriptor() {}

    @Override
    public String name() {
        return "String";
    }

    @Override
    public boolean isNullable() {
        return this == NULLABLE_STRING;
    }

    @Override
    public StringDescriptor toNullable() {
        return NULLABLE_STRING;
    }

    @Override
    public String toString() {
        return "TypeDescriptor.String" + (isNullable() ? "?" : "");
    }
}
