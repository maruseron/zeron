package com.maruseron.zeron.domain;

import java.util.Objects;
import java.util.List;

public final class TypeParameterDescriptor implements TypeDescriptor {
    /// Prefix for the hidden type parameter introduced by a parameter-position anonymous
    /// existential. The parameter is part of the lowered signature, but it is not written by
    /// source and does not consume an explicit call-site type argument.
    public static final String ANONYMOUS_EXISTENTIAL_PREFIX = "$zeron$anonymous$";

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
    private final boolean effectRowKind;
    private final boolean mutableArguments;
    private boolean reified;

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
        this(scopeId, name, bounds, higherKinded, variance, nullableArguments, effectKind, false,
                mutableArguments);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final List<TypeDescriptor> bounds,
                                   final boolean higherKinded,
                                   final Variance variance,
                                   final boolean nullableArguments,
                                   final boolean effectKind,
                                   final boolean effectRowKind,
                                   final boolean mutableArguments) {
        this(scopeId, name, bounds, higherKinded, variance, nullableArguments, effectKind,
                effectRowKind, mutableArguments, false);
    }

    public TypeParameterDescriptor(final int scopeId,
                                   final String name,
                                   final List<TypeDescriptor> bounds,
                                   final boolean higherKinded,
                                   final Variance variance,
                                   final boolean nullableArguments,
                                   final boolean effectKind,
                                   final boolean effectRowKind,
                                   final boolean mutableArguments,
                                   final boolean reified) {
        this.scopeId = scopeId;
        this.name = Objects.requireNonNull(name);
        this.bounds = List.copyOf(bounds);
        this.higherKinded = higherKinded;
        this.variance = Objects.requireNonNull(variance);
        if (nullableArguments && effectKind || effectKind && mutableArguments
                || effectKind && effectRowKind
                || effectRowKind && (nullableArguments || mutableArguments)
                || higherKinded && (nullableArguments || effectKind || effectRowKind || mutableArguments)
                || reified && (higherKinded || effectRowKind)) {
            throw new IllegalArgumentException("Invalid type-parameter admissibility markers.");
        }
        this.nullableArguments = nullableArguments;
        this.effectKind = effectKind;
        this.effectRowKind = effectRowKind;
        this.mutableArguments = mutableArguments;
        this.reified = reified;
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

    /// Returns this parameter with the `nullable` admissibility marker cleared, modelling a value
    /// that flow analysis has proved non-null. Equality ignores the marker, so the copy denotes the
    /// same type parameter and can be used wherever the declared descriptor is expected.
    public TypeParameterDescriptor asNonNull() {
        if (!nullableArguments) return this;
        return new TypeParameterDescriptor(scopeId, name, bounds, higherKinded, variance,
                false, effectKind, effectRowKind, mutableArguments, reified);
    }

    public boolean isEffectKind() {
        return effectKind;
    }

    public boolean isEffectRowKind() {
        return effectRowKind;
    }

    public boolean admitsMutableArguments() {
        return mutableArguments;
    }

    /// Whether this parameter was introduced by lowering an anonymous existential from a
    /// declared parameter type. Such parameters are inferred from arguments and never accepted
    /// as explicit call-site type arguments.
    public boolean isAnonymousExistential() {
        return name.startsWith(ANONYMOUS_EXISTENTIAL_PREFIX);
    }

    /// Whether the callee receives a runtime descriptor for this type parameter as a hidden
    /// parameter. This is an ABI marker, not part of type-parameter identity.
    public boolean isReified() {
        return reified;
    }

    /// Marks a non-exported declaration's parameter as requiring a runtime descriptor. The marker
    /// is inferred from the body, before bytecode signatures are emitted, and is deliberately not
    /// part of type-parameter identity or hashing.
    public void markReified() {
        if (higherKinded || effectRowKind) {
            throw new IllegalStateException("This type parameter cannot be reified: " + name);
        }
        reified = true;
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
                && effectRowKind == that.effectRowKind
                && variance == that.variance;
    }

    @Override
    public int hashCode() {
        return Objects.hash(scopeId, name, higherKinded, effectRowKind, variance);
    }

    @Override
    public String toString() {
        return "TypeParameter[" + name + "]";
    }
}