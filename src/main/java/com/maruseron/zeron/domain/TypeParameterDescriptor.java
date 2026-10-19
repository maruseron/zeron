package com.maruseron.zeron.domain;

import java.util.Objects;
import java.util.List;

public final class TypeParameterDescriptor implements TypeDescriptor {
    private final int scopeId;
    private final String name;
    private final List<TypeDescriptor> bounds;
    private final boolean higherKinded;

    public TypeParameterDescriptor(final int scopeId, final String name) {
        this(scopeId, name, List.of(), false);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final TypeDescriptor bound) {
        this(scopeId, name, bound == null ? List.of() : List.of(bound), false);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final List<TypeDescriptor> bounds) {
        this(scopeId, name, bounds, false);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final List<TypeDescriptor> bounds,
                                   final boolean higherKinded) {
        this.scopeId = scopeId;
        this.name = Objects.requireNonNull(name);
        this.bounds = List.copyOf(bounds);
        this.higherKinded = higherKinded;
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

    public boolean isHigherKinded() {
        return higherKinded;
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
                && name.equals(that.name)
                && higherKinded == that.higherKinded;
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopeId, name, higherKinded);
    }

    @Override
    public String toString() {
        return "TypeParameter[" + name + "]";
    }
}