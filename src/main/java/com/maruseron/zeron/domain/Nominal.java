package com.maruseron.zeron.domain;

import java.util.EnumSet;
import java.util.Objects;
import java.util.stream.Collectors;

public final class Nominal implements TypeDescriptor, Nullable, Mutable, TypeParameter {
    private final String name;
    private final EnumSet<TypeModifier> modifiers;

    public Nominal(String name, EnumSet<TypeModifier> modifiers) {
        this.name = name;
        this.modifiers = modifiers;
    }

    public String name() {
        return name;
    }

    public EnumSet<TypeModifier> modifiers() {
        return modifiers;
    }

    @Override
    public boolean isMutable() {
        return modifiers.contains(TypeModifier.MUTABLE);
    }

    @Override
    public Nominal toMutable() {
        final var copy = EnumSet.copyOf(modifiers);
        copy.add(TypeModifier.MUTABLE);
        return isMutable() ? this : new Nominal(name(), copy);
    }

    @Override
    public boolean isNullable() {
        return modifiers.contains(TypeModifier.NULLABLE);
    }

    @Override
    public Nominal toNullable() {
        final var copy = EnumSet.copyOf(modifiers);
        copy.add(TypeModifier.NULLABLE);
        return isMutable() ? this : new Nominal(name(), copy);
    }

    @Override
    public boolean isTypeParameter() {
        return modifiers.contains(TypeModifier.TYPE_PARAMETER);
    }

    @Override
    public Nominal toTypeParameter() {
        final var copy = EnumSet.copyOf(modifiers);
        copy.add(TypeModifier.TYPE_PARAMETER);
        return isMutable() ? this : new Nominal(name(), copy);
    }

    // e.g &?#:String
    @Override
    public String descriptor() {
        final var modifierDescriptors = modifiers
                .stream().sorted().map(TypeModifier::getSymbol).collect(Collectors.joining());
        return modifierDescriptors + ":" + name();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof Nominal that)) return false;
        return Objects.equals(this.name, that.name) &&
               Objects.equals(this.modifiers, that.modifiers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, modifiers);
    }

    // e.g TypeDescriptor.Nominal[#&String?]
    @Override
    public String toString() {
        return  (isTypeParameter() ? "#" : "")
                + (isMutable() ? "&" : "")
                + name
                + (isNullable() ? "?" : "");
    }
}
