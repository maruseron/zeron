package com.maruseron.zeron.domain;

public enum IntrinsicId {
    ARRAY_LITERAL("zeron.array.literal.v1"),
    ARRAY_LENGTH("zeron.array.length.v1"),
    ARRAY_READ("zeron.array.read.v1"),
    ARRAY_WRITE("zeron.array.write.v1");

    private final String stableName;

    IntrinsicId(final String stableName) {
        this.stableName = stableName;
    }

    public String stableName() {
        return stableName;
    }
}