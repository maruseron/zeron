package com.maruseron.zeron.domain;

public final class AnyDescriptor implements TypeDescriptor {
    public static final AnyDescriptor ANY = new AnyDescriptor();

    private AnyDescriptor() {}

    @Override
    public String name() {
        return "Any";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Any";
    }
}