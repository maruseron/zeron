package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TypeClassEvidence {
    private TypeClassEvidence() {}

    record WitnessEntry(Stmt.Witness declaration, String packageName) {}
    record WitnessMethodSelection(Stmt.WitnessMethod method, String helperQualifiedName,
                                  FunctionDescriptor helperType,
                                  Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {}

    static void register(final ResolutionContext context, final Stmt.Witness witness,
                         final String packageName) {
        validateType(context, witness.contractType(), witness.name());
        validateType(context, witness.targetType(), witness.name());
        witness.typeParameters().forEach(parameter ->
                TypeResolver.validateTypeParameterBound(context, parameter, witness.name()));
        final var contract = context.contracts.get(nominalName(witness.contractType()));
        final var target = context.classes.get(nominalName(witness.targetType()));
        if (contract == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    witness.name(), "A witness must name a contract and a supported target type."));
        }
        if (!packageName.equals(packageOf(contract.name().lexeme()))
                && !packageName.equals(packageOfTarget(witness.targetType()))) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    witness.name(), "A witness must be declared in the contract or target class package."));
        }

        if (target != null && target.typeParameters().size() != witness.typeParameters().size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    witness.name(), "Witness target must match the target class's complete type pattern."));
        }
        final var targetSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        final var classPattern = target == null ? witness.targetType() : target.typeParameters().isEmpty()
                ? (TypeDescriptor) TypeDescriptor.ofName(target.name().lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(target.name().lexeme()),
                        target.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
        if (target != null) {
            for (final var parameter : target.typeParameters()) {
                targetSubstitutions.put(parameter, parameter);
            }
        }
        if (target != null && (!matchPattern(witness.targetType(), classPattern,
                    witness.typeParameters(), targetSubstitutions)
                || !targetSubstitutions.keySet().containsAll(witness.typeParameters()))) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    witness.name(), "Witness target must match the target class's complete type pattern."));
        }
        if (target == null && !witness.typeParameters().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    witness.name(), "Generic witness targets must be generic classes."));
        }
        final var contractArguments = typeArguments(witness.contractType());
        if (contractArguments.size() != contract.typeParameters().size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    witness.name(), "Witness contract type has the wrong number of type arguments."));
        }
        final var classSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        if (target != null) {
            for (int index = 0; index < target.typeParameters().size(); index++) {
                classSubstitutions.put(target.typeParameters().get(index), witness.typeParameters().get(index));
            }
        }
        final var classContract = target == null ? null : target.contractUses().stream()
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
        if (target != null && classContract != null && !witness.methods().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    witness.name(), "A witness cannot override methods of a nominally conforming class."));
        }
        if (target == null || classContract == null) {
            synthesizeDefaultWitnessMethods(context, witness, contract, targetSubstitutions, contractArguments);
            validateWitnessMethods(context, witness, contract, targetSubstitutions, contractArguments);
        }

        final var mappedRequirements = new java.util.HashSet<String>();
        for (final var mapping : witness.mappings()) {
            if (target == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        mapping.constructorName(), "Factory mappings require a class target."));
            }
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
            if ((previous.packageName().equals(packageName) || isActive(context, previous))
                    && nominalName(previous.declaration().contractType()).equals(contract.name().lexeme())
                    && nominalName(previous.declaration().targetType())
                            .equals(nominalName(witness.targetType()))) {
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
        if (!(base instanceof NominalDescriptor || base instanceof GenericDescriptor
                || base instanceof IntDescriptor || base instanceof FloatDescriptor
                || base instanceof BooleanDescriptor || base instanceof StringDescriptor
                || base instanceof UnitDescriptor)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    where, "Witness types must be nominal types or supported built-in scalar types."));
        }
    }

    private static void synthesizeDefaultWitnessMethods(final ResolutionContext context,
                                                        final Stmt.Witness witness,
                                                        final Stmt.ContractDecl contract,
                                                        final Map<TypeParameterDescriptor, TypeDescriptor> targetSubstitutions,
                                                        final List<TypeDescriptor> contractArguments) {
        final var implementedNames = witness.methods().stream()
                .map(method -> method.requirementName().lexeme())
                .collect(java.util.stream.Collectors.toSet());
        final var contractSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < contract.typeParameters().size(); index++) {
            contractSubstitutions.put(contract.typeParameters().get(index),
                    TypeSubstitution.substitute(contractArguments.get(index), targetSubstitutions));
        }
        for (final var required : contract.methods()) {
            if (!required.isDefault() || implementedNames.contains(required.name().lexeme())) continue;
            if (required.isMutating()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                        required.name(), "Witnesses cannot use mutating default contract methods."));
            }
            final var sourceType = (FunctionDescriptor) TypeSubstitution.substitute(
                    required.typeDescriptor(), contractSubstitutions);
            final var sourceMethod = new Stmt.Function(required.name(), required.parameters(), sourceType,
                    required.body(), false, required.defaultValues(), required.minimumArity(),
                    required.variadic());
            final var helperName = new com.maruseron.zeron.scan.Token(
                    com.maruseron.zeron.scan.TokenType.IDENTIFIER,
                    "$zeron$witness$" + witness.name().span().start().offset()
                            + "$" + required.name().lexeme(),
                    null, required.name().span());
            final var helperParameters = new ArrayList<com.maruseron.zeron.scan.Token>();
            helperParameters.add(new com.maruseron.zeron.scan.Token(
                    com.maruseron.zeron.scan.TokenType.THIS, "this", null, required.name().span()));
            helperParameters.addAll(required.parameters());
            final var helperTypes = new ArrayList<TypeDescriptor>();
            helperTypes.add(witness.targetType());
            helperTypes.addAll(sourceType.parameters());
            final var helperTypeParameters = new ArrayList<TypeParameterDescriptor>(witness.typeParameters());
            helperTypeParameters.addAll(sourceType.typeParameters());
            final var helperType = TypeDescriptor.functionWithEffectsOf(helperName.lexeme(),
                    sourceType.returnType(), helperTypes, helperTypeParameters, sourceType.raisedEffects());
            final var implementation = new Stmt.Function(helperName, helperParameters, helperType,
                    required.body(), false, required.defaultValues(),
                    1 + required.minimumArity(), required.variadic());
            final var method = new Stmt.WitnessMethod(required.name(), sourceMethod, implementation);
            witness.addMethod(method);
            context.declarationRegistrar.registerFunction(context.packageName, implementation);
        }
    }

    private static void validateWitnessMethods(final ResolutionContext context, final Stmt.Witness witness,
                                               final Stmt.ContractDecl contract,
                                               final Map<TypeParameterDescriptor, TypeDescriptor> targetSubstitutions,
                                               final List<TypeDescriptor> contractArguments) {
        final var contractSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < contract.typeParameters().size(); index++) {
            contractSubstitutions.put(contract.typeParameters().get(index),
                    TypeSubstitution.substitute(contractArguments.get(index), targetSubstitutions));
        }
        final var implementations = new LinkedHashMap<String, Stmt.WitnessMethod>();
        for (final var method : witness.methods()) {
            if (implementations.putIfAbsent(method.requirementName().lexeme(), method) != null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        method.requirementName(), "Duplicate witness method implementation."));
            }
            final var required = contract.methods().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(method.requirementName().lexeme()))
                    .findFirst().orElse(null);
            if (required == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        method.requirementName(), "Unknown contract method requirement."));
            }
            if (required.isMutating()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                        method.requirementName(), "Witnesses cannot implement mutating contract methods."));
            }
            final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                    required.typeDescriptor(), contractSubstitutions);
            if (!DeclarationResolver.compatibleMethodSignatures(context, requiredType, required.variadic(),
                    method.methodSignature().typeDescriptor(), method.methodSignature().variadic())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE,
                        method.requirementName(), "Witness method does not satisfy the contract requirement."));
            }
        }
        for (final var required : contract.methods()) {
            if (required.isMutating()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                        required.name(), "Witnesses cannot implement contracts with mutating methods."));
            }
            if (!required.isDefault() && !implementations.containsKey(required.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        witness.name(), "Missing witness implementation for contract method '"
                                + required.name().lexeme() + "'."));
            }
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
        if (pattern instanceof ReferenceDescriptor patternReference) {
            return matchPattern(patternReference.baseType(), actual, parameters, substitutions);
        }
        if (actual instanceof ReferenceDescriptor actualReference) {
            return matchPattern(pattern, actualReference.baseType(), parameters, substitutions);
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

    private static String packageOfTarget(final TypeDescriptor target) {
        final var base = target instanceof ReferenceDescriptor reference ? reference.baseType() : target;
        if (base instanceof IntDescriptor || base instanceof FloatDescriptor || base instanceof BooleanDescriptor
                || base instanceof StringDescriptor || base instanceof UnitDescriptor) return "zeron.lang";
        return packageOf(nominalName(base));
    }

    static Stmt.WitnessFactoryMapping explicitMapping(final ResolutionContext context,
                                                      final TypeDescriptor contractType,
                                                      final TypeDescriptor targetType,
                                                      final String requirementName) {
        final var matches = new ArrayList<Stmt.WitnessFactoryMapping>();
        for (final var entry : context.typeClassWitnesses) {
            if (!isActive(context, entry)) continue;
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
            for (int methodIndex = 0; methodIndex < contract.methods().size(); methodIndex++) {
                final var method = contract.methods().get(methodIndex);
                final var methodParameters = new ArrayList<TypeDescriptor>();
                methodParameters.add(parameter);
                methodParameters.addAll(method.typeDescriptor().parameters());
                final var methodType = TypeDescriptor.functionWithEffectsOf(method.name().lexeme(),
                        method.typeDescriptor().returnType(), methodParameters,
                        method.typeDescriptor().typeParameters(), method.typeDescriptor().raisedEffects());
                result.add(new Expr.EvidenceArgument(parameter, boundIndex, methodIndex,
                        null, null, methodType,
                        Expr.methodEvidenceToken(parameter, boundIndex, methodIndex), true));
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
                if (contract == null) continue;
                if (slot.instanceMethod()) {
                    if (slot.constructorIndex() >= contract.methods().size()) continue;
                    evidence.add(selectMethodEvidence(context, parameter, slot.boundIndex(),
                            slot.constructorIndex(), requiredBound, actual,
                            contract.methods().get(slot.constructorIndex()), where));
                    continue;
                }
                if (slot.constructorIndex() >= contract.namedConstructors().size()) continue;
                evidence.add(select(context, parameter, slot.boundIndex(), slot.constructorIndex(),
                        requiredBound, actual, contract.namedConstructors().get(slot.constructorIndex()), where));
            }
        }
        return List.copyOf(evidence);
    }

    static boolean hasEvidence(final ResolutionContext context, final TypeDescriptor contractType,
                               final TypeDescriptor actualType,
                               final com.maruseron.zeron.scan.Token where) {
        return hasEvidence(context, contractType, actualType, where, new HashSet<>());
    }

    private static boolean hasEvidence(final ResolutionContext context, final TypeDescriptor contractType,
                                       final TypeDescriptor actualType,
                                       final com.maruseron.zeron.scan.Token where,
                                       final Set<String> resolving) {
        if (context.typeCompatibility.isContractProjection(contractType, actualType)) return true;
        if (actualType instanceof TypeParameterDescriptor parameter
                && parameter.bounds().contains(contractType)) return true;
        final var evidenceKey = contractType + " for " + actualType;
        if (!resolving.add(evidenceKey)) return false;
        final var actual = actualType instanceof ReferenceDescriptor reference
                ? reference.baseType() : actualType;
        try {
            var matchingWitnesses = 0;
            for (final var entry : context.typeClassWitnesses) {
                if (!isActive(context, entry)) continue;
                final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
                if (matchPattern(entry.declaration().contractType(), contractType,
                        entry.declaration().typeParameters(), substitutions)
                        && matchPattern(entry.declaration().targetType(), actual,
                                entry.declaration().typeParameters(), substitutions)
                        && prerequisitesAvailable(context, entry.declaration(), substitutions, where, resolving)) {
                    matchingWitnesses++;
                }
            }
            if (matchingWitnesses > 1) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, where,
                        "Ambiguous witness for contract '" + contractType + "' and type '" + actualType + "'."));
            }
            return matchingWitnesses == 1 || isBuiltInDisplay(contractType, actual);
        } finally {
            resolving.remove(evidenceKey);
        }
    }

    private static boolean prerequisitesAvailable(
            final ResolutionContext context, final Stmt.Witness witness,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
            final com.maruseron.zeron.scan.Token where, final Set<String> resolving) {
        for (final var parameter : witness.typeParameters()) {
            final var actual = substitutions.get(parameter);
            if (actual == null) return false;
            for (final var bound : parameter.bounds()) {
                final var required = TypeSubstitution.substitute(bound, substitutions);
                if (!hasEvidence(context, required, actual, where, resolving)) return false;
            }
        }
        return true;
    }

    private static Expr.EvidenceArgument selectMethodEvidence(
            final ResolutionContext context, final TypeParameterDescriptor targetParameter,
            final int targetBoundIndex, final int targetMethodIndex,
            final TypeDescriptor requiredBound, final TypeDescriptor actualType,
            final Stmt.ContractMethod requirement,
            final com.maruseron.zeron.scan.Token where) {
        if (actualType instanceof TypeParameterDescriptor actualParameter) {
            for (int boundIndex = 0; boundIndex < actualParameter.bounds().size(); boundIndex++) {
                if (actualParameter.bounds().get(boundIndex).equals(requiredBound)) {
                    return new Expr.EvidenceArgument(targetParameter, targetBoundIndex, targetMethodIndex,
                            null, null, requirement.typeDescriptor(),
                            Expr.methodEvidenceToken(actualParameter, boundIndex, targetMethodIndex),
                            true);
                }
            }
        }

        final var concrete = actualType instanceof ReferenceDescriptor reference
                ? reference.baseType() : actualType;
        final var methodParameters = new ArrayList<TypeDescriptor>();
        methodParameters.add(concrete);
        final var substitutions = contractSubstitutions(context, requiredBound);
        final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                requirement.typeDescriptor(), substitutions);
        methodParameters.addAll(requiredType.parameters());
        final var methodType = TypeDescriptor.functionWithEffectsOf(requirement.name().lexeme(),
                requiredType.returnType(), methodParameters, requiredType.typeParameters(),
                requiredType.raisedEffects());

        if (context.typeCompatibility.isContractProjection(requiredBound, concrete)) {
            return new Expr.EvidenceArgument(targetParameter, targetBoundIndex, targetMethodIndex,
                    nominalName(requiredBound), requirement.name().lexeme(), methodType, null, true,
                    Expr.EvidenceHandleKind.INTERFACE);
        }

        final var explicit = findMethodWitness(context, requiredBound, concrete,
                requirement.name().lexeme(), where);
        if (explicit != null) {
            final var helperType = (FunctionDescriptor) TypeSubstitution.substitute(
                    explicit.method().implementation().typeDescriptor(), explicit.substitutions());
            final var witnessTypeParameters = explicit.method().implementation().typeDescriptor()
                    .typeParameters().stream()
                    .filter(explicit.substitutions()::containsKey)
                    .toList();
            final var prerequisiteEvidence = selectArguments(context, witnessTypeParameters,
                    explicit.substitutions(), where);
            return new Expr.EvidenceArgument(targetParameter, targetBoundIndex, targetMethodIndex,
                    explicit.helperQualifiedName(), explicit.method().implementation().name().lexeme(),
                    helperType, null, true, Expr.EvidenceHandleKind.STATIC, prerequisiteEvidence);
        }

        final var builtin = builtinDisplayHandle(requiredBound, concrete, requirement, targetParameter,
                targetBoundIndex, targetMethodIndex);
        if (builtin != null) return builtin;
        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, where,
                "No method witness satisfies bound '" + requiredBound + "'."));
        throw new IllegalStateException("Unreachable");
    }

    private static WitnessMethodSelection findMethodWitness(
            final ResolutionContext context, final TypeDescriptor contractType,
            final TypeDescriptor concrete, final String methodName,
            final com.maruseron.zeron.scan.Token where) {
        return selectMethod(context, concrete, contractType, methodName, where);
    }

    private static Expr.EvidenceArgument builtinDisplayHandle(
            final TypeDescriptor requiredBound, final TypeDescriptor concrete,
            final Stmt.ContractMethod requirement, final TypeParameterDescriptor parameter,
            final int boundIndex, final int methodIndex) {
        if (!isBuiltInDisplay(requiredBound, concrete)
                || !requirement.name().lexeme().equals("toString")
                || !(requirement.typeDescriptor().returnType() instanceof StringDescriptor)) return null;
        final String owner;
        final String method;
        final Expr.EvidenceHandleKind kind;
        final FunctionDescriptor signature;
        if (concrete instanceof IntDescriptor || concrete instanceof FloatDescriptor
                || concrete instanceof BooleanDescriptor) {
            owner = "java.lang.String";
            method = "valueOf";
            kind = Expr.EvidenceHandleKind.STATIC;
            signature = TypeDescriptor.functionOf(method, TypeDescriptor.ofString(), concrete);
        } else if (concrete instanceof StringDescriptor) {
            owner = "java.lang.String";
            method = "toString";
            kind = Expr.EvidenceHandleKind.VIRTUAL;
            signature = TypeDescriptor.functionOf(method, TypeDescriptor.ofString(), concrete);
        } else if (concrete instanceof UnitDescriptor) {
            owner = "zeron.lang.Unit";
            method = "toString";
            kind = Expr.EvidenceHandleKind.VIRTUAL;
            signature = TypeDescriptor.functionOf(method, TypeDescriptor.ofString(), concrete);
        } else {
            return null;
        }
        return new Expr.EvidenceArgument(parameter, boundIndex, methodIndex, owner,
                method, signature, null, true, kind);
    }

    private static boolean isBuiltInDisplay(final TypeDescriptor contractType,
                                            final TypeDescriptor concreteType) {
        final var concrete = concreteType instanceof ReferenceDescriptor reference
                ? reference.baseType() : concreteType;
        final var arguments = typeArguments(contractType);
        final var displayTarget = arguments.isEmpty() ? null
                : arguments.getFirst() instanceof ReferenceDescriptor reference
                    ? reference.baseType() : arguments.getFirst();
        return nominalName(contractType).equals("zeron.lang.Display")
                && arguments.size() == 1 && displayTarget.equals(concrete)
                && (concrete instanceof IntDescriptor || concrete instanceof FloatDescriptor
                    || concrete instanceof BooleanDescriptor || concrete instanceof StringDescriptor
                    || concrete instanceof UnitDescriptor);
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
                if (!isActive(context, entry)) continue;
                if (!nominalName(entry.declaration().contractType()).equals(nominalName(requiredBound))) continue;
                final var witnessSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
                if (matchPattern(entry.declaration().targetType(), concrete,
                        entry.declaration().typeParameters(), witnessSubstitutions)
                        && matchPattern(entry.declaration().contractType(), requiredBound,
                                entry.declaration().typeParameters(), witnessSubstitutions)
                        && prerequisitesAvailable(context, entry.declaration(), witnessSubstitutions,
                                where, new HashSet<>())) {
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

    static WitnessMethodSelection selectMethod(final ResolutionContext context,
                                               final TypeDescriptor actualType,
                                               final String methodName,
                                               final com.maruseron.zeron.scan.Token where) {
        return selectMethod(context, actualType, null, methodName, where);
    }

    static WitnessMethodSelection selectMethod(final ResolutionContext context,
                                               final TypeDescriptor actualType,
                                               final TypeDescriptor requiredContractType,
                                               final String methodName,
                                               final com.maruseron.zeron.scan.Token where) {
        final var actual = actualType instanceof ReferenceDescriptor reference
                ? reference.baseType() : actualType;
        final var matches = new ArrayList<WitnessMethodSelection>();
        for (final var entry : context.typeClassWitnesses) {
            if (!isActive(context, entry)) continue;
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            if (!matchPattern(entry.declaration().targetType(), actual,
                    entry.declaration().typeParameters(), substitutions)) continue;
            if (requiredContractType != null && !matchPattern(entry.declaration().contractType(),
                    requiredContractType, entry.declaration().typeParameters(), substitutions)) continue;
            if (!prerequisitesAvailable(context, entry.declaration(), substitutions,
                where, new HashSet<>())) continue;
            for (final var method : entry.declaration().methods()) {
                if (!method.requirementName().lexeme().equals(methodName)) continue;
                final var helperType = (FunctionDescriptor) TypeSubstitution.substitute(
                        method.implementation().typeDescriptor(), substitutions);
                matches.add(new WitnessMethodSelection(method,
                        qualifyPackage(entry.packageName(), method.implementation().name().lexeme()),
                        helperType, Map.copyOf(substitutions)));
            }
        }
        if (matches.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, where,
                    "Ambiguous witness method '" + methodName + "' for type '" + actualType + "'."));
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static boolean isActive(final ResolutionContext context, final WitnessEntry entry) {
        return entry.packageName().equals(context.packageName)
                || context.currentImports.onDemandPackages().contains(entry.packageName());
    }

    private static String qualifyPackage(final String packageName, final String name) {
        return packageName == null || packageName.isEmpty() ? name : packageName + "." + name;
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
