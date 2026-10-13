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
        final var sumTarget = context.sums.get(nominalName(witness.targetType()));
        final var productTarget = context.products.get(nominalName(witness.targetType()));
        if ((sumTarget != null || productTarget != null) && contract != null
                && contract.name().lexeme().equals("zeron.lang.Eq")) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                    witness.name(), "Sum and product types use compiler-synthesized structural Eq evidence."));
        }
        if (contract == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    witness.name(), "A witness must name a contract and a supported target type."));
        }
        if (!packageName.equals(packageOf(contract.name().lexeme()))
                && !packageName.equals(packageOfTarget(witness.targetType()))) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    witness.name(), "A witness must be declared in the contract or target type package."));
        }

        final var targetParameters = target != null ? target.typeParameters()
                : sumTarget != null ? sumTarget.typeParameters() : List.<TypeParameterDescriptor>of();
        final var nominalTargetName = target != null ? target.name().lexeme()
                : sumTarget != null ? sumTarget.name().lexeme() : null;
        if (nominalTargetName != null && targetParameters.size() != witness.typeParameters().size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    witness.name(), "Witness target must match the target type's complete type pattern."));
        }
        final var targetSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        final var targetPattern = nominalTargetName == null ? witness.targetType() : targetParameters.isEmpty()
                ? (TypeDescriptor) TypeDescriptor.ofName(nominalTargetName)
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(nominalTargetName),
                        targetParameters.stream().map(parameter -> (TypeDescriptor) parameter).toList());
        if (nominalTargetName != null) {
            for (final var parameter : targetParameters) {
                targetSubstitutions.put(parameter, parameter);
            }
        }
        if (nominalTargetName != null && (!matchPattern(witness.targetType(), targetPattern,
                    witness.typeParameters(), targetSubstitutions)
                || !targetSubstitutions.keySet().containsAll(witness.typeParameters()))) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    witness.name(), "Witness target must match the target type's complete type pattern."));
        }
        if (nominalTargetName == null && !witness.typeParameters().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    witness.name(), "Generic witness targets must be generic nominal types."));
        }
        final var contractArguments = typeArguments(witness.contractType());
        if (contractArguments.size() != contract.typeParameters().size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    witness.name(), "Witness contract type has the wrong number of type arguments."));
        }
        final var classSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        if (target != null) {
            for (int index = 0; index < targetParameters.size(); index++) {
                classSubstitutions.put(targetParameters.get(index), witness.typeParameters().get(index));
            }
        }
        final var classContract = target != null && ContractComposition.forClass(context, target).stream()
                .anyMatch(applied -> sameAppliedType(
                        appliedType(applied, classSubstitutions), witness.contractType()));
        if (classContract && !witness.methods().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    witness.name(), "A witness cannot override methods of a nominally conforming class."));
        }
        if (target == null || !classContract) {
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
            final var requiredSlot = ContractComposition.constructors(context,
                            contractUse(witness.contractType()))
                    .stream().filter(slot -> slot.constructor().name().lexeme()
                            .equals(mapping.requirementName().lexeme()))
                    .findFirst().orElse(null);
            if (requiredSlot == null) {
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
            final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                    (FunctionDescriptor) TypeSubstitution.substitute(
                            requiredSlot.constructor().typeDescriptor(),
                            requiredSlot.applied().substitutions()), targetSubstitutions);
            final var implementationType = (FunctionDescriptor) TypeSubstitution.substitute(
                    implementation.typeDescriptor(), targetSubstitutions);
            if (requiredSlot.constructor().variadic() != implementation.variadic()
                    || !DeclarationResolver.compatibleMethodSignatures(context, requiredType,
                            requiredSlot.constructor().variadic(), implementationType,
                            implementation.variadic())) {
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
        for (final var slot : ContractComposition.methods(context, contractUse(witness.contractType()))) {
            final var required = slot.method();
            if (!required.isDefault() || implementedNames.contains(required.name().lexeme())) continue;
            if (required.isMutating()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                        required.name(), "Witnesses cannot use mutating default contract methods."));
            }
            final var sourceType = (FunctionDescriptor) TypeSubstitution.substitute(
                    (FunctionDescriptor) TypeSubstitution.substitute(
                            required.typeDescriptor(), slot.applied().substitutions()),
                    targetSubstitutions);
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
        final var methodSlots = ContractComposition.methods(context, contractUse(witness.contractType()));
        final var implementations = new LinkedHashMap<String, Stmt.WitnessMethod>();
        for (final var method : witness.methods()) {
            if (implementations.putIfAbsent(method.requirementName().lexeme(), method) != null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        method.requirementName(), "Duplicate witness method implementation."));
            }
            final var requiredSlot = methodSlots.stream()
                    .filter(slot -> slot.method().name().lexeme()
                            .equals(method.requirementName().lexeme()))
                    .findFirst().orElse(null);
            if (requiredSlot == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        method.requirementName(), "Unknown contract method requirement."));
            }
            final var required = requiredSlot.method();
            if (required.isMutating()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                        method.requirementName(), "Witnesses cannot implement mutating contract methods."));
            }
            final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                    (FunctionDescriptor) TypeSubstitution.substitute(
                            required.typeDescriptor(), requiredSlot.applied().substitutions()),
                    targetSubstitutions);
            if (!DeclarationResolver.compatibleMethodSignatures(context, requiredType, required.variadic(),
                    method.methodSignature().typeDescriptor(), method.methodSignature().variadic())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE,
                        method.requirementName(), "Witness method does not satisfy the contract requirement."));
            }
        }
        for (final var slot : methodSlots) {
            final var required = slot.method();
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
            if (!matchPattern(entry.declaration().targetType(), targetType,
                    entry.declaration().typeParameters(), substitutions)
                    || !witnessEntails(context, entry.declaration(), substitutions, contractType)) continue;
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
            final var use = contractUse(bound);
            if (use == null) continue;
            for (final var slot : ContractComposition.constructors(context, use)) {
                final var constructor = (FunctionDescriptor) TypeSubstitution.substitute(
                        slot.constructor().typeDescriptor(), slot.applied().substitutions());
                result.add(new Expr.EvidenceArgument(parameter, boundIndex, slot.index(),
                        null, null, constructor, null));
            }
            for (final var slot : ContractComposition.methods(context, use)) {
                final var method = slot.method();
                final var descriptor = (FunctionDescriptor) TypeSubstitution.substitute(
                        method.typeDescriptor(), slot.applied().substitutions());
                final var methodParameters = new ArrayList<TypeDescriptor>();
                methodParameters.add(parameter);
                methodParameters.addAll(descriptor.parameters());
                final var methodType = TypeDescriptor.functionWithEffectsOf(method.name().lexeme(),
                        descriptor.returnType(), methodParameters,
                        descriptor.typeParameters(), descriptor.raisedEffects());
                result.add(new Expr.EvidenceArgument(parameter, boundIndex, slot.index(),
                        null, null, methodType,
                        Expr.methodEvidenceToken(parameter, boundIndex, slot.index()), true));
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
                if (slot.instanceMethod()) {
                    final var use = contractUse(requiredBound);
                    if (use == null) continue;
                    final var methods = ContractComposition.methods(context, use);
                    if (slot.constructorIndex() >= methods.size()) continue;
                    final var methodSlot = methods.get(slot.constructorIndex());
                    final var appliedBound = appliedType(methodSlot.applied(), substitutions);
                    evidence.add(selectMethodEvidence(context, parameter, slot.boundIndex(),
                            slot.constructorIndex(), appliedBound, actual,
                            methodSlot.method(), where));
                    continue;
                }
                final var use = contractUse(requiredBound);
                if (use == null) continue;
                final var constructors = ContractComposition.constructors(context, use);
                if (slot.constructorIndex() >= constructors.size()) continue;
                final var constructorSlot = constructors.get(slot.constructorIndex());
                evidence.add(select(context, parameter, slot.boundIndex(), slot.constructorIndex(),
                        appliedType(constructorSlot.applied(), substitutions), actual,
                        constructorSlot.constructor(), where));
            }
        }
        return List.copyOf(evidence);
    }

    static List<Expr.EvidenceArgument> selectClassArguments(
            final ResolutionContext context, final Stmt.ClassDecl declaration,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
            final com.maruseron.zeron.scan.Token where) {
        final var evidence = new ArrayList<Expr.EvidenceArgument>(
                selectArguments(context, declaration.typeParameters(), substitutions, where));
        for (final var applied : ContractComposition.forClass(context, declaration)) {
            final var contractSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            for (final var parameter : applied.declaration().typeParameters()) {
                final var argument = applied.substitutions().get(parameter);
                if (argument != null) {
                    contractSubstitutions.put(parameter, TypeSubstitution.substitute(argument, substitutions));
                }
            }
            evidence.addAll(selectArguments(context, applied.declaration().typeParameters(),
                    contractSubstitutions, where));
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
                && parameter.bounds().stream().anyMatch(bound -> entails(context, bound, contractType))) return true;
        final var evidenceKey = contractType + " for " + actualType;
        if (!resolving.add(evidenceKey)) return false;
        final var actual = actualType instanceof ReferenceDescriptor reference
                ? reference.baseType() : actualType;
        try {
            if (isEqContract(contractType) && actual instanceof NullableDescriptor nullable
                    && hasEvidence(context, eqType(context, nullable.baseType()), nullable.baseType(),
                            where, resolving)) {
                return true;
            }
            if (isHashableContract(contractType) && actual instanceof NullableDescriptor nullable
                    && hasEvidence(context, hashableType(context, nullable.baseType()), nullable.baseType(),
                            where, resolving)) {
                return true;
            }
            if (hasStructuralProductEquality(context, contractType, actual, where, resolving)) return true;
            if (hasStructuralSumEquality(context, contractType, actual, where, resolving)) return true;
            var matchingWitnesses = 0;
            for (final var entry : context.typeClassWitnesses) {
                if (!isActive(context, entry)) continue;
                final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
                if (matchPattern(entry.declaration().targetType(), actual,
                        entry.declaration().typeParameters(), substitutions)
                        && witnessEntails(context, entry.declaration(), substitutions, contractType)
                        && prerequisitesAvailable(context, entry.declaration(), substitutions, where, resolving)) {
                    matchingWitnesses++;
                }
            }
            if (matchingWitnesses > 1) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, where,
                        "Ambiguous witness for contract '" + contractType + "' and type '" + actualType + "'."));
            }
            return matchingWitnesses == 1 || isBuiltInDisplay(contractType, actual)
                    || isBuiltInEq(contractType, actual) || isBuiltInHashable(contractType, actual);
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
                final var boundUse = contractUse(actualParameter.bounds().get(boundIndex));
                if (boundUse == null) continue;
                for (final var slot : ContractComposition.methods(context, boundUse)) {
                    if (slot.method().name().lexeme().equals(requirement.name().lexeme())
                            && sameAppliedType(appliedType(slot.applied(), Map.of()), requiredBound)) {
                        return new Expr.EvidenceArgument(actualParameter, boundIndex, slot.index(),
                                null, null, signatureForMethod(slot, actualParameter),
                                Expr.methodEvidenceToken(actualParameter, boundIndex, slot.index()), true);
                    }
                }
            }
        }
        if (isEqContract(requiredBound) && actualType instanceof NullableDescriptor nullable
                && !isBuiltInEq(requiredBound, actualType)) {
            final var baseEvidence = selectMethodEvidence(context, targetParameter, targetBoundIndex,
                    targetMethodIndex, eqType(context, nullable.baseType()), nullable.baseType(),
                    eqMethod(context), where);
            final var signature = TypeDescriptor.functionOf("eq", TypeDescriptor.ofBoolean(),
                    TypeDescriptor.ofAny(), TypeDescriptor.ofAny());
            return new Expr.EvidenceArgument(targetParameter, targetBoundIndex, targetMethodIndex,
                    "zeron.runtime.EqSupport", "eqNullableWithHandle", signature, null, true,
                    Expr.EvidenceHandleKind.STATIC, List.of(baseEvidence));
        }
        if (isHashableContract(requiredBound) && actualType instanceof NullableDescriptor nullable
                && !isBuiltInHashable(requiredBound, actualType)) {
            final var baseEvidence = selectMethodEvidence(context, targetParameter, targetBoundIndex,
                    targetMethodIndex, hashableType(context, nullable.baseType()), nullable.baseType(),
                    hashMethod(context), where);
            final var signature = TypeDescriptor.functionOf("hash", TypeDescriptor.ofInt(),
                    TypeDescriptor.ofAny());
            return new Expr.EvidenceArgument(targetParameter, targetBoundIndex, targetMethodIndex,
                    "zeron.runtime.HashSupport", "hashNullableWithHandle", signature, null, true,
                    Expr.EvidenceHandleKind.STATIC, List.of(baseEvidence));
        }
        if (actualType instanceof TypeParameterDescriptor actualParameter) {
            for (int boundIndex = 0; boundIndex < actualParameter.bounds().size(); boundIndex++) {
                if (actualParameter.bounds().get(boundIndex).equals(requiredBound)) {
                    final var methodParameters = new ArrayList<TypeDescriptor>();
                    methodParameters.add(actualParameter);
                    methodParameters.addAll(requirement.typeDescriptor().parameters());
                    final var methodSignature = TypeDescriptor.functionWithEffectsOf(
                            requirement.name().lexeme(), requirement.typeDescriptor().returnType(),
                            methodParameters, requirement.typeDescriptor().typeParameters(),
                            requirement.typeDescriptor().raisedEffects());
                    return new Expr.EvidenceArgument(targetParameter, targetBoundIndex, targetMethodIndex,
                            null, null, methodSignature,
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
        final var builtinEquality = builtinEqualityHandle(requiredBound, concrete, requirement,
                targetParameter, targetBoundIndex, targetMethodIndex);
        if (builtinEquality != null) return builtinEquality;
        final var builtinHashing = builtinHashingHandle(requiredBound, concrete, requirement,
                targetParameter, targetBoundIndex, targetMethodIndex);
        if (builtinHashing != null) return builtinHashing;
        final var sumEquality = structuralSumEqualityHandle(context, requiredBound, concrete, requirement,
                targetParameter, targetBoundIndex, targetMethodIndex, where);
        if (sumEquality != null) return sumEquality;
        final var productEquality = structuralProductEqualityHandle(context, requiredBound, concrete, requirement,
                targetParameter, targetBoundIndex, targetMethodIndex, where);
        if (productEquality != null) return productEquality;
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

    private static FunctionDescriptor signatureForMethod(
            final ContractComposition.MethodSlot slot, final TypeParameterDescriptor target) {
        final var descriptor = (FunctionDescriptor) TypeSubstitution.substitute(
                slot.method().typeDescriptor(), slot.applied().substitutions());
        final var parameters = new ArrayList<TypeDescriptor>();
        parameters.add(target);
        parameters.addAll(descriptor.parameters());
        return TypeDescriptor.functionWithEffectsOf(descriptor.name(), descriptor.returnType(), parameters,
                descriptor.typeParameters(), descriptor.raisedEffects());
    }

    private static boolean entails(final ResolutionContext context, final TypeDescriptor bound,
                                   final TypeDescriptor required) {
        final var use = contractUse(bound);
        if (use == null) return false;
        return ContractComposition.closure(context, use).stream()
                .anyMatch(applied -> sameAppliedType(appliedType(applied, Map.of()), required));
    }

    private static boolean witnessEntails(
            final ResolutionContext context, final Stmt.Witness witness,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
            final TypeDescriptor requiredContract) {
        final var witnessContract = TypeSubstitution.substitute(witness.contractType(), substitutions);
        final var use = contractUse(witnessContract);
        return use != null && ContractComposition.closure(context, use).stream()
                .anyMatch(applied -> sameAppliedType(appliedType(applied, Map.of()), requiredContract));
    }

    private static Stmt.ContractUse contractUse(final TypeDescriptor type) {
        final var base = type instanceof ReferenceDescriptor reference ? reference.baseType() : type;
        final var name = nominalName(base);
        if (name == null) return null;
        final var arguments = base instanceof GenericDescriptor generic
                ? generic.typeParameters() : List.<TypeDescriptor>of();
        return new Stmt.ContractUse(new com.maruseron.zeron.scan.Token(
                com.maruseron.zeron.scan.TokenType.IDENTIFIER, name, null,
                com.maruseron.zeron.diagnostic.SourceSpan.line(null, 1)), arguments);
    }

    private static TypeDescriptor appliedType(
            final ContractComposition.Applied applied,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        final var arguments = applied.use().typeArguments().stream()
                .map(argument -> TypeSubstitution.substitute(argument, substitutions))
                .toList();
        return arguments.isEmpty()
                ? TypeDescriptor.ofName(applied.use().name().lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(applied.use().name().lexeme()), arguments);
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

    private static Expr.EvidenceArgument builtinEqualityHandle(
            final TypeDescriptor requiredBound, final TypeDescriptor concrete,
            final Stmt.ContractMethod requirement, final TypeParameterDescriptor parameter,
            final int boundIndex, final int methodIndex) {
        if (!isBuiltInEq(requiredBound, concrete)
                || !requirement.name().lexeme().equals("eq")
                || !(requirement.typeDescriptor().returnType() instanceof BooleanDescriptor)
                || requirement.typeDescriptor().parameters().size() != 1) return null;
        final var signature = TypeDescriptor.functionOf("eq", TypeDescriptor.ofBoolean(), concrete, concrete);
        return new Expr.EvidenceArgument(parameter, boundIndex, methodIndex,
                "zeron.runtime.EqSupport", builtinEqualityMethod(concrete), signature, null,
                true, Expr.EvidenceHandleKind.STATIC);
    }

    private static String builtinEqualityMethod(final TypeDescriptor concrete) {
        if (concrete instanceof NullableDescriptor nullable) {
            final var base = nullable.baseType();
            if (base instanceof IntDescriptor || base instanceof FloatDescriptor
                    || base instanceof BooleanDescriptor || base instanceof StringDescriptor
                    || base instanceof UnitDescriptor) return "eqNullable";
        }
        if (concrete instanceof IntDescriptor) return "eqInt";
        if (concrete instanceof FloatDescriptor) return "eqFloat";
        if (concrete instanceof BooleanDescriptor) return "eqBoolean";
        if (concrete instanceof StringDescriptor) return "eqString";
        if (concrete instanceof UnitDescriptor) return "eqUnit";
        throw new IllegalArgumentException("Unsupported built-in Eq type: " + concrete);
    }

    private static boolean isBuiltInEq(final TypeDescriptor contractType,
                                       final TypeDescriptor concreteType) {
        final var concrete = concreteType instanceof ReferenceDescriptor reference
                ? reference.baseType() : concreteType;
        final var arguments = typeArguments(contractType);
        final var target = arguments.isEmpty() ? null
                : arguments.getFirst() instanceof ReferenceDescriptor reference
                    ? reference.baseType() : arguments.getFirst();
        final var isBuiltInType = concrete instanceof NullableDescriptor nullable
                ? nullable.baseType() instanceof IntDescriptor || nullable.baseType() instanceof FloatDescriptor
                    || nullable.baseType() instanceof BooleanDescriptor
                    || nullable.baseType() instanceof StringDescriptor
                    || nullable.baseType() instanceof UnitDescriptor
                : concrete instanceof IntDescriptor || concrete instanceof FloatDescriptor
                    || concrete instanceof BooleanDescriptor || concrete instanceof StringDescriptor
                    || concrete instanceof UnitDescriptor;
        return nominalName(contractType).equals("zeron.lang.Eq")
                && arguments.size() == 1 && target.equals(concrete)
                && isBuiltInType;
    }

    private static Expr.EvidenceArgument builtinHashingHandle(
            final TypeDescriptor requiredBound, final TypeDescriptor concrete,
            final Stmt.ContractMethod requirement, final TypeParameterDescriptor parameter,
            final int boundIndex, final int methodIndex) {
        if (!isBuiltInHashable(requiredBound, concrete)
                || !requirement.name().lexeme().equals("hash")
                || !(requirement.typeDescriptor().returnType() instanceof IntDescriptor)
                || !requirement.typeDescriptor().parameters().isEmpty()) return null;
        final var method = concrete instanceof IntDescriptor ? "hashInt"
                : concrete instanceof FloatDescriptor ? "hashFloat"
                : concrete instanceof BooleanDescriptor ? "hashBoolean"
                : concrete instanceof StringDescriptor ? "hashString"
                : "hashUnit";
        final var signature = TypeDescriptor.functionOf("hash", TypeDescriptor.ofInt(), concrete);
        return new Expr.EvidenceArgument(parameter, boundIndex, methodIndex,
                "zeron.runtime.HashSupport", method, signature, null,
                true, Expr.EvidenceHandleKind.STATIC);
    }

    private static boolean isBuiltInHashable(final TypeDescriptor contractType,
                                             final TypeDescriptor concreteType) {
        final var concrete = concreteType instanceof ReferenceDescriptor reference
                ? reference.baseType() : concreteType;
        final var arguments = typeArguments(contractType);
        final var target = arguments.isEmpty() ? null
                : arguments.getFirst() instanceof ReferenceDescriptor reference
                    ? reference.baseType() : arguments.getFirst();
        return nominalName(contractType).equals("zeron.lang.Hashable")
                && arguments.size() == 1 && target.equals(concrete)
                && (concrete instanceof IntDescriptor || concrete instanceof FloatDescriptor
                    || concrete instanceof BooleanDescriptor || concrete instanceof StringDescriptor
                    || concrete instanceof UnitDescriptor);
    }

    private static TypeDescriptor hashableType(final ResolutionContext context,
                                               final TypeDescriptor target) {
        final var contract = context.contracts.get("zeron.lang.Hashable");
        if (contract == null) throw new IllegalStateException("Hashable<T> contract is unavailable.");
        return TypeDescriptor.genericOf(TypeDescriptor.ofName(contract.name().lexeme()), target);
    }

    private static Stmt.ContractMethod hashMethod(final ResolutionContext context) {
        final var contract = context.contracts.get("zeron.lang.Hashable");
        if (contract == null) throw new IllegalStateException("Hashable<T> contract is unavailable.");
        return contract.methods().stream().filter(method -> method.name().lexeme().equals("hash"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Hashable.hash method is unavailable."));
    }

    private static boolean isHashableContract(final TypeDescriptor contractType) {
        return nominalName(contractType).equals("zeron.lang.Hashable")
                && typeArguments(contractType).size() == 1;
    }

    static Expr.EvidenceArgument selectEqualityMethod(final ResolutionContext context,
                                                       final TypeDescriptor actualType,
                                                       final com.maruseron.zeron.scan.Token where) {
        return selectMethodEvidence(context, null, 0, 0, eqType(context, actualType), actualType,
                eqMethod(context), where);
    }

    private static Expr.EvidenceArgument structuralSumEqualityHandle(
            final ResolutionContext context, final TypeDescriptor requiredBound,
            final TypeDescriptor concrete, final Stmt.ContractMethod requirement,
            final TypeParameterDescriptor targetParameter, final int targetBoundIndex,
            final int targetMethodIndex, final com.maruseron.zeron.scan.Token where) {
        if (!isEqContract(requiredBound) || !requirement.name().lexeme().equals("eq")
                || !(requirement.typeDescriptor().returnType() instanceof BooleanDescriptor)
                || requirement.typeDescriptor().parameters().size() != 1) return null;
        if (!eqTargetMatches(requiredBound, concrete)) return null;
        final var sum = context.sums.get(nominalName(concrete));
        if (sum == null) return null;
        final var actualArguments = typeArguments(concrete);
        if (actualArguments.size() != sum.typeParameters().size()) return null;
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < actualArguments.size(); index++) {
            substitutions.put(sum.typeParameters().get(index), actualArguments.get(index));
        }
        final var prerequisites = new ArrayList<Expr.EvidenceArgument>();
        for (final var sumCase : sum.cases()) {
            for (final var payload : sumCase.payloads()) {
                final var payloadType = TypeSubstitution.substitute(payload.type(), substitutions);
                if (isRecursiveSumPayload(payloadType, concrete)) continue;
                final var payloadBound = eqType(context, payloadType);
                if (!hasEvidence(context, payloadBound, payloadType, where)) return null;
                prerequisites.add(selectMethodEvidence(context, targetParameter, targetBoundIndex,
                        targetMethodIndex, payloadBound, payloadType, eqMethod(context), where));
            }
        }
        final var signature = TypeDescriptor.functionOf("eq", TypeDescriptor.ofBoolean(),
                concrete, concrete);
        return new Expr.EvidenceArgument(targetParameter, targetBoundIndex, targetMethodIndex,
                sum.name().lexeme(), "$zeron$eq", signature, null, true,
                Expr.EvidenceHandleKind.INTERFACE_STATIC, prerequisites);
    }

    private static Expr.EvidenceArgument structuralProductEqualityHandle(
            final ResolutionContext context, final TypeDescriptor requiredBound,
            final TypeDescriptor concrete, final Stmt.ContractMethod requirement,
            final TypeParameterDescriptor targetParameter, final int targetBoundIndex,
            final int targetMethodIndex, final com.maruseron.zeron.scan.Token where) {
        if (!isEqContract(requiredBound) || !requirement.name().lexeme().equals("eq")
                || !(requirement.typeDescriptor().returnType() instanceof BooleanDescriptor)
                || requirement.typeDescriptor().parameters().size() != 1
                || !eqTargetMatches(requiredBound, concrete)) return null;
        final var product = context.products.get(nominalName(concrete));
        if (product == null) return null;
        final var actualArguments = typeArguments(concrete);
        if (actualArguments.size() != product.typeParameters().size()) return null;
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < actualArguments.size(); index++) {
            substitutions.put(product.typeParameters().get(index), actualArguments.get(index));
        }
        final var prerequisites = new ArrayList<Expr.EvidenceArgument>();
        for (final var component : product.components()) {
            final var componentType = TypeSubstitution.substitute(component.type(), substitutions);
            if (isRecursiveProductComponent(componentType, concrete)) continue;
            final var componentBound = eqType(context, componentType);
            if (!hasEvidence(context, componentBound, componentType, where)) return null;
            prerequisites.add(selectMethodEvidence(context, targetParameter, targetBoundIndex,
                    targetMethodIndex, componentBound, componentType, eqMethod(context), where));
        }
        final var signature = TypeDescriptor.functionOf("eq", TypeDescriptor.ofBoolean(), concrete, concrete);
        return new Expr.EvidenceArgument(targetParameter, targetBoundIndex, targetMethodIndex,
                product.name().lexeme(), "$zeron$eq", signature, null, true,
                Expr.EvidenceHandleKind.STATIC, prerequisites);
    }

    private static boolean hasStructuralProductEquality(
            final ResolutionContext context, final TypeDescriptor contractType,
            final TypeDescriptor actualType, final com.maruseron.zeron.scan.Token where,
            final Set<String> resolving) {
        if (!isEqContract(contractType) || !eqTargetMatches(contractType, actualType)) return false;
        final var product = context.products.get(nominalName(actualType));
        if (product == null) return false;
        final var actualArguments = typeArguments(actualType);
        if (actualArguments.size() != product.typeParameters().size()) return false;
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < actualArguments.size(); index++) {
            substitutions.put(product.typeParameters().get(index), actualArguments.get(index));
        }
        for (final var component : product.components()) {
            final var componentType = TypeSubstitution.substitute(component.type(), substitutions);
            if (isRecursiveProductComponent(componentType, actualType)) continue;
            if (!hasEvidence(context, eqType(context, componentType), componentType, where, resolving)) return false;
        }
        return true;
    }

    private static boolean hasStructuralSumEquality(
            final ResolutionContext context, final TypeDescriptor contractType,
            final TypeDescriptor actualType, final com.maruseron.zeron.scan.Token where,
            final Set<String> resolving) {
        if (!isEqContract(contractType) || !eqTargetMatches(contractType, actualType)
                || context.sums.get(nominalName(actualType)) == null) return false;
        final var sum = context.sums.get(nominalName(actualType));
        final var actualArguments = typeArguments(actualType);
        if (actualArguments.size() != sum.typeParameters().size()) return false;
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < actualArguments.size(); index++) {
            substitutions.put(sum.typeParameters().get(index), actualArguments.get(index));
        }
        for (final var sumCase : sum.cases()) {
            for (final var payload : sumCase.payloads()) {
                final var payloadType = TypeSubstitution.substitute(payload.type(), substitutions);
                if (isRecursiveSumPayload(payloadType, actualType)) continue;
                if (!hasEvidence(context, eqType(context, payloadType), payloadType, where, resolving)) return false;
            }
        }
        return true;
    }

    static boolean sameAppliedType(final TypeDescriptor left, final TypeDescriptor right) {
        return matchPattern(left, right, List.of(), new LinkedHashMap<>());
    }

    private static boolean isRecursiveSumPayload(final TypeDescriptor payloadType,
                                                final TypeDescriptor sumType) {
        final var normalizedPayload = payloadType instanceof NullableDescriptor nullable
                ? nullable.baseType() : payloadType;
        return sameAppliedType(normalizedPayload, sumType);
    }

    private static boolean isRecursiveProductComponent(final TypeDescriptor componentType,
                                                       final TypeDescriptor productType) {
        final var normalizedComponent = componentType instanceof NullableDescriptor nullable
                ? nullable.baseType() : componentType;
        return sameAppliedType(normalizedComponent, productType);
    }

    private static TypeDescriptor eqType(final ResolutionContext context, final TypeDescriptor target) {
        final var contract = context.contracts.get("zeron.lang.Eq");
        if (contract == null) throw new IllegalStateException("Eq<T> contract is unavailable.");
        return TypeDescriptor.genericOf(TypeDescriptor.ofName(contract.name().lexeme()), target);
    }

    private static Stmt.ContractMethod eqMethod(final ResolutionContext context) {
        final var contract = context.contracts.get("zeron.lang.Eq");
        if (contract == null) throw new IllegalStateException("Eq<T> contract is unavailable.");
        return contract.methods().stream().filter(method -> method.name().lexeme().equals("eq"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Eq.eq method is unavailable."));
    }

    private static boolean isEqContract(final TypeDescriptor contractType) {
        return nominalName(contractType).equals("zeron.lang.Eq") && typeArguments(contractType).size() == 1;
    }

    private static boolean eqTargetMatches(final TypeDescriptor contractType, final TypeDescriptor actualType) {
        final var target = typeArguments(contractType).getFirst();
        return sameAppliedType(target, actualType);
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
                final var witnessSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
                if (matchPattern(entry.declaration().targetType(), concrete,
                        entry.declaration().typeParameters(), witnessSubstitutions)
                        && witnessEntails(context, entry.declaration(), witnessSubstitutions, requiredBound)
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
            if (requiredContractType != null && !witnessEntails(
                    context, entry.declaration(), substitutions, requiredContractType)) continue;
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
