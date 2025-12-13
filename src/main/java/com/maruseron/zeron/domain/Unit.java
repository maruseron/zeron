package com.maruseron.zeron.domain;

public final class Unit implements TypeDescriptor, Nullable {

    public static final Unit UNIT = new Unit();
    public static final Unit NULLABLE_UNIT = new Unit();

    // singleton
    private Unit() {}

    @Override
    public String descriptor() {
        return isNullable() ? "?:Unit" : "Unit";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Unit" + (isNullable() ? "?" : "");
    }

    @Override
    public Nullable toNullable() {
        return NULLABLE_UNIT;
    }

    @Override
    public boolean isNullable() {
        return this == NULLABLE_UNIT;
    }
}
