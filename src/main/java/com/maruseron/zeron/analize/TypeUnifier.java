package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class TypeUnifier {
    private TypeUnifier() {}

    static void unify(final TypeDescriptor pattern,
                      TypeDescriptor actual,
                      final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                      final Token where,
                      final TypeProjector projector) {
        final var rows = new RowInference(projector.effectSubtyping());
        unify(pattern, actual, substitutions, where, rows, true, projector, false);
        rows.solve(substitutions, where);
    }

    static void unify(final TypeDescriptor pattern,
                      TypeDescriptor actual,
                      final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                      final Token where,
                      final RowInference rows,
                      final TypeProjector projector) {
        unify(pattern, actual, substitutions, where, rows, true, projector, false);
    }

    /// Binds type parameters from an argument during call inference.
    ///
    /// Unlike `unify`, a position whose pattern contains no type parameter contributes no
    /// constraint instead of an equality check. Assignability decides those positions afterwards,
    /// which is what makes a contravariant callback such as `(Any?) -> Unit` acceptable where
    /// `(Int) -> Unit` is expected.
    static void unifyForInference(final TypeDescriptor pattern,
                                  TypeDescriptor actual,
                                  final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                                  final Token where,
                                  final TypeProjector projector) {
        final var rows = new RowInference(projector.effectSubtyping());
        unify(pattern, actual, substitutions, where, rows, true, projector, true);
        rows.solve(substitutions, where);
    }

    static void unifyForInference(final TypeDescriptor pattern,
                                  TypeDescriptor actual,
                                  final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                                  final Token where,
                                  final RowInference rows,
                                  final TypeProjector projector) {
        unify(pattern, actual, substitutions, where, rows, true, projector, true);
    }

    /// Binds the row variables of `patternEffects` from `actualEffects`.
    ///
    /// A pattern without a row variable contributes no constraint: a pure function value satisfies
    /// every row, and inclusion in the other direction belongs to assignability.
    static void bindEffectRows(final List<TypeDescriptor> patternEffects,
                               final List<TypeDescriptor> actualEffects,
                               final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                               final Token where,
                               final TypeProjector projector) {
        if (EffectRows.of(patternEffects).variables().isEmpty()) return;
        final var rows = new RowInference(projector.effectSubtyping());
        unifyEffects(patternEffects, actualEffects, substitutions, where, rows, true,
                projector.effectSubtyping());
        rows.solve(substitutions, where);
    }

    private static void unify(final TypeDescriptor pattern,
                              TypeDescriptor actual,
                              final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                              final Token where,
                              final RowInference rows,
                              final boolean covariant,
                              final TypeProjector projector,
                              final boolean bindOnly) {
        if (bindOnly && !TypeSubstitution.containsTypeParameter(pattern)) return;
        if (pattern instanceof TypeProjectionDescriptor projection) {
            if (actual instanceof TypeProjectionDescriptor actualProjection) {
                actual = actualProjection.bound();
            }
            unify(projection.bound(), actual, substitutions, where, rows, covariant, projector, bindOnly);
            return;
        }
        if (pattern instanceof TypeParameterDescriptor parameter) {
            if (parameter.isEffectRowKind()) {
                if (isEffectValue(actual)) {
                    rows.addLowerBound(parameter, EffectRows.asRow(actual));
                    return;
                }
            }
            if (actual instanceof NullDescriptor) return;

            if (!parameter.isEffectKind()
                    && !parameter.isEffectRowKind()
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
                                + previous.name() + " and " + actual.name() + "."));
            }
            return;
        }
        // Capability is not part of the type structure inference compares. A mutable argument
        // unifies with a read-only pattern by dropping its outer reference; assignability still
        // checks the direction afterwards, so this cannot admit a read-only-to-mutable upgrade.
        if (!(pattern instanceof ReferenceDescriptor)
                && actual instanceof ReferenceDescriptor(var baseType)) {
            actual = baseType;
        }
        if (pattern instanceof AppliedConstructorDescriptor applied) {
            if (actual instanceof ReferenceDescriptor reference) actual = reference.baseType();
            if (actual instanceof AppliedConstructorDescriptor actualApplied) {
                if (!applied.constructor().equals(actualApplied.constructor())) {
                    unify(applied.constructor(), actualApplied.constructor(), substitutions, where, rows,
                            covariant, projector, bindOnly);
                }
                unify(applied.argument(), actualApplied.argument(), substitutions, where, rows, covariant, projector, bindOnly);
                return;
            }
            final var selected = substitutions.get(applied.constructor());
            if (selected instanceof TypeConstructorDescriptor constructor) {
                unify(constructor.apply(applied.argument()), actual, substitutions, where, rows, covariant, projector, bindOnly);
                return;
            }
            if (actual instanceof GenericDescriptor generic && generic.typeParameters().size() == 1) {
                substitutions.putIfAbsent(applied.constructor(),
                        new TypeConstructorDescriptor(generic.baseType(), java.util.List.of(
                                TypeDescriptor.ofInfer())));
                unify(applied.argument(), generic.typeParameters().getFirst(), substitutions, where, rows,
                        covariant, projector, bindOnly);
                return;
            }
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.GENERIC_INFERENCE_FAILURE, where,
                    "Cannot infer the constructor parameter from this type; provide an explicit constructor argument."));
        }
        if (pattern.equals(actual)
                && !(pattern instanceof NullableDescriptor || pattern instanceof ReferenceDescriptor
                        || pattern instanceof ArrayDescriptor || pattern instanceof GenericDescriptor
                        || pattern instanceof FunctionDescriptor || pattern instanceof EffectRowDescriptor
                        || pattern instanceof TypeParameterDescriptor parameter
                                && parameter.isEffectRowKind())) return;
        if (pattern instanceof NullableDescriptor nullablePattern) {
            if (actual instanceof NullDescriptor) return;
            final var actualBase = actual instanceof NullableDescriptor nullableActual
                    ? nullableActual.baseType()
                    : actual;
            unify(nullablePattern.baseType(), actualBase, substitutions, where, rows, covariant, projector, bindOnly);
            return;
        }
        if (pattern instanceof ReferenceDescriptor(var baseType)) {
            final var actualBase = actual instanceof ReferenceDescriptor(var type)
                    ? type
                    : actual;
            unify(baseType, actualBase, substitutions, where, rows, covariant, projector, bindOnly);
            return;
        }
        if (pattern instanceof ArrayDescriptor && actual instanceof ReferenceDescriptor(var baseType)
                && baseType instanceof ArrayDescriptor) {
            actual = baseType;
        }
        if (pattern instanceof ArrayDescriptor(var elementType)
                && actual instanceof ArrayDescriptor(var actualType)) {
            unify(elementType, actualType, substitutions, where, rows, covariant, projector, bindOnly);
            return;
        }
        if (pattern instanceof GenericDescriptor genericPattern
                && actual instanceof GenericDescriptor genericActual
                && genericPattern.baseType().equals(genericActual.baseType())
                && genericPattern.typeParameters().size() == genericActual.typeParameters().size()) {
            for (int i = 0; i < genericPattern.typeParameters().size(); i++) {
                unify(genericPattern.typeParameters().get(i),
                        genericActual.typeParameters().get(i), substitutions, where, rows, covariant, projector, bindOnly);
            }
            return;
        }
        if (pattern instanceof EffectRowDescriptor rowPattern
                && actual instanceof EffectRowDescriptor rowActual) {
            unifyRowValues(rowPattern, rowActual, substitutions, where, rows, covariant,
                    projector.effectSubtyping());
            return;
        }
        if (pattern instanceof TypeConstructorDescriptor constructorPattern
                && actual instanceof TypeConstructorDescriptor constructorActual
                && constructorPattern.baseType().equals(constructorActual.baseType())
                && constructorPattern.arguments().size() == constructorActual.arguments().size()) {
            for (int i = 0; i < constructorPattern.arguments().size(); i++) {
                unify(constructorPattern.arguments().get(i), constructorActual.arguments().get(i),
                        substitutions, where, rows, covariant, projector, bindOnly);
            }
            return;
        }
        if (pattern instanceof FunctionDescriptor functionPattern
                && actual instanceof FunctionDescriptor functionActual
                && functionPattern.arity() == functionActual.arity()
                && (functionPattern.receiverType() == null) == (functionActual.receiverType() == null)) {
            if (functionPattern.receiverType() != null) {
                unify(functionPattern.receiverType(), functionActual.receiverType(), substitutions, where,
                        rows, covariant, projector, bindOnly);
            }
            for (int i = 0; i < functionPattern.arity(); i++) {
                unify(functionPattern.parameters().get(i), functionActual.parameters().get(i),
                        substitutions, where, rows, !covariant, projector, bindOnly);
            }
            unify(functionPattern.returnType(), functionActual.returnType(), substitutions, where, rows,
                    covariant, projector, bindOnly);
            unifyEffects(functionPattern.raisedEffects(), functionActual.raisedEffects(),
                    substitutions, where, rows, covariant, projector.effectSubtyping());
            return;
        }
        if (pattern instanceof GenericDescriptor genericPattern
                && actual instanceof GenericDescriptor genericActual
                && !genericPattern.baseType().equals(genericActual.baseType())) {

            final var projected = projector.projectTo(genericActual, genericPattern.baseType());

            if (projected.isPresent()) {
                unify(pattern, projected.get(), substitutions, where, rows, covariant, projector, bindOnly);
                return;
            }
        }
        if (pattern.equals(actual)) return;
        if (!pattern.equals(actual)
                && !(actual instanceof ReferenceDescriptor(var baseType) && pattern.equals(baseType))) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE, where,
                    "Expected " + TypeFormatter.format(pattern) + ", found " + TypeFormatter.format(actual) + "."));
        }
    }

    private static boolean isEffectValue(final TypeDescriptor type) {
        return type instanceof EffectRowDescriptor
                || type instanceof TypeParameterDescriptor parameter
                        && (parameter.isEffectKind() || parameter.isEffectRowKind());
    }

    private static void unifyEffects(final List<TypeDescriptor> patternEffects,
                                     final List<TypeDescriptor> actualEffects,
                                     final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                                     final Token where,
                                     final RowInference rows,
                                     final boolean covariant,
                                     final EffectSubtyping subtyping) {
        final var patternRow = EffectRows.of(patternEffects);
        final var actualRow = EffectRows.of(actualEffects);
        final var patternVariables = new LinkedHashSet<>(patternRow.variables());
        if (patternVariables.isEmpty()) {
            final var offender = covariant
                    ? EffectRows.firstUnpermitted(actualEffects, patternEffects, subtyping)
                    : EffectRows.firstUnpermitted(patternEffects, actualEffects, subtyping);
            if (offender != null) {
                rowError(offender, where, covariant);
            }
            return;
        }
        if (covariant) {
            final var lowerBound = actualRow.withoutEffects(
                    new LinkedHashSet<>(patternRow.effects()), subtyping);
            for (final var variable : patternVariables) {
                rows.addLowerBound(variable, lowerBound);
            }
            return;
        }
        for (final var variable : patternVariables) {
            rows.addUpperBound(variable, actualRow);
        }
        for (final var effect : patternRow.effects()) {
            if (!EffectRows.coveredBy(effect, actualRow.effects(), subtyping)
                    && actualRow.variables().isEmpty()) {
                rowError(effect, where, false);
            }
        }
    }

    private static void unifyRowValues(final EffectRowDescriptor pattern,
                                       final EffectRowDescriptor actual,
                                       final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                                       final Token where,
                                       final RowInference rows,
                                       final boolean covariant,
                                       final EffectSubtyping subtyping) {
        final var patternVariables = new LinkedHashSet<>(pattern.variables());
        if (patternVariables.isEmpty()) {
            final var offender = covariant
                    ? actual.includedIn(pattern, subtyping) ? null
                            : firstOffender(actual, pattern, subtyping)
                    : pattern.includedIn(actual, subtyping) ? null
                            : firstOffender(pattern, actual, subtyping);
            if (offender != null) rowError(offender, where, covariant);
            return;
        }
        if (covariant) {
            final var lowerBound = actual.withoutEffects(
                    new LinkedHashSet<>(pattern.effects()), subtyping);
            for (final var variable : patternVariables) {
                rows.addLowerBound(variable, lowerBound);
            }
            return;
        }
        for (final var variable : patternVariables) {
            rows.addUpperBound(variable, actual);
        }
        for (final var effect : pattern.effects()) {
            if (!EffectRows.coveredBy(effect, actual.effects(), subtyping)
                    && actual.variables().isEmpty()) {
                rowError(effect, where, false);
            }
        }
    }

    private static TypeDescriptor firstOffender(final EffectRowDescriptor actual,
                                                final EffectRowDescriptor expected,
                                                final EffectSubtyping subtyping) {
        for (final var effect : actual.effects()) {
            if (!EffectRows.coveredBy(effect, expected.effects(), subtyping)) return effect;
        }
        for (final var variable : actual.variables()) {
            if (!expected.variables().contains(variable)) return variable;
        }
        return null;
    }

    private static void rowError(final TypeDescriptor effect, final Token where,
                                 final boolean covariant) {
        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                where, "Effect '" + TypeFormatter.format(effect) + "' is not permitted "
                        + (covariant ? "by the expected raises clause." : "by this callback position.")));
    }

    private static boolean acceptsAny(final TypeDescriptor expected, final TypeDescriptor actual) {
        if (expected instanceof AnyDescriptor) {
            return !(actual instanceof NullableDescriptor || actual instanceof NullDescriptor);
        }
        return expected instanceof NullableDescriptor(var baseType)
                && baseType instanceof AnyDescriptor;
    }
}
