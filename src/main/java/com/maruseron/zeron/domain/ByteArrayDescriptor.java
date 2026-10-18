package com.maruseron.zeron.domain;

public final class ByteArrayDescriptor implements TypeDescriptor {
    public static final ByteArrayDescriptor BYTE_ARRAY = new ByteArrayDescriptor();

    private ByteArrayDescriptor() {}

    @Override
    public String name() {
        return "ByteArray";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.ByteArray";
    }
}
