package com.maruseron.zeron.domain;

public final class IntDescriptor implements TypeDescriptor {
    public static final IntDescriptor INT = new IntDescriptor();

    private IntDescriptor() {}

    @Override
    public String name() {
        return "Int";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Int";
    }
}