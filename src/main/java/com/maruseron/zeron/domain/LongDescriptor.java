package com.maruseron.zeron.domain;

public final class LongDescriptor implements TypeDescriptor {
    public static final LongDescriptor LONG = new LongDescriptor();

    private LongDescriptor() {}

    @Override
    public String name() {
        return "Long";
    }

    @Override
    public boolean isDoubleWidth() {
        return true;
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Long";
    }
}
