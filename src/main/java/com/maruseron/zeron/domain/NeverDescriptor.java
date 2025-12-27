package com.maruseron.zeron.domain;

public final class NeverDescriptor implements TypeDescriptor {
    public static final NeverDescriptor NEVER = new NeverDescriptor();
    public static final NeverDescriptor NULLABLE_NEVER = new NeverDescriptor();

    private NeverDescriptor() {}

    @Override
    public String name() {
        return "Never";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Never" + (isNullable() ? "?" : "");
    }

    @Override
    public NeverDescriptor toNullable() {
        return NULLABLE_NEVER;
    }

    @Override
    public boolean isNullable() {
        return this == NULLABLE_NEVER;
    }
}
