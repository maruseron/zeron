package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.domain.TypeSubstitution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HktDefaultSupport {
    private HktDefaultSupport() {}

    public static FunctionDescriptor helperType(
            final Map<String, Stmt.ContractDecl> contracts,
            final Stmt.ContractDecl owner,
            final Stmt.ContractMethod method) {
        final var substitutions = ownerParameterSubstitutions(contracts, owner);
        final var methodType = (FunctionDescriptor) TypeSubstitution.substitute(
                method.typeDescriptor(), substitutions);
        final var helperParameters = new ArrayList<TypeParameterDescriptor>(
                substitutions.values().stream()
                        .map(TypeParameterDescriptor.class::cast)
                        .toList());
        helperParameters.addAll(methodType.typeParameters());
        return TypeDescriptor.functionWithEffectsOf(method.name().lexeme(),
                methodType.returnType(), methodType.parameters(), helperParameters,
                methodType.raisedEffects());
    }

    public static FunctionDescriptor effectiveMethodType(
            final Map<String, Stmt.ContractDecl> contracts,
            final Stmt.ContractDecl owner,
            final Stmt.ContractMethod method) {
        return (FunctionDescriptor) TypeSubstitution.substitute(
                method.typeDescriptor(), ownerParameterSubstitutions(contracts, owner));
    }

    public static Map<TypeParameterDescriptor, TypeDescriptor> ownerParameterSubstitutions(
            final Map<String, Stmt.ContractDecl> contracts, final Stmt.ContractDecl owner) {
        if (!owner.higherKinded()) return Map.of();
        final var result = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (final var parameter : owner.typeParameters()) {
            if (!parameter.isHigherKinded()) {
                result.put(parameter, parameter);
                continue;
            }
            final var bounds = new ArrayList<>(parameter.bounds());
            final var root = new Stmt.ContractUse(owner.name(), owner.typeParameters().stream()
                    .map(typeParameter -> (TypeDescriptor) typeParameter).toList());
            for (final var applied : ContractComposition.closure(contracts, root)) {
                    if (!applied.declaration().higherKinded()
                            || applied.use().typeArguments().stream().noneMatch(argument ->
                                    argument.equals(parameter)
                                            || argument instanceof com.maruseron.zeron.domain.TypeConstructorDescriptor type
                                                    && type.baseType().name().equals(parameter.name())
                                            || argument instanceof com.maruseron.zeron.domain.AppliedConstructorDescriptor type
                                                    && type.constructor().equals(parameter))) {
                        continue;
                    }
                    final TypeDescriptor bound = applied.use().typeArguments().isEmpty()
                            ? TypeDescriptor.ofName(applied.use().name().lexeme())
                            : TypeDescriptor.genericOf(TypeDescriptor.ofName(applied.use().name().lexeme()),
                                    applied.use().typeArguments());
                    if (!bounds.contains(bound)) bounds.add(bound);
            }
            if (bounds.equals(parameter.bounds())) {
                result.put(parameter, parameter);
            } else {
                result.put(parameter, new TypeParameterDescriptor(parameter.scopeId(), parameter.name(),
                        bounds, true, parameter.variance(), parameter.admitsNullableArguments(),
                        parameter.isEffectKind(), parameter.admitsMutableArguments()));
            }
        }
        return result;
    }
}
