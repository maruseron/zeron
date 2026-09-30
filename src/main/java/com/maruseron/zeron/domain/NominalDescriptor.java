package com.maruseron.zeron.domain;

import java.util.Objects;

public final class NominalDescriptor implements TypeDescriptor {
    private final String name;
    public NominalDescriptor(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof NominalDescriptor that)) return false;
        return Objects.equals(this.name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    // e.g TypeDescriptor.Nominal[#&String?]
    @Override
    public String toString() {
        return "TypeDescriptor.Nominal[" + name() + "]";
    }
}
