package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class TypeCompatibility implements TypeProjector {
    private final Map<String, Stmt.ClassDecl> classes;
    private final Map<String, Stmt.ContractDecl> contracts;

    TypeCompatibility(final Map<String, Stmt.ClassDecl> classes,
                      final Map<String, Stmt.ContractDecl> contracts) {
        this.classes = classes;
        this.contracts = contracts;
    }

    TypeDescriptor ensureExact(final Token where,
                               final TypeDescriptor typeA,
                               final TypeDescriptor typeB) {
        if (typeA instanceof InferDescriptor && typeB instanceof InferDescriptor) {
            return TypeDescriptor.ofInfer();
        }
        if (typeA.isWellFormed() && typeB.isWellFormed() && typeA.equals(typeB)) return typeA;

        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE,
                where, "Types are not exact."));
        return typeA instanceof InferDescriptor ? typeB : typeA;
    }

    TypeDescriptor ensureCommonParent(final Token where,
                                      final TypeDescriptor typeA,
                                      final TypeDescriptor typeB) {
        if (typeA.equals(typeB)) return typeA;
        if (typeA instanceof NullDescriptor) return typeB.toNullable();
        if (typeB instanceof NullDescriptor) return typeA.toNullable();
        if (canAssign(typeA, typeB)) return typeA;
        if (canAssign(typeB, typeA)) return typeB;
        if (typeA.isNullable() || typeB.isNullable()) return TypeDescriptor.ofAny().toNullable();
        return TypeDescriptor.ofAny();
    }

    TypeDescriptor ensureAssignable(final TypeDescriptor expectedType,
                                    final TypeDescriptor resolvedType,
                                    final Token where) {
        if (expectedType instanceof InferDescriptor) return resolvedType;
        if (canAssign(expectedType, resolvedType)) return expectedType;
        if (resolvedType instanceof TypeParameterDescriptor nullableParameter
                && nullableParameter.admitsNullableArguments()) {
            Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.NULLABLE_TYPE_PARAMETER_REQUIRES_NULL_CHECK, where,
                    "`" + where.lexeme() + "` has type `" + nullableParameter.name()
                            + "`, which may be null. Check it against `null` before using it."));
        }

        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE, where,
                "Expected " + TypeFormatter.format(expectedType)
                        + ", found " + TypeFormatter.format(resolvedType) + "."));
        return expectedType;
    }

    boolean canAssign(final TypeDescriptor expectedType, final TypeDescriptor resolvedType) {
        if (expectedType.equals(resolvedType)) return true;
        if (resolvedType instanceof NeverDescriptor) return true;
        if (expectedType instanceof CapturedTypeDescriptor capture) {
            return canAssign(capture.lowerBound(), resolvedType);
        }
        if (resolvedType instanceof CapturedTypeDescriptor capture) {
            return canAssign(expectedType, capture.upperBound());
        }
        if (resolvedType instanceof TypeProjectionDescriptor projection) {
            return canAssign(expectedType, projection.bound());
        }
        if (resolvedType instanceof TypeParameterDescriptor nullableParameter
                && nullableParameter.admitsNullableArguments()) {
            return admitsNullableTypeParameter(expectedType, nullableParameter);
        }
        if (expectedType instanceof NullableDescriptor expectedNullable
                && resolvedType instanceof NullableDescriptor resolvedNullable
                && canAssign(expectedNullable.baseType(), resolvedNullable.baseType())) {
            return true;
        }
        if (expectedType instanceof FunctionDescriptor expectedFunction
                && resolvedType instanceof FunctionDescriptor resolvedFunction
                && expectedFunction.arity() == resolvedFunction.arity()
                && (expectedFunction.receiverType() == null) == (resolvedFunction.receiverType() == null)
                && (expectedFunction.receiverType() == null
                    || canAssign(resolvedFunction.receiverType(), expectedFunction.receiverType()))
                && expectedFunction.isHandler() == resolvedFunction.isHandler()
                && (!expectedFunction.isHandler()
                    || new java.util.HashSet<>(expectedFunction.handledEffects())
                            .equals(new java.util.HashSet<>(resolvedFunction.handledEffects()))
                    && new java.util.HashSet<>(expectedFunction.raisedEffects())
                            .equals(new java.util.HashSet<>(resolvedFunction.raisedEffects()))
                    && expectedFunction.returnType().equals(resolvedFunction.returnType()))
                && functionParametersAccept(expectedFunction, resolvedFunction)
                && canAssign(expectedFunction.returnType(), resolvedFunction.returnType())
                && (expectedFunction.isHandler()
                    || EffectRows.includedIn(resolvedFunction.raisedEffects(),
                            expectedFunction.raisedEffects()))) {
            return true;
        }
        if (expectedType instanceof GenericDescriptor expectedGeneric
                && resolvedType instanceof GenericDescriptor resolvedGeneric
                && expectedGeneric.baseType().equals(resolvedGeneric.baseType())) {
            return genericArgumentsAssignable(expectedGeneric.baseType().name(),
                    expectedGeneric.typeParameters(), resolvedGeneric.typeParameters(), true);
        }
        if (resolvedType instanceof TypeParameterDescriptor parameter
            && parameter.bounds().stream().anyMatch(bound -> canAssign(expectedType, bound))) return true;
        if (expectedType instanceof AnyDescriptor
                && !(resolvedType instanceof NullableDescriptor || resolvedType instanceof NullDescriptor)) {
            return true;
        }
        if (isNullableAny(expectedType)) return true;
        if (resolvedType instanceof ReferenceDescriptor reference
                && (expectedType.equals(reference.baseType())
                || canAssign(expectedType, reference.baseType()))) return true;
        if (isArrayIterableProjection(expectedType, resolvedType)
                || isContractProjection(expectedType, resolvedType)) return true;
        if (resolvedType instanceof NullDescriptor && expectedType.isNullable()) return true;
        return expectedType instanceof NullableDescriptor nullable
                && (canAssign(nullable.baseType(), resolvedType)
                || resolvedType instanceof ReferenceDescriptor reference
                && canAssign(nullable.baseType(), reference.baseType()));
    }

    public Optional<TypeDescriptor> projectTo(final TypeDescriptor source,
                                              final TypeDescriptor targetBase) {
        final var sourceUse = ContractComposition.fromType(source);
        if (sourceUse == null) return Optional.empty();

        final var targetName = targetBase instanceof ReferenceDescriptor reference 
            ? reference.baseType()
            : targetBase instanceof GenericDescriptor generic 
                ? generic.baseType() 
                : targetBase;

        final var targetContract = contracts.get(targetName.name());
        if (targetContract == null) return Optional.empty();

        final var closure = ContractComposition.closure(contracts, sourceUse);

        return closure.stream()
                .filter(applied -> applied.declaration().name().equals(targetContract.name()))
                .map(this::toTypeDescriptor)
                .findFirst();
    }

    private TypeDescriptor toTypeDescriptor(ContractComposition.Applied applied) {
        final var declaration = applied.declaration();
        final var arguments = applied.use().typeArguments();

        final var baseType = TypeDescriptor.ofName(declaration.name().lexeme());

        if (arguments.isEmpty()) {
            return baseType;
        }

        return TypeDescriptor.genericOf(baseType, arguments);
    }

    TypeDescriptor inferenceView(final TypeDescriptor expectedType, final TypeDescriptor actualType) {
        if (!(expectedType instanceof GenericDescriptor expected)) return actualType;
        final var actualBase = actualType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : actualType;
        if (actualBase instanceof GenericDescriptor actual) {
            final var declaration = classes.get(actual.baseType().name());
            if (declaration != null
                    && declaration.typeParameters().size() == actual.typeParameters().size()) {
                final var projected = projectClassContracts(declaration, actual.typeParameters(), expected);
                if (projected != null) return projected;
            }
            // A contract value can stand in for one of its parent contracts, including a generic
            // model implementation standing in for `Model<T>`.
            return projectTo(actualBase, expected.baseType()).orElse(actualType);
        }
        if (actualBase instanceof NominalDescriptor nominal) {
            final var declaration = classes.get(nominal.name());
            if (declaration != null) {
                final var projected = projectClassContracts(declaration, List.of(), expected);
                if (projected != null) return projected;
            }
        }
        return actualType;
    }

    /// Projects a class's contract uses to the expected contract, substituting the class's type
    /// arguments into the declared parent arguments.
    private TypeDescriptor projectClassContracts(final Stmt.ClassDecl declaration,
                                                 final List<TypeDescriptor> arguments,
                                                 final GenericDescriptor expected) {
        if (declaration.typeParameters().size() != arguments.size()) return null;
        final var substitutions = new java.util.LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < declaration.typeParameters().size(); index++) {
            substitutions.put(declaration.typeParameters().get(index), arguments.get(index));
        }
        for (final var applied : ContractComposition.forClass(contracts, declaration)) {
            if (!applied.use().name().lexeme().equals(expected.baseType().name())) continue;
            final var projectedArguments = applied.use().typeArguments().stream()
                    .map(argument -> TypeSubstitution.substitute(argument, substitutions))
                    .toList();
            if (projectedArguments.size() == expected.typeParameters().size()) {
                return TypeDescriptor.genericOf(expected.baseType(), projectedArguments);
            }
        }
        return null;
    }

    private boolean functionParametersAccept(final FunctionDescriptor expectedFunction,
                                             final FunctionDescriptor resolvedFunction) {
        for (int i = 0; i < expectedFunction.arity(); i++) {
            if (!canAssign(resolvedFunction.parameters().get(i), expectedFunction.parameters().get(i))) {
                return false;
            }
        }
        return true;
    }

    boolean canTypeTest(final TypeDescriptor sourceType, final TypeDescriptor targetType) {
        if (sourceType instanceof NullDescriptor) return true;
        var sourceBase = sourceType instanceof NullableDescriptor nullable
                ? nullable.baseType()
                : sourceType;
        if (sourceBase instanceof ReferenceDescriptor reference) sourceBase = reference.baseType();
        return sourceBase instanceof AnyDescriptor
                || canAssign(sourceBase, targetType)
                || canAssign(targetType, sourceBase);
    }

    boolean canTestGenericTarget(final TypeDescriptor sourceType, final TypeDescriptor targetType) {
        if (!(targetType instanceof GenericDescriptor target)
                || !isZeronGenericType(target.baseType().name())) return false;
        final var source = nonNullBase(sourceType);
        if (source instanceof AnyDescriptor) return isUniversalProjectedView(target);
        if (canAssign(target, source)) return true;

        final var declaration = classes.get(target.baseType().name());
        if (declaration == null || target.typeParameters().size() != declaration.typeParameters().size()) {
            return false;
        }
        final var substitutions = new java.util.LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < declaration.typeParameters().size(); index++) {
            substitutions.put(declaration.typeParameters().get(index), target.typeParameters().get(index));
        }
        final var targetContracts = ContractComposition.forClass(contracts, declaration);
        final var sourceContracts = contractViews(source);
        for (final var sourceContract : sourceContracts) {
            final var matchingSourceApplications = sourceContracts.stream()
                    .filter(candidate -> candidate.name().lexeme().equals(sourceContract.name().lexeme()))
                    .toList();
            final var matchingTargetApplications = targetContracts.stream()
                    .filter(candidate -> candidate.use().name().lexeme().equals(sourceContract.name().lexeme()))
                    .toList();
            if (matchingSourceApplications.size() == 1 && matchingTargetApplications.size() == 1) {
                final var targetContract = matchingTargetApplications.getFirst().use();
                final var resolvedTargetArguments = targetContract.typeArguments().stream()
                        .map(argument -> TypeSubstitution.substitute(argument, substitutions))
                        .toList();
                if (sourceApplicationIsExact(sourceContract)
                        && allTypeParametersOccur(targetContract.typeArguments(), declaration.typeParameters())
                        && sourceContract.typeArguments().equals(resolvedTargetArguments)) return true;
            }
        }
        return false;
    }

    private boolean allTypeParametersOccur(final java.util.List<TypeDescriptor> appliedArguments,
                                           final java.util.List<TypeParameterDescriptor> typeParameters) {
        return typeParameters.stream().allMatch(parameter -> appliedArguments.stream()
                .anyMatch(argument -> containsTypeParameter(argument, parameter)));
    }

    private boolean containsTypeParameter(final TypeDescriptor type,
                                          final TypeParameterDescriptor parameter) {
        return switch (type) {
            case TypeParameterDescriptor candidate -> candidate.equals(parameter);
            case AppliedConstructorDescriptor applied -> containsTypeParameter(applied.constructor(), parameter)
                    || containsTypeParameter(applied.argument(), parameter);
            case TypeConstructorDescriptor constructor -> constructor.arguments().stream()
                    .anyMatch(argument -> containsTypeParameter(argument, parameter));
            case NullableDescriptor nullable -> containsTypeParameter(nullable.baseType(), parameter);
            case ReferenceDescriptor reference -> containsTypeParameter(reference.baseType(), parameter);
            case ArrayDescriptor array -> containsTypeParameter(array.elementType(), parameter);
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(argument -> containsTypeParameter(argument, parameter));
            case TypeProjectionDescriptor projection -> containsTypeParameter(projection.bound(), parameter);
            case CapturedTypeDescriptor capture -> containsTypeParameter(capture.lowerBound(), parameter)
                    || containsTypeParameter(capture.upperBound(), parameter);
            case FunctionDescriptor function -> containsTypeParameter(function.returnType(), parameter)
                    || function.parameters().stream().anyMatch(argument -> containsTypeParameter(argument, parameter))
                    || function.raisedEffects().stream().anyMatch(argument -> containsTypeParameter(argument, parameter));
            case EffectRowDescriptor row -> row.effects().stream()
                    .anyMatch(effect -> containsTypeParameter(effect, parameter))
                    || row.variables().contains(parameter);
            default -> false;
        };
    }

    private boolean sourceApplicationIsExact(final Stmt.ContractUse sourceContract) {
        final var declaration = contracts.get(sourceContract.name().lexeme());
        if (declaration == null || declaration.typeParameters().size() != sourceContract.typeArguments().size()) {
            return false;
        }
        for (int index = 0; index < declaration.typeParameters().size(); index++) {
            if (declaration.typeParameters().get(index).variance()
                    == TypeParameterDescriptor.Variance.COVARIANT
                    && !isStaticallyFinalType(sourceContract.typeArguments().get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean isStaticallyFinalType(final TypeDescriptor type) {
        return switch (type) {
            case IntDescriptor _, LongDescriptor _, ByteDescriptor _, FloatDescriptor _,
                    BooleanDescriptor _, CharDescriptor _, StringDescriptor _, UnitDescriptor _,
                    ByteArrayDescriptor _, ArrayDescriptor _ -> true;
            case NominalDescriptor nominal -> classes.containsKey(nominal.name());
            case GenericDescriptor generic -> {
                final var declaration = classes.get(generic.baseType().name());
                if (declaration == null || declaration.typeParameters().size() != generic.typeParameters().size()
                        || generic.typeParameters().stream().anyMatch(this::containsProjectionOrCapture)) {
                    yield false;
                }
                var finalType = true;
                for (int index = 0; index < declaration.typeParameters().size(); index++) {
                    if (declaration.typeParameters().get(index).variance()
                            == TypeParameterDescriptor.Variance.COVARIANT
                            && !isStaticallyFinalType(generic.typeParameters().get(index))) {
                        finalType = false;
                        break;
                    }
                }
                yield finalType;
            }
            default -> false;
        };
    }

    private boolean containsProjectionOrCapture(final TypeDescriptor type) {
        return switch (type) {
            case TypeProjectionDescriptor _, CapturedTypeDescriptor _ -> true;
            case NullableDescriptor nullable -> containsProjectionOrCapture(nullable.baseType());
            case ReferenceDescriptor reference -> containsProjectionOrCapture(reference.baseType());
            case ArrayDescriptor array -> containsProjectionOrCapture(array.elementType());
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(this::containsProjectionOrCapture);
            case FunctionDescriptor function -> containsProjectionOrCapture(function.returnType())
                    || function.parameters().stream().anyMatch(this::containsProjectionOrCapture)
                    || function.raisedEffects().stream().anyMatch(this::containsProjectionOrCapture);
            case EffectRowDescriptor row -> row.effects().stream()
                    .anyMatch(this::containsProjectionOrCapture);
            case AppliedConstructorDescriptor applied -> containsProjectionOrCapture(applied.argument());
            case TypeConstructorDescriptor constructor -> constructor.arguments().stream()
                    .anyMatch(this::containsProjectionOrCapture);
            default -> false;
        };
    }

    private boolean isZeronGenericType(final String typeName) {
        return classes.containsKey(typeName) || contracts.containsKey(typeName);
    }

    private boolean isUniversalProjectedView(final GenericDescriptor target) {
        if (!isZeronGenericType(target.baseType().name()) || target.typeParameters().isEmpty()) return false;
        return target.typeParameters().stream().allMatch(argument ->
                argument instanceof TypeProjectionDescriptor projection
                        && projection.kind() == TypeProjectionDescriptor.Kind.OUT
                        && isNullableAny(projection.bound()));
    }

    private TypeDescriptor nonNullBase(final TypeDescriptor type) {
        var base = type instanceof NullableDescriptor nullable ? nullable.baseType() : type;
        if (base instanceof ReferenceDescriptor reference) base = reference.baseType();
        if (base instanceof CapturedTypeDescriptor capture) base = capture.upperBound();
        return base;
    }

    private java.util.List<Stmt.ContractUse> contractViews(final TypeDescriptor source) {
        if (source instanceof TypeParameterDescriptor parameter) {
            return parameter.bounds().stream()
                    .flatMap(bound -> contractViews(nonNullBase(bound)).stream())
                    .toList();
        }
        final var use = ContractComposition.fromType(source);
        if (use == null || !contracts.containsKey(use.name().lexeme())) return java.util.List.of();
        return ContractComposition.closure(contracts, use).stream()
                .map(ContractComposition.Applied::use)
                .toList();
    }

    TypeDescriptor commonTypeForAlternatives(final Set<TypeDescriptor> alternatives) {
        if (alternatives.isEmpty()) return TypeDescriptor.ofAny();
        if (alternatives.size() == 1) return alternatives.iterator().next();
        for (final var contractName : contracts.keySet()) {
            final var contractType = TypeDescriptor.ofName(contractName);
            if (alternatives.stream().allMatch(alternative -> canAssign(contractType, alternative))) {
                return contractType;
            }
        }
        return TypeDescriptor.ofAny();
    }

    /// Whether `expectedType` is one of the only targets a possibly-null type-parameter value may be
    /// used for: the parameter itself, `T?`, or `Any?`.
    boolean admitsNullableTypeParameter(final TypeDescriptor expectedType,
                                        final TypeParameterDescriptor parameter) {
        if (expectedType instanceof InferDescriptor) return true;
        final var expected = expectedType instanceof ReferenceDescriptor reference
                ? reference.baseType() : expectedType;
        if (expected instanceof NullableDescriptor nullable) {
            return nullable.baseType() instanceof AnyDescriptor
                    || nullable.baseType() instanceof TypeParameterDescriptor candidate
                        && candidate.equals(parameter);
        }
        return expected instanceof TypeParameterDescriptor candidate && candidate.equals(parameter);
    }

    private boolean isNullableAny(final TypeDescriptor type) {
        return type instanceof NullableDescriptor nullable
                && nullable.baseType() instanceof AnyDescriptor;
    }

    boolean isContractProjection(final TypeDescriptor expectedType,
                                 final TypeDescriptor resolvedType) {
        final var expectedMutable = expectedType instanceof ReferenceDescriptor;
        final var contractType = expectedMutable
                ? ((ReferenceDescriptor) expectedType).baseType()
                : expectedType;
        final var resolvedMutable = resolvedType instanceof ReferenceDescriptor;
        if (expectedMutable && !resolvedMutable) return false;
        final var classType = resolvedMutable
                ? ((ReferenceDescriptor) resolvedType).baseType()
                : resolvedType;
        final var contract = nominalBase(contractType);
        final var concrete = nominalBase(classType);
        if (contract == null || concrete == null || !contracts.containsKey(contract.name())) return false;
        if (contracts.containsKey(concrete.name())) {
            final var sourceUse = ContractComposition.fromType(classType);
            final var targetUse = ContractComposition.fromType(contractType);
            return sourceUse != null && targetUse != null
                    && ContractComposition.closure(contracts, sourceUse).stream()
                            .anyMatch(applied -> contractUseAssignable(targetUse, applied.use(),
                                    !expectedMutable));
        }
        final var declaration = classes.get(concrete.name());
        if (declaration == null) return false;
        final var classArguments = classType instanceof GenericDescriptor generic
                ? generic.typeParameters()
                : java.util.List.<TypeDescriptor>of();
        final var classSubstitutions = new java.util.LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        if (classArguments.size() != declaration.typeParameters().size()) return false;
        for (int i = 0; i < classArguments.size(); i++) {
            classSubstitutions.put(declaration.typeParameters().get(i), classArguments.get(i));
        }
        for (final var applied : ContractComposition.forClass(contracts, declaration)) {
            if (!applied.use().name().lexeme().equals(contract.name())) continue;
            final var appliedArguments = applied.use().typeArguments().stream()
                    .map(argument -> TypeSubstitution.substitute(argument, classSubstitutions))
                    .toList();
            final TypeDescriptor appliedContract = appliedArguments.isEmpty()
                    ? TypeDescriptor.ofName(contract.name())
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName(contract.name()), appliedArguments);
            final var appliedUse = ContractComposition.fromType(appliedContract);
            final var targetUse = ContractComposition.fromType(contractType);
            if (appliedUse != null && targetUse != null
                    && contractUseAssignable(targetUse, appliedUse, !expectedMutable)) return true;
        }
        return false;
    }

    static boolean isArrayIterableProjection(final TypeDescriptor expectedType,
                                             final TypeDescriptor resolvedType) {
        if (expectedType instanceof ReferenceDescriptor) return false;
        var target = expectedType instanceof NullableDescriptor nullable
                ? nullable.baseType() : expectedType;
        if (target instanceof ReferenceDescriptor) return false;
        if (!(target instanceof GenericDescriptor iterable)
                || !iterable.baseType().name().equals("zeron.collections.Iterable")
                || iterable.typeParameters().size() != 1) return false;
        final var source = resolvedType instanceof ReferenceDescriptor reference
                ? reference.baseType() : resolvedType;
        return source instanceof ArrayDescriptor array
                && iterable.typeParameters().getFirst().equals(array.elementType());
    }

    private boolean contractUseAssignable(final Stmt.ContractUse expected,
                                          final Stmt.ContractUse actual,
                                          final boolean allowVariance) {
        if (!expected.name().lexeme().equals(actual.name().lexeme())
                || expected.typeArguments().size() != actual.typeArguments().size()) return false;
        return genericArgumentsAssignable(expected.name().lexeme(),
                expected.typeArguments(), actual.typeArguments(), allowVariance);
    }

    private boolean genericArgumentsAssignable(final String typeName,
                                               final java.util.List<TypeDescriptor> expectedArguments,
                                               final java.util.List<TypeDescriptor> actualArguments,
                                               final boolean allowVariance) {
        if (expectedArguments.size() != actualArguments.size()) return false;
        final var declarationParameters = declarationTypeParameters(typeName, expectedArguments.size());
        if (declarationParameters == null) return expectedArguments.equals(actualArguments);
        for (int index = 0; index < expectedArguments.size(); index++) {
            final var expectedArgument = expectedArguments.get(index);
            final var actualArgument = actualArguments.get(index);
            if (expectedArgument instanceof TypeProjectionDescriptor expectedProjection) {
                if (actualArgument instanceof TypeProjectionDescriptor actualProjection) {
                    if (expectedProjection.kind() != actualProjection.kind()) return false;
                    final var compatible = expectedProjection.kind() == TypeProjectionDescriptor.Kind.OUT
                            ? canAssign(expectedProjection.bound(), actualProjection.bound())
                            : canAssign(actualProjection.bound(), expectedProjection.bound());
                    if (!compatible) return false;
                } else {
                    final var compatible = expectedProjection.kind() == TypeProjectionDescriptor.Kind.OUT
                            ? canAssign(expectedProjection.bound(), actualArgument)
                            : canAssign(actualArgument, expectedProjection.bound());
                    if (!compatible) return false;
                }
                continue;
            }
            if (actualArgument instanceof TypeProjectionDescriptor) return false;
            if (allowVariance
                    && declarationParameters.get(index).variance()
                            == TypeParameterDescriptor.Variance.COVARIANT) {
                if (!canAssign(expectedArgument, actualArgument)) return false;
            } else if (!expectedArgument.equals(actualArgument)) {
                return false;
            }
        }
        return true;
    }

    private List<TypeParameterDescriptor> declarationTypeParameters(final String typeName,
                                                                    final int arity) {
        final var classDeclaration = classes.get(typeName);
        if (classDeclaration != null && classDeclaration.typeParameters().size() == arity) {
            return classDeclaration.typeParameters();
        }
        final var contractDeclaration = contracts.get(typeName);
        if (contractDeclaration != null && contractDeclaration.typeParameters().size() == arity) {
            return contractDeclaration.typeParameters();
        }
        return null;
    }

    private NominalDescriptor nominalBase(final TypeDescriptor type) {
        return switch (type) {
            case NominalDescriptor nominal -> nominal;
            case GenericDescriptor generic -> generic.baseType();
            default -> null;
        };
    }

    void ensureBoolean(final TypeDescriptor type, final Token where) {
        if (type instanceof BooleanDescriptor) return;
        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE, where,
                "Condition must have non-null Boolean type."));
    }
}