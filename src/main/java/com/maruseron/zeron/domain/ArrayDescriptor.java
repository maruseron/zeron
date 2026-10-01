package com.maruseron.zeron.domain;

import java.util.Objects;

public record ArrayDescriptor(TypeDescriptor elementType) implements TypeDescriptor {
    public ArrayDescriptor {
        Objects.requireNonNull(elementType);
    }

    @Override
    public String name() {
        return "Array";
    }

    @Override
    public String descriptor() {
        return "Array<" + elementType.descriptor() + ">";
    }

    @Override
    public String toString() {
        return "Array<" + elementType + ">";
    }
}