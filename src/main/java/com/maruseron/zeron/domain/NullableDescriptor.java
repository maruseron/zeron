package com.maruseron.zeron.domain;

import java.util.Objects;

public record NullableDescriptor(TypeDescriptor baseType) implements TypeDescriptor {
    public NullableDescriptor {
        Objects.requireNonNull(baseType);
        if (baseType instanceof NullableDescriptor || baseType instanceof NullDescriptor) {
            throw new IllegalArgumentException("Nullable types must have a non-nullable base type");
        }
    }

    @Override
    public String name() {
        return baseType.name();
    }

    @Override
    public TypeDescriptor toNullable() {
        return this;
    }

    @Override
    public boolean isNullable() {
        return true;
    }

    @Override
    public boolean isDoubleWidth() {
        return false;
    }

    @Override
    public String descriptor() {
        return "?" + baseType.descriptor();
    }

    @Override
    public String toString() {
        return baseType + "?";
    }
}