package com.maruseron.zeron.domain;

public final class NullDescriptor implements TypeDescriptor {
    public static final NullDescriptor NULL = new NullDescriptor();

    private NullDescriptor() {}

    @Override
    public String name() {
        return "Null";
    }

    @Override
    public String descriptor() {
        return "<Null>";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Null";
    }
}