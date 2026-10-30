package com.maruseron.zeron.domain;

import java.util.Objects;

public final class NominalDescriptor implements TypeDescriptor {
    private QualifiedName qualifiedName;
    public NominalDescriptor(String name) {
        this.qualifiedName = QualifiedName.parse(name);
    }

    public String name() {
        return qualifiedName.binaryName();
    }

    public QualifiedName qualifiedName() {
        return qualifiedName;
    }

    public void resolveName(final String name) {
        if (qualifiedName.packageParts().isEmpty()) {
            qualifiedName = QualifiedName.parse(name);
        } else if (!qualifiedName.binaryName().equals(name)) {
            throw new IllegalStateException("A qualified type name cannot be changed.");
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof NominalDescriptor that)) return false;
        return Objects.equals(this.qualifiedName, that.qualifiedName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(qualifiedName);
    }

    // e.g TypeDescriptor.Nominal[&String?]
    @Override
    public String toString() {
        return "TypeDescriptor.Nominal[" + name() + "]";
    }
}
