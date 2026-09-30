package com.maruseron.zeron.domain;

public final class BooleanDescriptor implements TypeDescriptor {
    public static final BooleanDescriptor BOOLEAN = new BooleanDescriptor();

    private BooleanDescriptor() {}

    @Override
    public String name() {
        return "Boolean";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Boolean";
    }
}
