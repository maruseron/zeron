package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.Map;

final class TypeUnifier {
    private TypeUnifier() {}

    static void unify(final TypeDescriptor pattern,
                      TypeDescriptor actual,
                      final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                      final Token where) {
        if (pattern.equals(actual)) return;
        if (pattern instanceof TypeParameterDescriptor parameter) {
            final var previous = substitutions.putIfAbsent(parameter, actual);
            if (previous != null && !previous.equals(actual)
                && !acceptsAny(previous, actual)
                && !(actual instanceof ReferenceDescriptor reference
                    && previous.equals(reference.baseType()))) {
                Zeron.resolutionError(new ResolutionError(where,
                        "Conflicting type inferences for '" + parameter.name() + "': "
                                + previous + " and " + actual + "."));
            }
            return;
        }
        if (pattern instanceof NullableDescriptor nullablePattern) {
            if (actual instanceof NullDescriptor) return;
            final var actualBase = actual instanceof NullableDescriptor nullableActual
                    ? nullableActual.baseType()
                    : actual;
            unify(nullablePattern.baseType(), actualBase, substitutions, where);
            return;
        }
        if (pattern instanceof ReferenceDescriptor referencePattern) {
            final var actualBase = actual instanceof ReferenceDescriptor referenceActual
                    ? referenceActual.baseType()
                    : actual;
            unify(referencePattern.baseType(), actualBase, substitutions, where);
            return;
        }
        if (pattern instanceof ArrayDescriptor && actual instanceof ReferenceDescriptor reference
                && reference.baseType() instanceof ArrayDescriptor) {
            actual = reference.baseType();
        }
        if (pattern instanceof ArrayDescriptor arrayPattern
                && actual instanceof ArrayDescriptor arrayActual) {
            unify(arrayPattern.elementType(), arrayActual.elementType(), substitutions, where);
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
        if (pattern instanceof FunctionDescriptor functionPattern
                && actual instanceof FunctionDescriptor functionActual
                && functionPattern.arity() == functionActual.arity()) {
            for (int i = 0; i < functionPattern.arity(); i++) {
                unify(functionPattern.parameters().get(i), functionActual.parameters().get(i),
                        substitutions, where);
            }
            unify(functionPattern.returnType(), functionActual.returnType(), substitutions, where);
            return;
        }
        if (!pattern.equals(actual)
                && !(actual instanceof ReferenceDescriptor reference && pattern.equals(reference.baseType()))) {
            Zeron.resolutionError(new ResolutionError(where,
                    "Expected " + pattern + ", found " + actual + "."));
        }
    }

    private static boolean acceptsAny(final TypeDescriptor expected, final TypeDescriptor actual) {
        if (expected instanceof AnyDescriptor) {
            return !(actual instanceof NullableDescriptor || actual instanceof NullDescriptor);
        }
        return expected instanceof NullableDescriptor nullable
                && nullable.baseType() instanceof AnyDescriptor;
    }
}