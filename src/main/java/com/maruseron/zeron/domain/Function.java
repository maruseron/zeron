package com.maruseron.zeron.domain;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class Function implements TypeDescriptor {
    private final String name;
    private final TypeDescriptor returnType;
    private final List<TypeDescriptor> parameters;

    Function(String name, TypeDescriptor returnType, List<TypeDescriptor> parameters) {
        this.name = name;
        this.returnType = returnType;
        this.parameters = parameters;
    }

    public String name() {
        return name;
    }

    public TypeDescriptor returnType() {
        return returnType;
    }

    public List<TypeDescriptor> parameters() {
        return parameters;
    }

    @Override
    public String descriptor() {
            // function annotation + arity
        return "$ " + parameters.size() + " "
            // parameters in order
                + parameters.stream()
                    .map(TypeDescriptor::descriptor).collect(Collectors.joining(" ")) + " "
            // return type !
            + returnType;
    }

    public int arity() {
        return parameters().size();
    }

    public Function withReturnType(final TypeDescriptor returnType) {
        return new Function(name, returnType, parameters);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof Function that)) return false;
        return Objects.equals(this.parameters, that.parameters) &&
               Objects.equals(this.returnType, that.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(returnType, parameters);
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Function("
                + parameters().stream().map(TypeDescriptor::toString).collect(Collectors.joining(", "))
                + ") -> "
                + returnType().toString();
    }
}
