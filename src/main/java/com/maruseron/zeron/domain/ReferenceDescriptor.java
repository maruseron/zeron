package com.maruseron.zeron.domain;

import java.util.Objects;

public record ReferenceDescriptor(TypeDescriptor baseType) implements TypeDescriptor {
    public ReferenceDescriptor {
        Objects.requireNonNull(baseType);
        if (baseType instanceof ReferenceDescriptor) {
            throw new IllegalArgumentException("Reference types cannot be nested");
        }
    }

    @Override
    public String name() {
        return baseType.name();
    }

    @Override
    public String descriptor() {
        return "&" + baseType.descriptor();
    }

    @Override
    public boolean isDoubleWidth() {
        return baseType.isDoubleWidth();
    }

    @Override
    public String toString() {
        return "&" + baseType;
    }
}