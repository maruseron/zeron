package com.maruseron.zeron.domain;

import java.util.Objects;
import java.util.List;

public final class TypeParameterDescriptor implements TypeDescriptor {
    private final int scopeId;
    private final String name;
    private final List<TypeDescriptor> bounds;

    public TypeParameterDescriptor(final int scopeId, final String name) {
        this(scopeId, name, List.of());
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final TypeDescriptor bound) {
        this(scopeId, name, bound == null ? List.of() : List.of(bound));
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final List<TypeDescriptor> bounds) {
        this.scopeId = scopeId;
        this.name = Objects.requireNonNull(name);
        this.bounds = List.copyOf(bounds);
    }

    public int scopeId() {
        return scopeId;
    }

    public TypeDescriptor bound() {
        return bounds.isEmpty() ? null : bounds.getFirst();
    }

    public List<TypeDescriptor> bounds() {
        return bounds;
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