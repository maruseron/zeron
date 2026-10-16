package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;

import java.util.*;

final class WitnessPatternUnifier {
    private WitnessPatternUnifier() {}

    static boolean overlaps(final Stmt.Witness first, final Stmt.Witness second) {
        final var usedScopes = new HashSet<Integer>();
        first.typeParameters().forEach(parameter -> usedScopes.add(parameter.scopeId()));
        second.typeParameters().forEach(parameter -> usedScopes.add(parameter.scopeId()));
        collectScopes(first.targetType(), usedScopes);
        collectScopes(first.contractType(), usedScopes);
        collectScopes(second.targetType(), usedScopes);
        collectScopes(second.contractType(), usedScopes);

        final var nextScope = new int[]{Integer.MIN_VALUE};
        final var firstRenaming = alphaRenaming(first.typeParameters(), usedScopes, nextScope);
        final var secondRenaming = alphaRenaming(second.typeParameters(), usedScopes, nextScope);
        final var firstTarget = TypeSubstitution.substitute(first.targetType(), firstRenaming);
        final var firstContract = TypeSubstitution.substitute(first.contractType(), firstRenaming);
        final var secondTarget = TypeSubstitution.substitute(second.targetType(), secondRenaming);
        final var secondContract = TypeSubstitution.substitute(second.contractType(), secondRenaming);
        final var variables = new HashSet<TypeParameterDescriptor>();
        firstRenaming.values().forEach(value -> variables.add((TypeParameterDescriptor) value));
        secondRenaming.values().forEach(value -> variables.add((TypeParameterDescriptor) value));

        final var substitutions = new HashMap<TypeParameterDescriptor, TypeDescriptor>();
        return unify(firstTarget, secondTarget, variables, substitutions)
                && unify(firstContract, secondContract, variables, substitutions);
    }

    private static Map<TypeParameterDescriptor, TypeDescriptor> alphaRenaming(
            final List<TypeParameterDescriptor> parameters,
            final Set<Integer> usedScopes,
            final int[] nextScope) {
        final var result = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (final var parameter : parameters) {
            while (usedScopes.contains(nextScope[0])) nextScope[0] = Math.incrementExact(nextScope[0]);
            result.put(parameter, new TypeParameterDescriptor(nextScope[0], parameter.name()));
            usedScopes.add(nextScope[0]);
            nextScope[0] = Math.incrementExact(nextScope[0]);
        }
        return result;
    }

    private static boolean unify(final TypeDescriptor left,
                                 final TypeDescriptor right,
                                 final Set<TypeParameterDescriptor> variables,
                                 final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        final var resolvedLeft = resolve(left, substitutions);
        final var resolvedRight = resolve(right, substitutions);
        if (resolvedLeft.equals(resolvedRight)) return true;

        if (resolvedLeft instanceof TypeParameterDescriptor parameter && variables.contains(parameter)) {
            return bind(parameter, resolvedRight, variables, substitutions);
        }
        if (resolvedRight instanceof TypeParameterDescriptor parameter && variables.contains(parameter)) {
            return bind(parameter, resolvedLeft, variables, substitutions);
        }
        if (resolvedLeft instanceof NullableDescriptor nullableLeft
                && resolvedRight instanceof NullableDescriptor nullableRight) {
            return unify(nullableLeft.baseType(), nullableRight.baseType(), variables, substitutions);
        }
        if (resolvedLeft instanceof ReferenceDescriptor referenceLeft
                && resolvedRight instanceof ReferenceDescriptor referenceRight) {
            return unify(referenceLeft.baseType(), referenceRight.baseType(), variables, substitutions);
        }
        if (resolvedLeft instanceof ArrayDescriptor arrayLeft
                && resolvedRight instanceof ArrayDescriptor arrayRight) {
            return unify(arrayLeft.elementType(), arrayRight.elementType(), variables, substitutions);
        }
        if (resolvedLeft instanceof GenericDescriptor genericLeft
                && resolvedRight instanceof GenericDescriptor genericRight) {
            if (!genericLeft.baseType().equals(genericRight.baseType())
                    || genericLeft.typeParameters().size() != genericRight.typeParameters().size()) return false;
            for (int index = 0; index < genericLeft.typeParameters().size(); index++) {
                if (!unify(genericLeft.typeParameters().get(index), genericRight.typeParameters().get(index),
                        variables, substitutions)) return false;
            }
            return true;
        }
        if (resolvedLeft instanceof FunctionDescriptor functionLeft
                && resolvedRight instanceof FunctionDescriptor functionRight) {
            return unifyFunctions(functionLeft, functionRight, variables, substitutions);
        }
        return false;
    }

    private static boolean unifyFunctions(final FunctionDescriptor left,
                                          final FunctionDescriptor right,
                                          final Set<TypeParameterDescriptor> variables,
                                          final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (left.arity() != right.arity()
                || left.typeParameters().size() != right.typeParameters().size()
                || left.raisedEffects().size() != right.raisedEffects().size()) return false;
        for (int index = 0; index < left.typeParameters().size(); index++) {
            final var leftParameter = left.typeParameters().get(index);
            final var rightParameter = right.typeParameters().get(index);
            if (leftParameter.bounds().size() != rightParameter.bounds().size()) return false;
            if (!leftParameter.equals(rightParameter)) substitutions.put(leftParameter, rightParameter);
            for (int boundIndex = 0; boundIndex < leftParameter.bounds().size(); boundIndex++) {
                if (!unify(leftParameter.bounds().get(boundIndex), rightParameter.bounds().get(boundIndex),
                        variables, substitutions)) return false;
            }
        }
        for (int index = 0; index < left.arity(); index++) {
            if (!unify(left.parameters().get(index), right.parameters().get(index),
                    variables, substitutions)) return false;
        }
        if (!unify(left.returnType(), right.returnType(), variables, substitutions)) return false;
        for (int index = 0; index < left.raisedEffects().size(); index++) {
            if (!unify(left.raisedEffects().get(index), right.raisedEffects().get(index),
                    variables, substitutions)) return false;
        }
        return true;
    }

    private static boolean bind(final TypeParameterDescriptor variable,
                                final TypeDescriptor type,
                                final Set<TypeParameterDescriptor> variables,
                                final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (variable.equals(type)) return true;
        if (occurs(variable, type, substitutions)) return false;
        substitutions.put(variable, type);
        return true;
    }

    private static boolean occurs(final TypeParameterDescriptor variable,
                                  final TypeDescriptor type,
                                  final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        final var resolved = resolve(type, substitutions);
        if (variable.equals(resolved)) return true;
        return switch (resolved) {
            case NullableDescriptor nullable -> occurs(variable, nullable.baseType(), substitutions);
            case ReferenceDescriptor reference -> occurs(variable, reference.baseType(), substitutions);
            case ArrayDescriptor array -> occurs(variable, array.elementType(), substitutions);
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(argument -> occurs(variable, argument, substitutions));
            case FunctionDescriptor function -> function.parameters().stream()
                    .anyMatch(parameter -> occurs(variable, parameter, substitutions))
                    || occurs(variable, function.returnType(), substitutions)
                    || function.raisedEffects().stream()
                            .anyMatch(effect -> occurs(variable, effect, substitutions));
            default -> false;
        };
    }

    private static TypeDescriptor resolve(final TypeDescriptor type,
                                          final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (!(type instanceof TypeParameterDescriptor parameter)) return type;
        final var replacement = substitutions.get(parameter);
        return replacement == null ? type : resolve(replacement, substitutions);
    }

    private static void collectScopes(final TypeDescriptor type, final Set<Integer> scopes) {
        switch (type) {
            case TypeParameterDescriptor parameter -> scopes.add(parameter.scopeId());
            case NullableDescriptor nullable -> collectScopes(nullable.baseType(), scopes);
            case ReferenceDescriptor reference -> collectScopes(reference.baseType(), scopes);
            case ArrayDescriptor array -> collectScopes(array.elementType(), scopes);
            case GenericDescriptor generic -> generic.typeParameters()
                    .forEach(argument -> collectScopes(argument, scopes));
            case FunctionDescriptor function -> {
                function.typeParameters().forEach(parameter -> {
                    scopes.add(parameter.scopeId());
                    parameter.bounds().forEach(bound -> collectScopes(bound, scopes));
                });
                function.parameters().forEach(parameter -> collectScopes(parameter, scopes));
                collectScopes(function.returnType(), scopes);
                function.raisedEffects().forEach(effect -> collectScopes(effect, scopes));
            }
            default -> { }
        }
    }
}
