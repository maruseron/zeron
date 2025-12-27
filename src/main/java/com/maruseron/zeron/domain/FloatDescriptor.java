package com.maruseron.zeron.domain;

public final class FloatDescriptor implements TypeDescriptor {
    public static final FloatDescriptor FLOAT = new FloatDescriptor();
    public static final FloatDescriptor NULLABLE_FLOAT = new FloatDescriptor();

    private FloatDescriptor() {}

    @Override
    public String name() {
        return "Float";
    }

    @Override
    public boolean isDoubleWidth() {
        return true;
    }

    @Override
    public boolean isNullable() {
        return this == NULLABLE_FLOAT;
    }

    @Override
    public FloatDescriptor toNullable() {
        return NULLABLE_FLOAT;
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Float" + (isNullable() ? "?" : "");
    }
}