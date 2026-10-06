package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.Map;
import java.util.Set;

final class TypeCompatibility {
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

        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE, where,
                "Expected " + TypeFormatter.format(expectedType)
                        + ", found " + TypeFormatter.format(resolvedType) + "."));
        return expectedType;
    }

    boolean canAssign(final TypeDescriptor expectedType, final TypeDescriptor resolvedType) {
        if (expectedType.equals(resolvedType)) return true;
        if (resolvedType instanceof TypeParameterDescriptor parameter
            && parameter.bounds().stream().anyMatch(bound -> canAssign(expectedType, bound))) return true;
        if (expectedType instanceof AnyDescriptor
                && !(resolvedType instanceof NullableDescriptor || resolvedType instanceof NullDescriptor)) {
            return true;
        }
        if (isNullableAny(expectedType)) return true;
        if (resolvedType instanceof ReferenceDescriptor reference
                && expectedType.equals(reference.baseType())) return true;
        if (isContractProjection(expectedType, resolvedType)) return true;
        if (resolvedType instanceof NullDescriptor && expectedType.isNullable()) return true;
        return expectedType instanceof NullableDescriptor nullable
                && (nullable.baseType().equals(resolvedType)
                || resolvedType instanceof ReferenceDescriptor reference
                && nullable.baseType().equals(reference.baseType()));
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
        for (final var contractUse : declaration.contractUses()) {
            if (!contractUse.name().lexeme().equals(contract.name())) continue;
            final var appliedArguments = contractUse.typeArguments().stream()
                    .map(argument -> TypeSubstitution.substitute(argument, classSubstitutions))
                    .toList();
            final TypeDescriptor appliedContract = appliedArguments.isEmpty()
                    ? TypeDescriptor.ofName(contract.name())
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName(contract.name()), appliedArguments);
            if (appliedContract.equals(contractType)) return true;
        }
        return false;
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