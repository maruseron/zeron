package com.maruseron.zeron.domain;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class FunctionDescriptor implements TypeDescriptor {
    private final String name;
    private final TypeDescriptor receiverType;
    private final TypeDescriptor returnType;
    private final List<TypeDescriptor> parameters;
    private final List<TypeParameterDescriptor> typeParameters;
    private final List<TypeDescriptor> raisedEffects;
    private final List<TypeDescriptor> handledEffects;
    private final boolean handlerShape;

    FunctionDescriptor(String name, TypeDescriptor returnType, List<TypeDescriptor> parameters) {
        this(name, returnType, parameters, List.of(), List.of(), List.of());
    }

    FunctionDescriptor(String name, TypeDescriptor returnType, List<TypeDescriptor> parameters,
                       List<TypeParameterDescriptor> typeParameters) {
        this(name, returnType, parameters, typeParameters, List.of(), List.of());
    }

    FunctionDescriptor(String name, TypeDescriptor returnType, List<TypeDescriptor> parameters,
                       List<TypeParameterDescriptor> typeParameters, List<TypeDescriptor> raisedEffects) {
        this(name, returnType, parameters, typeParameters, raisedEffects, List.of());
    }

    FunctionDescriptor(String name, TypeDescriptor returnType, List<TypeDescriptor> parameters,
                       List<TypeParameterDescriptor> typeParameters, List<TypeDescriptor> raisedEffects,
                       List<TypeDescriptor> handledEffects) {
        this(name, null, returnType, parameters, typeParameters, raisedEffects, handledEffects, false);
    }

    FunctionDescriptor(String name, TypeDescriptor receiverType, TypeDescriptor returnType,
                       List<TypeDescriptor> parameters,
                       List<TypeParameterDescriptor> typeParameters, List<TypeDescriptor> raisedEffects,
                       List<TypeDescriptor> handledEffects, boolean handlerShape) {
        this.name = name;
        this.receiverType = receiverType;
        this.returnType = returnType;
        this.parameters = List.copyOf(parameters);
        this.typeParameters = List.copyOf(typeParameters);
        this.raisedEffects = handlerShape ? normalizedEffects(raisedEffects)
                : EffectRows.normalize(raisedEffects);
        this.handledEffects = normalizedEffects(handledEffects);
        this.handlerShape = handlerShape;
    }

    private static List<TypeDescriptor> normalizedEffects(final List<TypeDescriptor> effects) {
        return effects.stream().sorted(java.util.Comparator.comparing(TypeFormatter::format))
                .toList();
    }

    public String name() {
        return name;
    }

    public TypeDescriptor receiverType() {
        return receiverType;
    }

    public boolean isReceiverFunction() {
        return receiverType != null;
    }

    public List<TypeDescriptor> invocationParameters() {
        if (receiverType == null) return parameters;
        final var invocationParameters = new java.util.ArrayList<TypeDescriptor>(parameters.size() + 1);
        invocationParameters.add(receiverType);
        invocationParameters.addAll(parameters);
        return List.copyOf(invocationParameters);
    }

    @Override
    public String descriptor() {
        // function annotation + arity
        return (receiverType == null ? "" : "@" + receiverType.descriptor() + " ")
                + "$ " + parameters.size() + " "
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

    public List<TypeDescriptor> raisedEffects() {
        return raisedEffects;
    }

    public List<TypeDescriptor> handledEffects() {
        return handledEffects;
    }

    public boolean isHandler() {
        return !handledEffects.isEmpty() || handlerShape;
    }

    FunctionDescriptor asHandler() {
        return new FunctionDescriptor(name, null, returnType, parameters, typeParameters, raisedEffects,
                handledEffects, true);
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
        return new FunctionDescriptor(name, receiverType, returnType, parameters, typeParameters, raisedEffects,
                handledEffects, handlerShape);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof FunctionDescriptor that)) return false;
         return Objects.equals(this.receiverType, that.receiverType) &&
             Objects.equals(this.parameters, that.parameters) &&
             sameTypeParameters(this.typeParameters, that.typeParameters) &&
               Objects.equals(this.returnType, that.returnType) &&
             this.handlerShape == that.handlerShape &&
             (handlerShape
                 ? new java.util.HashSet<>(raisedEffects).equals(new java.util.HashSet<>(that.raisedEffects))
                     && new java.util.HashSet<>(handledEffects).equals(new java.util.HashSet<>(that.handledEffects))
                 : Objects.equals(raisedEffects, that.raisedEffects)
                     && Objects.equals(handledEffects, that.handledEffects));
    }

    @Override
    public int hashCode() {
        final var raisedHash = handlerShape
                ? new java.util.HashSet<>(raisedEffects).hashCode() : raisedEffects.hashCode();
        final var handledHash = handlerShape
                ? new java.util.HashSet<>(handledEffects).hashCode() : handledEffects.hashCode();
        return handlerShape
                ? Objects.hash(receiverType, returnType, parameters, typeParameterHash(), raisedHash, handledHash, true)
                : Objects.hash(receiverType, returnType, parameters, typeParameterHash(),
                        raisedEffects, handledEffects, false);
    }

    private boolean sameTypeParameters(final List<TypeParameterDescriptor> left,
                                       final List<TypeParameterDescriptor> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            final var first = left.get(index);
            final var second = right.get(index);
            if (!first.equals(second)
                    || first.admitsNullableArguments() != second.admitsNullableArguments()
                    || first.isEffectKind() != second.isEffectKind()
                    || first.isEffectRowKind() != second.isEffectRowKind()
                    || first.admitsMutableArguments() != second.admitsMutableArguments()) return false;
        }
        return true;
    }

    private int typeParameterHash() {
        var hash = 1;
        for (final var parameter : typeParameters) {
            hash = 31 * hash + Objects.hash(parameter, parameter.admitsNullableArguments(),
                    parameter.isEffectKind(), parameter.isEffectRowKind(), parameter.admitsMutableArguments());
        }
        return hash;
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Function(" + (receiverType == null ? "" : TypeFormatter.format(receiverType) + ".")
                + parameters().stream().map(TypeDescriptor::toString).collect(Collectors.joining(", "))
                + ") -> "
                + returnType().toString();
    }
}
