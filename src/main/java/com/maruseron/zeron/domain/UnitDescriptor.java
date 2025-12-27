package com.maruseron.zeron.domain;

public final class UnitDescriptor implements TypeDescriptor {

    public static final UnitDescriptor UNIT = new UnitDescriptor();
    public static final UnitDescriptor NULLABLE_UNIT = new UnitDescriptor();

    // singleton
    private UnitDescriptor() {}

    @Override
    public String name() {
        return "Unit";
    }

    @Override
    public boolean isNullable() {
        return this == NULLABLE_UNIT;
    }

    @Override
    public UnitDescriptor toNullable() {
        return NULLABLE_UNIT;
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Unit" + (isNullable() ? "?" : "");
    }
}
