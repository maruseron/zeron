package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TypeClassEvidence {
    private TypeClassEvidence() {}

    record WitnessEntry(Stmt.Witness declaration, String packageName) {}

    static void register(final ResolutionContext context, final Stmt.Witness witness,
                         final String packageName) {
        validateType(context, witness.contractType(), witness.name());
        validateType(context, witness.targetType(), witness.name());
        final var contract = context.contracts.get(nominalName(witness.contractType()));
        final var target = context.classes.get(nominalName(witness.targetType()));
        if (contract == null || target == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    witness.name(), "A constructor witness must name a contract and a class."));
        }
        if (!packageName.equals(packageOf(contract.name().lexeme()))
                && !packageName.equals(packageOf(target.name().lexeme()))) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    witness.name(), "A witness must be declared in the contract or target class package."));
        }

        if (target.typeParameters().size() != witness.typeParameters().size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    witness.name(), "Witness target must match the target class's complete type pattern."));
        }
        final var targetSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        final var classPattern = target.typeParameters().isEmpty()
                ? (TypeDescriptor) TypeDescriptor.ofName(target.name().lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(target.name().lexeme()),
                        target.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
        for (final var parameter : target.typeParameters()) {
            targetSubstitutions.put(parameter, parameter);
        }
        if (!matchPattern(witness.targetType(), classPattern, witness.typeParameters(), targetSubstitutions)
                || !targetSubstitutions.keySet().containsAll(witness.typeParameters())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    witness.name(), "Witness target must match the target class's complete type pattern."));
        }
        final var contractArguments = typeArguments(witness.contractType());
        if (contractArguments.size() != contract.typeParameters().size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    witness.name(), "Witness contract type has the wrong number of type arguments."));
        }
        final var classSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < target.typeParameters().size(); index++) {
            classSubstitutions.put(target.typeParameters().get(index), witness.typeParameters().get(index));
        }
        final var classContract = target.contractUses().stream()
                .filter(use -> use.name().lexeme().equals(contract.name().lexeme()))
                .filter(use -> use.typeArguments().size() == contractArguments.size())
                .filter(use -> {
                    for (int index = 0; index < use.typeArguments().size(); index++) {
                        if (!contractArguments.get(index)
                                .equals(TypeSubstitution.substitute(use.typeArguments().get(index),
                                        classSubstitutions))) return false;
                    }
                    return true;
                })
                .findFirst().orElse(null);
        if (classContract == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    witness.name(), "Witness target must conform to the fully applied witness contract."));
        }

        final var mappedRequirements = new java.util.HashSet<String>();
        for (final var mapping : witness.mappings()) {
            if (!mappedRequirements.add(mapping.requirementName().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        mapping.requirementName(), "Duplicate witness factory mapping."));
            }
            if (!mapping.targetType().equals(witness.targetType())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        mapping.constructorName(),
                        "A witness factory must target the witness's implementing class type."));
            }
            final var required = contract.namedConstructors().stream()
                    .filter(value -> value.name().lexeme().equals(mapping.requirementName().lexeme()))
                    .findFirst().orElse(null);
            if (required == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        mapping.requirementName(), "Unknown contract factory requirement."));
            }
            final var implementation = target.namedConstructors().stream()
                    .filter(value -> value.name().lexeme().equals(mapping.constructorName().lexeme()))
                    .filter(Stmt.NamedConstructor::isPublic)
                    .findFirst().orElse(null);
            if (implementation == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                        mapping.constructorName(), "Witness target must be a public named constructor."));
            }
            final var contractSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            for (int index = 0; index < contract.typeParameters().size(); index++) {
                contractSubstitutions.put(contract.typeParameters().get(index),
                        TypeSubstitution.substitute(contractArguments.get(index), targetSubstitutions));
            }
            final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                    required.typeDescriptor(), contractSubstitutions);
            final var implementationType = (FunctionDescriptor) TypeSubstitution.substitute(
                    implementation.typeDescriptor(), targetSubstitutions);
            if (required.variadic() != implementation.variadic()
                    || !DeclarationResolver.compatibleMethodSignatures(context, requiredType,
                            required.variadic(), implementationType, implementation.variadic())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE,
                        mapping.constructorName(), "Mapped constructor does not satisfy the factory requirement."));
            }
        }

        for (final var previous : context.typeClassWitnesses) {
            if (nominalName(previous.declaration().contractType()).equals(contract.name().lexeme())
                    && nominalName(previous.declaration().targetType()).equals(target.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        witness.name(), "Overlapping witnesses for the same class and contract are not allowed."));
            }
        }
        context.typeClassWitnesses.add(new WitnessEntry(witness, packageName));
    }

    private static void validateType(final ResolutionContext context, final TypeDescriptor type,
                                     final com.maruseron.zeron.scan.Token where) {
        TypeResolver.validateType(context, type, where);
        final var base = type instanceof ReferenceDescriptor reference ? reference.baseType() : type;
        if (!(base instanceof NominalDescriptor || base instanceof GenericDescriptor)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    where, "Witness types must be nominal class or contract types."));
        }
    }

    private static List<TypeDescriptor> typeArguments(final TypeDescriptor type) {
        final var base = type instanceof ReferenceDescriptor reference ? reference.baseType() : type;
        return base instanceof GenericDescriptor generic ? generic.typeParameters() : List.of();
    }

    private static boolean matchPattern(final TypeDescriptor pattern, final TypeDescriptor actual,
                                        final List<TypeParameterDescriptor> parameters,
                                        final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (pattern instanceof TypeParameterDescriptor parameter && parameters.contains(parameter)) {
            final var previous = substitutions.putIfAbsent(parameter, actual);
            return previous == null || previous.equals(actual);
        }
        if (pattern instanceof ReferenceDescriptor patternReference
                && actual instanceof ReferenceDescriptor actualReference) {
            return matchPattern(patternReference.baseType(), actualReference.baseType(), parameters, substitutions);
        }
        if (pattern instanceof GenericDescriptor patternGeneric
                && actual instanceof GenericDescriptor actualGeneric) {
            if (!patternGeneric.baseType().equals(actualGeneric.baseType())
                    || patternGeneric.typeParameters().size() != actualGeneric.typeParameters().size()) return false;
            for (int index = 0; index < patternGeneric.typeParameters().size(); index++) {
                if (!matchPattern(patternGeneric.typeParameters().get(index),
                        actualGeneric.typeParameters().get(index), parameters, substitutions)) return false;
            }
            return true;
        }
        return pattern.equals(actual);
    }

    private static String packageOf(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? "" : qualifiedName.substring(0, separator);
    }

    static Stmt.WitnessFactoryMapping explicitMapping(final ResolutionContext context,
                                                      final TypeDescriptor contractType,
                                                      final TypeDescriptor targetType,
                                                      final String requirementName) {
        final var matches = new ArrayList<Stmt.WitnessFactoryMapping>();
        for (final var entry : context.typeClassWitnesses) {
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            if (!matchPattern(entry.declaration().contractType(), contractType,
                    entry.declaration().typeParameters(), substitutions)
                    || !matchPattern(entry.declaration().targetType(), targetType,
                            entry.declaration().typeParameters(), substitutions)) continue;
            entry.declaration().mappings().stream()
                    .filter(mapping -> mapping.requirementName().lexeme().equals(requirementName))
                    .forEach(matches::add);
        }
        if (matches.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    matches.getFirst().requirementName(),
                    "Ambiguous constructor witness for '" + contractType + "'."));
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    static List<Expr.EvidenceArgument> requirements(final ResolutionContext context,
                                                   final TypeParameterDescriptor parameter) {
        final var result = new ArrayList<Expr.EvidenceArgument>();
        for (int boundIndex = 0; boundIndex < parameter.bounds().size(); boundIndex++) {
            final var bound = parameter.bounds().get(boundIndex);
            final var contractName = nominalName(bound);
            final var contract = context.contracts.get(contractName);
            if (contract == null) continue;
            for (int constructorIndex = 0; constructorIndex < contract.namedConstructors().size(); constructorIndex++) {
                final var constructor = contract.namedConstructors().get(constructorIndex);
                result.add(new Expr.EvidenceArgument(parameter, boundIndex, constructorIndex,
                        null, null, constructor.typeDescriptor(), null));
            }
        }
        return List.copyOf(result);
    }

    static List<Expr.EvidenceArgument> selectArguments(
            final ResolutionContext context, final List<TypeParameterDescriptor> parameters,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
            final com.maruseron.zeron.scan.Token where) {
        final var evidence = new ArrayList<Expr.EvidenceArgument>();
        for (final var parameter : parameters) {
            final var actual = substitutions.get(parameter);
            if (actual == null) continue;
            for (final var slot : requirements(context, parameter)) {
                final var requiredBound = TypeSubstitution.substitute(
                        parameter.bounds().get(slot.boundIndex()), substitutions);
                final var contract = context.contracts.get(nominalName(requiredBound));
                if (contract == null || slot.constructorIndex() >= contract.namedConstructors().size()) continue;
                evidence.add(select(context, parameter, slot.boundIndex(), slot.constructorIndex(),
                        requiredBound, actual, contract.namedConstructors().get(slot.constructorIndex()), where));
            }
        }
        return List.copyOf(evidence);
    }

    static Expr.EvidenceArgument select(final ResolutionContext context,
                                        final TypeParameterDescriptor targetParameter,
                                        final int targetBoundIndex,
                                        final int targetConstructorIndex,
                                        final TypeDescriptor requiredBound,
                                        final TypeDescriptor actualType,
                                        final Stmt.NamedContractConstructor requirement,
                                        final com.maruseron.zeron.scan.Token where) {
        if (actualType instanceof TypeParameterDescriptor actualParameter) {
            for (int boundIndex = 0; boundIndex < actualParameter.bounds().size(); boundIndex++) {
                if (!actualParameter.bounds().get(boundIndex).equals(requiredBound)) continue;
                final var name = nominalName(requiredBound);
                final var contract = context.contracts.get(name);
                if (contract == null) continue;
                final int constructorIndex = contract.namedConstructors().indexOf(requirement);
                if (constructorIndex >= 0) {
                    return new Expr.EvidenceArgument(targetParameter, targetBoundIndex, targetConstructorIndex,
                            null, null, requirement.typeDescriptor(),
                            Expr.evidenceToken(actualParameter, boundIndex, constructorIndex));
                }
            }
        }
        final var concrete = actualType instanceof ReferenceDescriptor reference ? reference.baseType() : actualType;
        final var candidate = context.classes.get(nominalName(concrete));
        final var matches = new ArrayList<Expr.EvidenceArgument>();
        if (candidate != null && context.typeCompatibility.isContractProjection(requiredBound, concrete)) {
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            final var explicitWitnesses = new ArrayList<Map.Entry<WitnessEntry,
                    Map<TypeParameterDescriptor, TypeDescriptor>>>();
            for (final var entry : context.typeClassWitnesses) {
                if (!nominalName(entry.declaration().contractType()).equals(nominalName(requiredBound))) continue;
                final var witnessSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
                if (matchPattern(entry.declaration().targetType(), concrete,
                        entry.declaration().typeParameters(), witnessSubstitutions)
                        && matchPattern(entry.declaration().contractType(), requiredBound,
                                entry.declaration().typeParameters(), witnessSubstitutions)) {
                    explicitWitnesses.add(Map.entry(entry, witnessSubstitutions));
                }
            }
            if (explicitWitnesses.size() > 1) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, where,
                        "Ambiguous constructor witness for bound '" + requiredBound + "'."));
            }
            final var explicitMapping = explicitWitnesses.isEmpty() ? null
                    : explicitWitnesses.getFirst().getKey().declaration().mappings().stream()
                            .filter(mapping -> mapping.requirementName().lexeme()
                                    .equals(requirement.name().lexeme()))
                            .findFirst().orElse(null);
            for (int index = 0; index < candidate.typeParameters().size(); index++) {
                substitutions.put(candidate.typeParameters().get(index),
                        typeArguments(concrete).get(index));
            }
            final var implementation = candidate.namedConstructors().stream()
                    .filter(value -> value.name().lexeme().equals(explicitMapping == null
                            ? requirement.name().lexeme() : explicitMapping.constructorName().lexeme()))
                    .filter(Stmt.NamedConstructor::isPublic)
                    .filter(value -> value.variadic() == requirement.variadic())
                    .filter(value -> DeclarationResolver.compatibleMethodSignatures(context,
                            (FunctionDescriptor) TypeSubstitution.substitute(requirement.typeDescriptor(),
                                    contractSubstitutions(context, requiredBound)),
                            requirement.variadic(),
                            (FunctionDescriptor) TypeSubstitution.substitute(value.typeDescriptor(), substitutions),
                            value.variadic()))
                    .findFirst().orElse(null);
            if (implementation != null) {
                matches.add(new Expr.EvidenceArgument(targetParameter, targetBoundIndex, targetConstructorIndex,
                        candidate.name().lexeme(), implementation.name().lexeme(),
                        implementation.typeDescriptor(), null));
            }
        }
        if (matches.size() != 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, where,
                    matches.isEmpty() ? "No constructor witness satisfies bound '" + requiredBound + "'."
                            : "Ambiguous constructor witness for bound '" + requiredBound + "'."));
        }
        return matches.getFirst();
    }

    static String nominalName(final TypeDescriptor type) {
        final var base = type instanceof ReferenceDescriptor reference ? reference.baseType() : type;
        return base instanceof GenericDescriptor generic ? generic.baseType().name() : base.name();
    }

    private static java.util.Map<TypeParameterDescriptor, TypeDescriptor> contractSubstitutions(
            final ResolutionContext context, final TypeDescriptor bound) {
        final var contract = context.contracts.get(nominalName(bound));
        final var base = bound instanceof ReferenceDescriptor reference ? reference.baseType() : bound;
        final var args = base instanceof GenericDescriptor generic
                ? generic.typeParameters() : List.<TypeDescriptor>of();
        final var result = new java.util.LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        if (contract != null) {
            for (int i = 0; i < Math.min(args.size(), contract.typeParameters().size()); i++) {
                result.put(contract.typeParameters().get(i), args.get(i));
            }
        }
        return result;
    }
}
