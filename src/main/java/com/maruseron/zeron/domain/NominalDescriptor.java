package com.maruseron.zeron.domain;

import java.util.EnumSet;
import java.util.Objects;
import java.util.stream.Collectors;

public final class NominalDescriptor implements TypeDescriptor {
    private final String name;
    private final boolean isNullable;

    public NominalDescriptor(String name, boolean isNullable) {
        this.name = name;
        this.isNullable = isNullable;
    }

    public String name() {
        return name;
    }

    @Override
    public boolean isNullable() {
        return isNullable;
    }

    @Override
    public NominalDescriptor toNullable() {
        return new NominalDescriptor(name, true);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof NominalDescriptor that)) return false;
        return Objects.equals(this.name, that.name) &&
               Objects.equals(this.isNullable, that.isNullable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, isNullable);
    }

    // e.g TypeDescriptor.Nominal[#&String?]
    @Override
    public String toString() {
        return "TypeDescriptor.Nominal[" + name() + (isNullable() ? "?" : "") + "]";
    }
}
