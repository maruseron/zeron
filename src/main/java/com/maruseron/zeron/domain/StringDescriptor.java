package com.maruseron.zeron.domain;

public final class StringDescriptor implements TypeDescriptor {
    public static final StringDescriptor STRING = new StringDescriptor();

    private StringDescriptor() {}

    @Override
    public String name() {
        return "String";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.String";
    }
}
