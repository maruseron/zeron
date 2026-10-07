package com.maruseron.zeron.domain;

import java.util.List;
import java.util.Map;

public final class TypeSubstitution {
    private TypeSubstitution() {}

    public static TypeDescriptor substitute(final TypeDescriptor type,
                                             final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        return switch (type) {
            case TypeParameterDescriptor parameter ->
                    substitutions.getOrDefault(parameter, parameter);
            case NullableDescriptor nullable ->
                    substitute(nullable.baseType(), substitutions).toNullable();
            case ReferenceDescriptor reference ->
                    new ReferenceDescriptor(substitute(reference.baseType(), substitutions));
            case ArrayDescriptor array ->
                    TypeDescriptor.arrayOf(substitute(array.elementType(), substitutions));
            case GenericDescriptor generic -> TypeDescriptor.genericOf(generic.baseType(),
                    generic.typeParameters().stream()
                            .map(argument -> substitute(argument, substitutions))
                            .toList());
            case FunctionDescriptor function -> TypeDescriptor.functionWithEffectsOf(
                    function.name(),
                    substitute(function.returnType(), substitutions),
                    function.parameters().stream()
                            .map(parameter -> substitute(parameter, substitutions))
                            .toList(),
                    function.typeParameters().stream()
                            .map(parameter -> new TypeParameterDescriptor(parameter.scopeId(), parameter.name(),
                                    parameter.bounds().stream()
                                            .map(bound -> substitute(bound, substitutions))
                                            .toList()))
                            .toList(),
                    function.raisedEffects().stream()
                            .map(effect -> substitute(effect, substitutions))
                            .toList());
            default -> type;
        };
    }

    public static TypeDescriptor erase(final TypeDescriptor type) {
        return switch (type) {
            case TypeParameterDescriptor _ -> TypeDescriptor.ofName("java.lang.Object");
            case NullableDescriptor nullable -> erase(nullable.baseType()).toNullable();
            case ReferenceDescriptor reference -> new ReferenceDescriptor(erase(reference.baseType()));
            case ArrayDescriptor array -> TypeDescriptor.arrayOf(erase(array.elementType()));
            case GenericDescriptor generic -> generic.baseType();
            case FunctionDescriptor function -> TypeDescriptor.functionWithEffectsOf(
                    function.name(), erase(function.returnType()),
                    function.parameters().stream().map(TypeSubstitution::erase).toList(),
                    List.of(), function.raisedEffects().stream().map(TypeSubstitution::erase).toList());
            default -> type;
        };
    }

    public static boolean containsTypeParameter(final TypeDescriptor type) {
        return switch (type) {
            case TypeParameterDescriptor _ -> true;
            case NullableDescriptor nullable -> containsTypeParameter(nullable.baseType());
            case ReferenceDescriptor reference -> containsTypeParameter(reference.baseType());
            case ArrayDescriptor array -> containsTypeParameter(array.elementType());
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(TypeSubstitution::containsTypeParameter);
            case FunctionDescriptor function -> containsTypeParameter(function.returnType())
                    || function.parameters().stream().anyMatch(TypeSubstitution::containsTypeParameter)
                    || function.raisedEffects().stream().anyMatch(TypeSubstitution::containsTypeParameter);
            default -> false;
        };
    }
}