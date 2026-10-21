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
            case AppliedConstructorDescriptor applied -> {
                final var constructor = substitutions.get(applied.constructor());
                if (constructor instanceof TypeConstructorDescriptor typeConstructor) {
                    yield typeConstructor.apply(substitute(applied.argument(), substitutions));
                }
                final var resolvedConstructor = constructor instanceof TypeParameterDescriptor parameter
                        && parameter.isHigherKinded()
                        ? parameter
                        : applied.constructor();
                yield new AppliedConstructorDescriptor(resolvedConstructor,
                        substitute(applied.argument(), substitutions));
            }
            case TypeConstructorDescriptor constructor -> new TypeConstructorDescriptor(
                    constructor.baseType(), constructor.arguments().stream()
                            .map(argument -> substitute(argument, substitutions)).toList());
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
            case TypeProjectionDescriptor projection -> new TypeProjectionDescriptor(
                    projection.kind(), substitute(projection.bound(), substitutions));
            case CapturedTypeDescriptor capture -> new CapturedTypeDescriptor(capture.id(),
                    substitute(capture.lowerBound(), substitutions),
                    substitute(capture.upperBound(), substitutions));
            case FunctionDescriptor function -> function.isHandler()
                    ? TypeDescriptor.handlerOf(substitute(function.returnType(), substitutions),
                            function.handledEffects().stream()
                                    .map(effect -> substitute(effect, substitutions)).toList(),
                            function.raisedEffects().stream()
                                    .map(effect -> substitute(effect, substitutions)).toList())
                    : TypeDescriptor.functionWithEffectsOf(
                            function.name(),
                            substitute(function.returnType(), substitutions),
                            function.parameters().stream()
                                    .map(parameter -> substitute(parameter, substitutions))
                                    .toList(),
                            function.typeParameters().stream()
                                    .map(parameter -> new TypeParameterDescriptor(parameter.scopeId(), parameter.name(),
                                            parameter.bounds().stream()
                                                    .map(bound -> substitute(bound, substitutions))
                                                    .toList(), parameter.isHigherKinded(), parameter.variance(),
                                                    parameter.admitsNullableArguments(), parameter.isEffectKind(),
                                                    parameter.admitsMutableArguments()))
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
            case AppliedConstructorDescriptor _ -> TypeDescriptor.ofName("java.lang.Object");
            case TypeConstructorDescriptor _ -> TypeDescriptor.ofName("java.lang.Object");
            case NullableDescriptor nullable -> erase(nullable.baseType()).toNullable();
            case ReferenceDescriptor reference -> new ReferenceDescriptor(erase(reference.baseType()));
            case ArrayDescriptor array -> TypeDescriptor.arrayOf(erase(array.elementType()));
            case GenericDescriptor generic -> generic.baseType();
            case TypeProjectionDescriptor projection -> erase(projection.bound());
            case CapturedTypeDescriptor capture -> erase(capture.upperBound());
            case FunctionDescriptor function -> function.isHandler()
                    ? TypeDescriptor.handlerOf(erase(function.returnType()),
                            function.handledEffects().stream().map(TypeSubstitution::erase).toList(),
                            function.raisedEffects().stream().map(TypeSubstitution::erase).toList())
                    : TypeDescriptor.functionWithEffectsOf(
                            function.name(), erase(function.returnType()),
                            function.parameters().stream().map(TypeSubstitution::erase).toList(),
                            List.of(), function.raisedEffects().stream().map(TypeSubstitution::erase).toList());
            default -> type;
        };
    }

    public static TypeDescriptor widenCapture(final TypeDescriptor type) {
        return switch (type) {
            case CapturedTypeDescriptor capture -> capture.upperBound();
            case NullableDescriptor nullable -> widenCapture(nullable.baseType()).toNullable();
            default -> type;
        };
    }

    public static boolean hasUnexposableCapture(final TypeDescriptor type) {
        if (type instanceof CapturedTypeDescriptor) return false;
        if (type instanceof NullableDescriptor nullable) {
            return hasUnexposableCapture(nullable.baseType());
        }
        return containsCapture(type);
    }

    public static boolean containsCapture(final TypeDescriptor type) {
        return switch (type) {
            case CapturedTypeDescriptor _ -> true;
            case AppliedConstructorDescriptor applied -> containsCapture(applied.argument());
            case TypeConstructorDescriptor constructor -> constructor.arguments().stream()
                    .anyMatch(TypeSubstitution::containsCapture);
            case NullableDescriptor nullable -> containsCapture(nullable.baseType());
            case ReferenceDescriptor reference -> containsCapture(reference.baseType());
            case ArrayDescriptor array -> containsCapture(array.elementType());
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(TypeSubstitution::containsCapture);
            case TypeProjectionDescriptor projection -> containsCapture(projection.bound());
            case FunctionDescriptor function -> containsCapture(function.returnType())
                    || function.parameters().stream().anyMatch(TypeSubstitution::containsCapture)
                    || function.raisedEffects().stream().anyMatch(TypeSubstitution::containsCapture)
                    || function.handledEffects().stream().anyMatch(TypeSubstitution::containsCapture);
            default -> false;
        };
    }

    public static boolean containsTypeParameter(final TypeDescriptor type) {
        return switch (type) {
            case TypeParameterDescriptor _ -> true;
            case AppliedConstructorDescriptor applied -> containsTypeParameter(applied.constructor())
                    || containsTypeParameter(applied.argument());
            case TypeConstructorDescriptor constructor -> constructor.arguments().stream()
                    .anyMatch(TypeSubstitution::containsTypeParameter);
            case NullableDescriptor nullable -> containsTypeParameter(nullable.baseType());
            case ReferenceDescriptor reference -> containsTypeParameter(reference.baseType());
            case ArrayDescriptor array -> containsTypeParameter(array.elementType());
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(TypeSubstitution::containsTypeParameter);
            case TypeProjectionDescriptor projection -> containsTypeParameter(projection.bound());
            case CapturedTypeDescriptor capture -> containsTypeParameter(capture.lowerBound())
                    || containsTypeParameter(capture.upperBound());
            case FunctionDescriptor function -> containsTypeParameter(function.returnType())
                    || function.parameters().stream().anyMatch(TypeSubstitution::containsTypeParameter)
                    || function.raisedEffects().stream().anyMatch(TypeSubstitution::containsTypeParameter)
                    || function.handledEffects().stream().anyMatch(TypeSubstitution::containsTypeParameter);
            default -> false;
        };
    }
}