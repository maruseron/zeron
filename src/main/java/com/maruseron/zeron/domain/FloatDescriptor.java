package com.maruseron.zeron.domain;

public final class FloatDescriptor implements TypeDescriptor {
    public static final FloatDescriptor FLOAT = new FloatDescriptor();

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
    public String toString() {
        return "TypeDescriptor.Float";
    }
}