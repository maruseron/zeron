package com.maruseron.zeron.domain;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class Generic implements TypeDescriptor {
    private final Nominal baseType;
    private final List<TypeDescriptor> typeParameters;

    public Generic(Nominal baseType, List<TypeDescriptor> typeParameters) {
        this.baseType = baseType;
        this.typeParameters = typeParameters;
    }

    @Override
    public String descriptor() {
        // generic annotation + arity
        return "@ " + typeParameters.size() + " "
                // main type
                + baseType.descriptor() + " "
                // type parameters separated by space
                + typeParameters.stream().map(TypeDescriptor::descriptor).collect(Collectors.joining(" "));
    }

    public Nominal baseType() {
        return baseType;
    }

    public List<TypeDescriptor> typeParameters() {
        return typeParameters;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof Generic that)) return false;
        return Objects.equals(this.baseType,       that.baseType) &&
               Objects.equals(this.typeParameters, that.typeParameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseType, typeParameters);
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Generic[" + baseType + "<" +
                typeParameters().stream().map(TypeDescriptor::toString).collect(Collectors.joining(", "))
                + ">]";
    }
}
