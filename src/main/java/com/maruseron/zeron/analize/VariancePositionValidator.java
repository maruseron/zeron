package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.List;
import java.util.Map;

final class VariancePositionValidator {
    private static final int INVARIANT = 0;
    private static final int COVARIANT = 1;
    private static final int CONTRAVARIANT = -1;

    private final Map<String, Stmt.ClassDecl> classes;
    private final Map<String, Stmt.ContractDecl> contracts;
    private final ResolutionContext context;
    private final Token declarationName;

    private VariancePositionValidator(final ResolutionContext context,
                                      final Token declarationName) {
        this.context = context;
        this.classes = context.classes;
        this.contracts = context.contracts;
        this.declarationName = declarationName;
    }

    static void validate(final ResolutionContext context, final Stmt.ClassDecl declaration) {
        if (declaration.typeParameters().stream()
                .noneMatch(parameter -> parameter.variance() == TypeParameterDescriptor.Variance.COVARIANT)) return;
        final var validator = new VariancePositionValidator(context, declaration.name());
        for (final var parameter : declaration.typeParameters()) {
            validator.validateClassParameter(declaration, parameter);
        }
    }

    static void validate(final ResolutionContext context, final Stmt.ContractDecl declaration) {
        if (declaration.typeParameters().stream()
                .noneMatch(parameter -> parameter.variance() == TypeParameterDescriptor.Variance.COVARIANT)) return;
        final var validator = new VariancePositionValidator(context, declaration.name());
        for (final var parameter : declaration.typeParameters()) {
            validator.validateContractParameter(declaration, parameter);
        }
    }

    static void validate(final ResolutionContext context, final Stmt.ProductDecl declaration) {
        if (declaration.typeParameters().stream()
                .noneMatch(parameter -> parameter.variance() == TypeParameterDescriptor.Variance.COVARIANT)) return;
        final var validator = new VariancePositionValidator(context, declaration.name());
        for (final var parameter : declaration.typeParameters()) {
            validator.validateProductParameter(declaration, parameter);
        }
    }

    static void validate(final ResolutionContext context, final Stmt.SumDecl declaration) {
        if (declaration.typeParameters().stream()
                .noneMatch(parameter -> parameter.variance() == TypeParameterDescriptor.Variance.COVARIANT)) return;
        final var validator = new VariancePositionValidator(context, declaration.name());
        for (final var parameter : declaration.typeParameters()) {
            validator.validateSumParameter(declaration, parameter);
        }
    }

    private void validateClassParameter(final Stmt.ClassDecl declaration,
                                        final TypeParameterDescriptor parameter) {
        if (parameter.variance() != TypeParameterDescriptor.Variance.COVARIANT) return;
        for (final var field : declaration.fields()) {
            check(parameter, field.type(), field.readOnly() ? COVARIANT : INVARIANT,
                    "field '" + field.name().lexeme() + "'");
        }
        for (final var property : declaration.properties()) {
            check(parameter, property.type(), property.isMutating() ? INVARIANT : COVARIANT,
                    "property '" + property.name().lexeme() + "'");
        }
        for (final var method : declaration.methods()) {
            checkFunction(parameter, method.typeDescriptor(), method.name().lexeme());
        }
        for (final var pattern : declaration.patterns()) {
            for (final var output : pattern.outputs()) {
                check(parameter, output.type(), COVARIANT, "pattern output '" + output.name().lexeme() + "'");
            }
        }
        for (final var contractUse : declaration.contractUses()) {
            checkContractUse(parameter, contractUse);
        }
        for (final var contractUse : declaration.higherKindedContractUses()) {
            checkContractUse(parameter, contractUse);
        }
    }

    private void validateProductParameter(final Stmt.ProductDecl declaration,
                                          final TypeParameterDescriptor parameter) {
        if (parameter.variance() != TypeParameterDescriptor.Variance.COVARIANT) return;
        for (final var component : declaration.components()) {
            check(parameter, component.type(), COVARIANT,
                    "component '" + component.name().lexeme() + "'");
        }
        checkDerivedContracts(parameter, declaration.derivedContracts());
    }

    private void validateSumParameter(final Stmt.SumDecl declaration,
                                      final TypeParameterDescriptor parameter) {
        if (parameter.variance() != TypeParameterDescriptor.Variance.COVARIANT) return;
        for (final var sumCase : declaration.cases()) {
            for (final var payload : sumCase.payloads()) {
                check(parameter, payload.type(), COVARIANT, "payload '" + payload.name().lexeme()
                        + "' of case '" + sumCase.name().lexeme() + "'");
            }
        }
        checkDerivedContracts(parameter, declaration.derivedContracts());
    }

    private void checkDerivedContracts(final TypeParameterDescriptor parameter,
                                       final List<Token> derivedContracts) {
        for (final var derived : derivedContracts) {
            final var contractName = TypeResolver.resolveTypeName(context, derived.lexeme(), derived);
            final var contract = contracts.get(contractName);
            if (contract == null || contract.typeParameters().isEmpty()) continue;
            final var variance = contract.typeParameters().getFirst().variance()
                    == TypeParameterDescriptor.Variance.COVARIANT ? COVARIANT : INVARIANT;
            check(parameter, parameter, variance, "derived contract '" + derived.lexeme() + "'");
        }
    }

    private void validateContractParameter(final Stmt.ContractDecl declaration,
                                           final TypeParameterDescriptor parameter) {
        if (parameter.variance() != TypeParameterDescriptor.Variance.COVARIANT) return;
        for (final var required : declaration.requiredContracts()) {
            checkContractUse(parameter, required);
        }
        for (final var method : declaration.methods()) {
            checkFunction(parameter, method.typeDescriptor(), method.name().lexeme());
        }
        for (final var property : declaration.properties()) {
            check(parameter, property.type(), property.isMutating() ? INVARIANT : COVARIANT,
                    "contract property '" + property.name().lexeme() + "'");
        }
    }

    private void checkContractUse(final TypeParameterDescriptor parameter,
                                 final Stmt.ContractUse contractUse) {
        checkContractUse(parameter, contractUse.name(), contractUse.typeArguments(),
                "contract use '" + contractUse.name().lexeme() + "'");
    }

    private void checkContractUse(final TypeParameterDescriptor parameter,
                                  final Token name,
                                  final List<TypeDescriptor> typeArguments,
                                  final String contextName) {
        final var contract = contracts.get(name.lexeme());
        if (contract == null || contract.typeParameters().size() != typeArguments.size()) return;
        for (int index = 0; index < typeArguments.size(); index++) {
            final var variance = contract.typeParameters().get(index).variance()
                    == TypeParameterDescriptor.Variance.COVARIANT ? COVARIANT : INVARIANT;
            check(parameter, typeArguments.get(index), variance, contextName);
        }
    }

    private void checkFunction(final TypeParameterDescriptor parameter,
                               final FunctionDescriptor function,
                               final String memberName) {
        for (final var argument : function.parameters()) {
            check(parameter, argument, CONTRAVARIANT, "parameter of '" + memberName + "'");
        }
        check(parameter, function.returnType(), COVARIANT, "result of '" + memberName + "'");
        for (final var effect : function.raisedEffects()) {
            check(parameter, effect, INVARIANT, "raised effect of '" + memberName + "'");
        }
        for (final var methodParameter : function.typeParameters()) {
            for (final var bound : methodParameter.bounds()) {
                check(parameter, bound, INVARIANT, "bound of '" + memberName + "'");
            }
        }
    }

    private void check(final TypeParameterDescriptor parameter,
                       final TypeDescriptor type,
                       final int position,
                       final String context) {
        switch (type) {
            case TypeParameterDescriptor occurrence -> {
                if (occurrence.equals(parameter) && position != COVARIANT) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_DECLARATION,
                            declarationName,
                            "Covariant type parameter '" + parameter.name()
                                    + "' occurs in an unsafe " + context + "."));
                }
            }
            case NullableDescriptor nullable -> check(parameter, nullable.baseType(), position, context);
            case ReferenceDescriptor reference ->
                    check(parameter, reference.baseType(), INVARIANT, context);
            case ArrayDescriptor array -> check(parameter, array.elementType(), INVARIANT, context);
            case GenericDescriptor generic -> {
                final var variances = variancesFor(generic.baseType().name(), generic.typeParameters().size());
                for (int index = 0; index < generic.typeParameters().size(); index++) {
                    final var nestedPosition = variances.get(index)
                            == TypeParameterDescriptor.Variance.COVARIANT
                            ? position : INVARIANT;
                    check(parameter, generic.typeParameters().get(index), nestedPosition, context);
                }
            }
            case FunctionDescriptor function -> {
                for (final var argument : function.parameters()) {
                    check(parameter, argument, reverse(position), context);
                }
                check(parameter, function.returnType(), position, context);
                for (final var effect : function.raisedEffects()) {
                    check(parameter, effect, INVARIANT, context);
                }
                for (final var methodParameter : function.typeParameters()) {
                    for (final var bound : methodParameter.bounds()) {
                        check(parameter, bound, INVARIANT, context);
                    }
                }
            }
            case AppliedConstructorDescriptor applied -> {
                check(parameter, applied.constructor(), INVARIANT, context);
                check(parameter, applied.argument(), INVARIANT, context);
            }
            case TypeConstructorDescriptor constructor -> {
                for (final var argument : constructor.arguments()) {
                    check(parameter, argument, INVARIANT, context);
                }
            }
            default -> {
            }
        }
    }

    private List<TypeParameterDescriptor.Variance> variancesFor(final String typeName, final int arity) {
        final var classDeclaration = classes.get(typeName);
        if (classDeclaration != null && classDeclaration.typeParameters().size() == arity) {
            return classDeclaration.typeParameters().stream()
                    .map(TypeParameterDescriptor::variance).toList();
        }
        final var contractDeclaration = contracts.get(typeName);
        if (contractDeclaration != null && contractDeclaration.typeParameters().size() == arity) {
            return contractDeclaration.typeParameters().stream()
                    .map(TypeParameterDescriptor::variance).toList();
        }
        return java.util.Collections.nCopies(arity, TypeParameterDescriptor.Variance.INVARIANT);
    }

    private static int reverse(final int position) {
        return -position;
    }
}
