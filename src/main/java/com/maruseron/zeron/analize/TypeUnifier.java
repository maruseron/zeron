package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.Map;

final class TypeUnifier {
    private TypeUnifier() {}

    static void unify(final TypeDescriptor pattern,
                      TypeDescriptor actual,
                      final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                      final Token where) {
        if (pattern instanceof TypeProjectionDescriptor projection) {
            if (actual instanceof TypeProjectionDescriptor actualProjection) {
                actual = actualProjection.bound();
            }
            unify(projection.bound(), actual, substitutions, where);
            return;
        }
        if (pattern instanceof TypeParameterDescriptor parameter) {
            if (actual instanceof NullDescriptor) return;

            if (!parameter.isEffectKind()
                    && !parameter.admitsMutableArguments()
                    && actual instanceof ReferenceDescriptor(var baseType)) {
                actual = baseType;
            }

            if (parameter.isHigherKinded()
                    && !(actual instanceof TypeConstructorDescriptor)
                    && !(actual instanceof TypeParameterDescriptor actualParameter
                            && actualParameter.isHigherKinded())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.GENERIC_INFERENCE_FAILURE, where,
                        "Cannot infer a type constructor for '" + parameter.name()
                                + "'; provide an explicit constructor argument."));
            }
            final var previous = substitutions.putIfAbsent(parameter, actual);
            if (previous != null && !previous.equals(actual)
                && !acceptsAny(previous, actual)
                && !(actual instanceof ReferenceDescriptor(var baseType)
                    && previous.equals(baseType))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.GENERIC_INFERENCE_FAILURE, where,
                        "Conflicting type inferences for '" + parameter.name() + "': "
                                + previous + " and " + actual + "."));
            }
            return;
        }
        if (pattern instanceof AppliedConstructorDescriptor applied) {
            if (actual instanceof ReferenceDescriptor reference) actual = reference.baseType();
            if (actual instanceof AppliedConstructorDescriptor actualApplied) {
                if (!applied.constructor().equals(actualApplied.constructor())) {
                    unify(applied.constructor(), actualApplied.constructor(), substitutions, where);
                }
                unify(applied.argument(), actualApplied.argument(), substitutions, where);
                return;
            }
            final var selected = substitutions.get(applied.constructor());
            if (selected instanceof TypeConstructorDescriptor constructor) {
                unify(constructor.apply(applied.argument()), actual, substitutions, where);
                return;
            }
            if (actual instanceof GenericDescriptor generic && generic.typeParameters().size() == 1) {
                substitutions.putIfAbsent(applied.constructor(),
                        new TypeConstructorDescriptor(generic.baseType(), java.util.List.of(
                                TypeDescriptor.ofInfer())));
                unify(applied.argument(), generic.typeParameters().getFirst(), substitutions, where);
                return;
            }
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.GENERIC_INFERENCE_FAILURE, where,
                    "Cannot infer the constructor parameter from this type; provide an explicit constructor argument."));
        }
        if (pattern.equals(actual)
                && !(pattern instanceof NullableDescriptor || pattern instanceof ReferenceDescriptor
                        || pattern instanceof ArrayDescriptor || pattern instanceof GenericDescriptor)) return;
        if (pattern instanceof NullableDescriptor nullablePattern) {
            if (actual instanceof NullDescriptor) return;
            final var actualBase = actual instanceof NullableDescriptor nullableActual
                    ? nullableActual.baseType()
                    : actual;
            unify(nullablePattern.baseType(), actualBase, substitutions, where);
            return;
        }
        if (pattern instanceof ReferenceDescriptor(var baseType)) {
            final var actualBase = actual instanceof ReferenceDescriptor(var type)
                    ? type
                    : actual;
            unify(baseType, actualBase, substitutions, where);
            return;
        }
        if (pattern instanceof ArrayDescriptor && actual instanceof ReferenceDescriptor(var baseType)
                && baseType instanceof ArrayDescriptor) {
            actual = baseType;
        }
        if (pattern instanceof ArrayDescriptor(var elementType)
                && actual instanceof ArrayDescriptor(var actualType)) {
            unify(elementType, actualType, substitutions, where);
            return;
        }
        if (pattern instanceof GenericDescriptor genericPattern
                && actual instanceof GenericDescriptor genericActual
                && genericPattern.baseType().equals(genericActual.baseType())
                && genericPattern.typeParameters().size() == genericActual.typeParameters().size()) {
            for (int i = 0; i < genericPattern.typeParameters().size(); i++) {
                unify(genericPattern.typeParameters().get(i),
                        genericActual.typeParameters().get(i), substitutions, where);
            }
            return;
        }
        if (pattern instanceof TypeConstructorDescriptor constructorPattern
                && actual instanceof TypeConstructorDescriptor constructorActual
                && constructorPattern.baseType().equals(constructorActual.baseType())
                && constructorPattern.arguments().size() == constructorActual.arguments().size()) {
            for (int i = 0; i < constructorPattern.arguments().size(); i++) {
                unify(constructorPattern.arguments().get(i), constructorActual.arguments().get(i),
                        substitutions, where);
            }
            return;
        }
        if (pattern instanceof FunctionDescriptor functionPattern
                && actual instanceof FunctionDescriptor functionActual
                && functionPattern.arity() == functionActual.arity()
                && functionPattern.raisedEffects().equals(functionActual.raisedEffects())) {
            for (int i = 0; i < functionPattern.arity(); i++) {
                unify(functionPattern.parameters().get(i), functionActual.parameters().get(i),
                        substitutions, where);
            }
            unify(functionPattern.returnType(), functionActual.returnType(), substitutions, where);
            return;
        }
        if (pattern.equals(actual)) return;
        if (!pattern.equals(actual)
                && !(actual instanceof ReferenceDescriptor(var baseType) && pattern.equals(baseType))) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE, where,
                    "Expected " + TypeFormatter.format(pattern) + ", found " + TypeFormatter.format(actual) + "."));
        }
    }

    private static boolean acceptsAny(final TypeDescriptor expected, final TypeDescriptor actual) {
        if (expected instanceof AnyDescriptor) {
            return !(actual instanceof NullableDescriptor || actual instanceof NullDescriptor);
        }
        return expected instanceof NullableDescriptor(var baseType)
                && baseType instanceof AnyDescriptor;
    }
}