package com.maruseron.zeron.domain;

import java.util.Objects;
import java.util.List;

public final class TypeParameterDescriptor implements TypeDescriptor {
    public enum Variance {
        INVARIANT,
        COVARIANT
    }

    private final int scopeId;
    private final String name;
    private final List<TypeDescriptor> bounds;
    private final boolean higherKinded;
    private final Variance variance;
    private final boolean nullableArguments;
    private final boolean effectKind;
    private final boolean mutableArguments;

    public TypeParameterDescriptor(final int scopeId, final String name) {
        this(scopeId, name, List.of(), false, Variance.INVARIANT);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final TypeDescriptor bound) {
        this(scopeId, name, bound == null ? List.of() : List.of(bound), false, Variance.INVARIANT);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final List<TypeDescriptor> bounds) {
        this(scopeId, name, bounds, false, Variance.INVARIANT);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final List<TypeDescriptor> bounds,
                                   final boolean higherKinded) {
        this(scopeId, name, bounds, higherKinded, Variance.INVARIANT);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final List<TypeDescriptor> bounds,
                                   final boolean higherKinded,
                                   final Variance variance) {
        this(scopeId, name, bounds, higherKinded, variance, false, false);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final List<TypeDescriptor> bounds,
                                   final boolean higherKinded,
                                   final Variance variance,
                                   final boolean nullableArguments,
                                   final boolean effectKind) {
        this(scopeId, name, bounds, higherKinded, variance, nullableArguments, effectKind, false);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final List<TypeDescriptor> bounds,
                                   final boolean higherKinded,
                                   final Variance variance,
                                   final boolean nullableArguments,
                                   final boolean effectKind,
                                   final boolean mutableArguments) {
        this.scopeId = scopeId;
        this.name = Objects.requireNonNull(name);
        this.bounds = List.copyOf(bounds);
        this.higherKinded = higherKinded;
        this.variance = Objects.requireNonNull(variance);
        if (nullableArguments && effectKind || effectKind && mutableArguments
                || higherKinded && (nullableArguments || effectKind || mutableArguments)) {
            throw new IllegalArgumentException("Invalid type-parameter admissibility markers.");
        }
        this.nullableArguments = nullableArguments;
        this.effectKind = effectKind;
        this.mutableArguments = mutableArguments;
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

    public Variance variance() {
        return variance;
    }

    public boolean admitsNullableArguments() {
        return nullableArguments;
    }

    public boolean isEffectKind() {
        return effectKind;
    }

    public boolean admitsMutableArguments() {
        return mutableArguments;
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
                && higherKinded == that.higherKinded
                && variance == that.variance;
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopeId, name, higherKinded, variance);
    }

    @Override
    public String toString() {
        return "TypeParameter[" + name + "]";
    }
}