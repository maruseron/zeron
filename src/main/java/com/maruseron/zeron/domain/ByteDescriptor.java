package com.maruseron.zeron.domain;

public final class ByteDescriptor implements TypeDescriptor {
    public static final ByteDescriptor BYTE = new ByteDescriptor();

    private ByteDescriptor() {}

    @Override
    public String name() {
        return "Byte";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Byte";
    }
}
