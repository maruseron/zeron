package com.maruseron.zeron.domain;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class FunctionDescriptor implements TypeDescriptor {
    private final String name;
    private final TypeDescriptor returnType;
    private final List<TypeDescriptor> parameters;
    private final List<TypeParameterDescriptor> typeParameters;

    FunctionDescriptor(String name, TypeDescriptor returnType, List<TypeDescriptor> parameters) {
        this(name, returnType, parameters, List.of());
    }

    FunctionDescriptor(String name, TypeDescriptor returnType, List<TypeDescriptor> parameters,
                       List<TypeParameterDescriptor> typeParameters) {
        this.name = name;
        this.returnType = returnType;
        this.parameters = List.copyOf(parameters);
        this.typeParameters = List.copyOf(typeParameters);
    }

    public String name() {
        return name;
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

    public TypeDescriptor returnType() {
        return returnType;
    }

    public List<TypeDescriptor> parameters() {
        return parameters;
    }

    public List<TypeParameterDescriptor> typeParameters() {
        return typeParameters;
    }

    public boolean isGeneric() {
        return !typeParameters.isEmpty();
    }

    public int arity() {
        return parameters().size();
    }

    public boolean isLambda() {
        return name.isEmpty();
    }

    public FunctionDescriptor toReturnType(final TypeDescriptor returnType) {
        return new FunctionDescriptor(name, returnType, parameters, typeParameters);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof FunctionDescriptor that)) return false;
         return Objects.equals(this.parameters, that.parameters) &&
             Objects.equals(this.typeParameters, that.typeParameters) &&
               Objects.equals(this.returnType, that.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(returnType, parameters, typeParameters);
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Function("
                + parameters().stream().map(TypeDescriptor::toString).collect(Collectors.joining(", "))
                + ") -> "
                + returnType().toString();
    }
}
