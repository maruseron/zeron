package com.maruseron.zeron.domain;

import java.util.Objects;

public final class TypeParameterDescriptor implements TypeDescriptor {
    private final int scopeId;
    private final String name;
    private final TypeDescriptor bound;

    public TypeParameterDescriptor(final int scopeId, final String name) {
        this(scopeId, name, null);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final TypeDescriptor bound) {
        this.scopeId = scopeId;
        this.name = Objects.requireNonNull(name);
        this.bound = bound;
    }

    public int scopeId() {
        return scopeId;
    }

    public TypeDescriptor bound() {
        return bound;
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