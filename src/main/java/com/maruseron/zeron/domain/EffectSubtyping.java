package com.maruseron.zeron.domain;

/**
 * The effect-subtyping relation used by effect-set operations. Resolution supplies the declared
 * {@code under} hierarchy; consumers that only compare identity use {@link #exact()}.
 */
public interface EffectSubtyping {
    String TOP_EFFECT = "zeron.lang.Effect";

    boolean isSubtype(TypeDescriptor effect, TypeDescriptor superEffect);

    static EffectSubtyping exact() {
        return TypeDescriptor::equals;
    }
}
