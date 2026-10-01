package com.maruseron.zeron.domain;

import java.util.Objects;

public final class TypeParameterDescriptor implements TypeDescriptor {
    private final int scopeId;
    private final String name;

    public TypeParameterDescriptor(final int scopeId, final String name) {
        this.scopeId = scopeId;
        this.name = Objects.requireNonNull(name);
    }

    public int scopeId() {
        return scopeId;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String descriptor() {
        return "#" + scopeId + ":" + name;
    }

    @Override
    public boolean equals(final Object other) {
        return this == other
                || other instanceof TypeParameterDescriptor that
                && scopeId == that.scopeId
                && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopeId, name);
    }

    @Override
    public String toString() {
        return "TypeParameter[" + name + "]";
    }
}