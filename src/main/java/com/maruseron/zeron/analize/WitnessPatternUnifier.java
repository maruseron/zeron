package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;

import java.util.*;

final class WitnessPatternUnifier {
    private WitnessPatternUnifier() {}

    static boolean overlaps(final Stmt.Witness first, final Stmt.Witness second) {
        final var usedScopes = new HashSet<Integer>();
        first.typeParameters().forEach(parameter -> {
            usedScopes.add(parameter.scopeId());
            parameter.bounds().forEach(bound -> collectScopes(bound, usedScopes));
        });
        second.typeParameters().forEach(parameter -> {
            usedScopes.add(parameter.scopeId());
            parameter.bounds().forEach(bound -> collectScopes(bound, usedScopes));
        });
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

        var states = unify(firstTarget, secondTarget, variables, Map.of());
        states = unify(firstContract, secondContract, variables, states);
        return !states.isEmpty();
    }

    private static Map<TypeParameterDescriptor, TypeDescriptor> alphaRenaming(
            final List<TypeParameterDescriptor> parameters,
            final Set<Integer> usedScopes,
            final int[] nextScope) {
        final var result = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (final var parameter : parameters) {
            while (usedScopes.contains(nextScope[0])) nextScope[0] = Math.incrementExact(nextScope[0]);
            result.put(parameter, new TypeParameterDescriptor(nextScope[0], parameter.name(),
                    List.of(), parameter.isHigherKinded(), parameter.variance(),
                    parameter.admitsNullableArguments(), parameter.isEffectKind(),
                    parameter.admitsMutableArguments()));
            usedScopes.add(nextScope[0]);
            nextScope[0] = Math.incrementExact(nextScope[0]);
        }
        return result;
    }

    private static List<Map<TypeParameterDescriptor, TypeDescriptor>> unify(
            final TypeDescriptor left,
            final TypeDescriptor right,
            final Set<TypeParameterDescriptor> variables,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        final var resolvedLeft = resolve(left, substitutions);
        final var resolvedRight = resolve(right, substitutions);
        if (resolvedLeft.equals(resolvedRight)) return List.of(substitutions);
        if (resolvedLeft instanceof InferDescriptor || resolvedRight instanceof InferDescriptor) {
            return List.of(substitutions);
        }

        if (resolvedLeft instanceof TypeParameterDescriptor parameter && variables.contains(parameter)) {
            return bind(parameter, resolvedRight, substitutions);
        }
        if (resolvedRight instanceof TypeParameterDescriptor parameter && variables.contains(parameter)) {
            return bind(parameter, resolvedLeft, substitutions);
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
                    || genericLeft.typeParameters().size() != genericRight.typeParameters().size()) {
                return List.of();
            }
            return unifyPairs(genericLeft.typeParameters(), genericRight.typeParameters(),
                    variables, substitutions);
        }
        if (resolvedLeft instanceof TypeConstructorDescriptor constructorLeft
                && resolvedRight instanceof TypeConstructorDescriptor constructorRight) {
            if (!constructorLeft.baseType().equals(constructorRight.baseType())
                    || constructorLeft.arguments().size() != constructorRight.arguments().size()) {
                return List.of();
            }
            final var equations = new ArrayList<Equation>();
            for (int index = 0; index < constructorLeft.arguments().size(); index++) {
                final var leftArgument = constructorLeft.arguments().get(index);
                final var rightArgument = constructorRight.arguments().get(index);
                final var leftOpen = leftArgument instanceof InferDescriptor;
                final var rightOpen = rightArgument instanceof InferDescriptor;
                if (leftOpen != rightOpen) return List.of();
                if (!leftOpen) equations.add(new Equation(leftArgument, rightArgument));
            }
            return unify(equations, List.of(substitutions), variables);
        }
        if (resolvedLeft instanceof AppliedConstructorDescriptor appliedLeft
                && resolvedRight instanceof AppliedConstructorDescriptor appliedRight) {
            if (appliedLeft.constructor().equals(appliedRight.constructor())) {
                return unify(appliedLeft.argument(), appliedRight.argument(), variables, substitutions);
            }
            if (appliedLeft.constructor().isHigherKinded()
                    && appliedRight.constructor().isHigherKinded()) {
                // Distinct unknown constructors may still instantiate to the same applied type.
                return List.of(substitutions);
            }
            return List.of();
        }
        if (resolvedLeft instanceof AppliedConstructorDescriptor applied
                && resolvedRight instanceof GenericDescriptor generic) {
            return unifyAppliedConstructor(applied, generic, variables, substitutions);
        }
        if (resolvedRight instanceof AppliedConstructorDescriptor applied
                && resolvedLeft instanceof GenericDescriptor generic) {
            return unifyAppliedConstructor(applied, generic, variables, substitutions);
        }
        if (resolvedLeft instanceof AppliedConstructorDescriptor applied
                && resolvedRight instanceof ArrayDescriptor array) {
            return unifyAppliedConstructor(applied, array, variables, substitutions);
        }
        if (resolvedRight instanceof AppliedConstructorDescriptor applied
                && resolvedLeft instanceof ArrayDescriptor array) {
            return unifyAppliedConstructor(applied, array, variables, substitutions);
        }
        if (resolvedLeft instanceof FunctionDescriptor functionLeft
                && resolvedRight instanceof FunctionDescriptor functionRight) {
            return unifyFunctions(functionLeft, functionRight, variables, substitutions);
        }
        return List.of();
    }

    private static List<Map<TypeParameterDescriptor, TypeDescriptor>> unifyAppliedConstructor(
            final AppliedConstructorDescriptor applied,
            final GenericDescriptor actual,
            final Set<TypeParameterDescriptor> variables,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (!variables.contains(applied.constructor())) return List.of(substitutions);
        final var results = new ArrayList<Map<TypeParameterDescriptor, TypeDescriptor>>();
        for (int openIndex = 0; openIndex < actual.typeParameters().size(); openIndex++) {
            final var constructorArguments = new ArrayList<>(actual.typeParameters());
            constructorArguments.set(openIndex, TypeDescriptor.ofInfer());
            final var constructor = new TypeConstructorDescriptor(actual.baseType(), constructorArguments);
            final var constructorStates = unify(applied.constructor(), constructor, variables, substitutions);
            results.addAll(unify(applied.argument(), actual.typeParameters().get(openIndex),
                    variables, constructorStates));
        }
        return distinct(results);
    }

    private static List<Map<TypeParameterDescriptor, TypeDescriptor>> unifyAppliedConstructor(
            final AppliedConstructorDescriptor applied,
            final ArrayDescriptor actual,
            final Set<TypeParameterDescriptor> variables,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (!variables.contains(applied.constructor())) return List.of(substitutions);
        final var constructor = new TypeConstructorDescriptor(TypeDescriptor.ofName("Array"),
                List.of(TypeDescriptor.ofInfer()));
        final var constructorStates = unify(applied.constructor(), constructor, variables, substitutions);
        return unify(applied.argument(), actual.elementType(), variables, constructorStates);
    }

    private static List<Map<TypeParameterDescriptor, TypeDescriptor>> unifyPairs(
            final List<TypeDescriptor> left,
            final List<TypeDescriptor> right,
            final Set<TypeParameterDescriptor> variables,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (left.size() != right.size()) return List.of();
        final var equations = new ArrayList<Equation>();
        for (int index = 0; index < left.size(); index++) {
            equations.add(new Equation(left.get(index), right.get(index)));
        }
        return unify(equations, List.of(substitutions), variables);
    }

    private static List<Map<TypeParameterDescriptor, TypeDescriptor>> unifyFunctions(
            final FunctionDescriptor left,
            final FunctionDescriptor right,
            final Set<TypeParameterDescriptor> variables,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (left.arity() != right.arity()
                || left.typeParameters().size() != right.typeParameters().size()
                || left.raisedEffects().size() != right.raisedEffects().size()) return List.of();

        var states = List.of(substitutions);
        final var equations = new ArrayList<Equation>();
        for (int index = 0; index < left.typeParameters().size(); index++) {
            final var leftParameter = left.typeParameters().get(index);
            final var rightParameter = right.typeParameters().get(index);
            if (leftParameter.isHigherKinded() != rightParameter.isHigherKinded()
                    || leftParameter.variance() != rightParameter.variance()
                    || leftParameter.admitsNullableArguments() != rightParameter.admitsNullableArguments()
                    || leftParameter.isEffectKind() != rightParameter.isEffectKind()
                    || leftParameter.admitsMutableArguments()
                            != rightParameter.admitsMutableArguments()
                    || leftParameter.bounds().size() != rightParameter.bounds().size()) return List.of();
            states = relateFunctionParameters(leftParameter, rightParameter, states);
            for (int boundIndex = 0; boundIndex < leftParameter.bounds().size(); boundIndex++) {
                equations.add(new Equation(leftParameter.bounds().get(boundIndex),
                        rightParameter.bounds().get(boundIndex)));
            }
        }
        for (int index = 0; index < left.arity(); index++) {
            equations.add(new Equation(left.parameters().get(index), right.parameters().get(index)));
        }
        equations.add(new Equation(left.returnType(), right.returnType()));
        for (int index = 0; index < left.raisedEffects().size(); index++) {
            equations.add(new Equation(left.raisedEffects().get(index), right.raisedEffects().get(index)));
        }
        return unify(equations, states, variables);
    }

    private static List<Map<TypeParameterDescriptor, TypeDescriptor>> relateFunctionParameters(
            final TypeParameterDescriptor left,
            final TypeParameterDescriptor right,
            final List<Map<TypeParameterDescriptor, TypeDescriptor>> states) {
        final var results = new ArrayList<Map<TypeParameterDescriptor, TypeDescriptor>>();
        for (final var state : states) {
            final var previous = state.get(left);
            if (previous != null && !previous.equals(right)) continue;
            if (left.equals(right) || previous != null) {
                results.add(state);
            } else {
                final var updated = new HashMap<>(state);
                updated.put(left, right);
                results.add(updated);
            }
        }
        return distinct(results);
    }

    private static List<Map<TypeParameterDescriptor, TypeDescriptor>> unify(
            final TypeDescriptor left,
            final TypeDescriptor right,
            final Set<TypeParameterDescriptor> variables,
            final List<Map<TypeParameterDescriptor, TypeDescriptor>> states) {
        return unify(List.of(new Equation(left, right)), states, variables);
    }

    private static List<Map<TypeParameterDescriptor, TypeDescriptor>> unify(
            final List<Equation> equations,
            final List<Map<TypeParameterDescriptor, TypeDescriptor>> initialStates,
            final Set<TypeParameterDescriptor> variables) {
        var states = initialStates;
        for (final var equation : equations) {
            final var nextStates = new ArrayList<Map<TypeParameterDescriptor, TypeDescriptor>>();
            for (final var state : states) {
                nextStates.addAll(unify(equation.left(), equation.right(), variables, state));
            }
            states = distinct(nextStates);
            if (states.isEmpty()) return states;
        }
        return states;
    }

    private static List<Map<TypeParameterDescriptor, TypeDescriptor>> bind(
            final TypeParameterDescriptor variable,
            final TypeDescriptor type,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (variable.equals(type)) return List.of(substitutions);
        if (variable.isHigherKinded()
                != (type instanceof TypeConstructorDescriptor
                        || type instanceof TypeParameterDescriptor parameter && parameter.isHigherKinded())) {
            return List.of();
        }
        if (occurs(variable, type, substitutions)) return List.of();
        final var updated = new HashMap<>(substitutions);
        updated.put(variable, type);
        return List.of(updated);
    }

    private static boolean occurs(final TypeParameterDescriptor variable,
                                  final TypeDescriptor type,
                                  final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        final var resolved = resolve(type, substitutions);
        if (variable.equals(resolved)) return true;
        return switch (resolved) {
            case AppliedConstructorDescriptor applied ->
                    occurs(variable, applied.constructor(), substitutions)
                            || occurs(variable, applied.argument(), substitutions);
            case TypeConstructorDescriptor constructor -> constructor.arguments().stream()
                    .anyMatch(argument -> occurs(variable, argument, substitutions));
            case NullableDescriptor nullable -> occurs(variable, nullable.baseType(), substitutions);
            case ReferenceDescriptor reference -> occurs(variable, reference.baseType(), substitutions);
            case ArrayDescriptor array -> occurs(variable, array.elementType(), substitutions);
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(argument -> occurs(variable, argument, substitutions));
            case FunctionDescriptor function -> function.parameters().stream()
                    .anyMatch(parameter -> occurs(variable, parameter, substitutions))
                    || occurs(variable, function.returnType(), substitutions)
                    || function.typeParameters().stream()
                            .flatMap(parameter -> parameter.bounds().stream())
                            .anyMatch(bound -> occurs(variable, bound, substitutions))
                    || function.raisedEffects().stream()
                            .anyMatch(effect -> occurs(variable, effect, substitutions));
            default -> false;
        };
    }

    private static TypeDescriptor resolve(final TypeDescriptor type,
                                          final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (type instanceof TypeParameterDescriptor parameter) {
            final var replacement = substitutions.get(parameter);
            return replacement == null ? type : resolve(replacement, substitutions);
        }
        if (type instanceof AppliedConstructorDescriptor applied) {
            final var constructor = resolve(applied.constructor(), substitutions);
            final var argument = resolve(applied.argument(), substitutions);
            if (constructor instanceof TypeConstructorDescriptor typeConstructor) {
                return resolve(typeConstructor.apply(argument), substitutions);
            }
            if (constructor instanceof TypeParameterDescriptor parameter && parameter.isHigherKinded()) {
                return new AppliedConstructorDescriptor(parameter, argument);
            }
        }
        return type;
    }

    private static void collectScopes(final TypeDescriptor type, final Set<Integer> scopes) {
        switch (type) {
            case TypeParameterDescriptor parameter -> scopes.add(parameter.scopeId());
            case AppliedConstructorDescriptor applied -> {
                collectScopes(applied.constructor(), scopes);
                collectScopes(applied.argument(), scopes);
            }
            case TypeConstructorDescriptor constructor -> constructor.arguments()
                    .forEach(argument -> collectScopes(argument, scopes));
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

    private static List<Map<TypeParameterDescriptor, TypeDescriptor>> distinct(
            final List<Map<TypeParameterDescriptor, TypeDescriptor>> states) {
        return List.copyOf(new LinkedHashSet<>(states));
    }

    private record Equation(TypeDescriptor left, TypeDescriptor right) {}
}
