package com.maruseron.zeron.domain;

public final class NeverDescriptor implements TypeDescriptor {
    public static final NeverDescriptor NEVER = new NeverDescriptor();

    private NeverDescriptor() {}

    @Override
    public String name() {
        return "Never";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Never";
    }
}
