package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.*;

import static com.maruseron.zeron.analize.Resolver.*;

final class DeclarationResolver {
    static void resolveContract(final ResolutionContext context, final Stmt.ContractDecl contract) {
        validateSealedContract(context, contract);
        final var methodNames = new HashSet<String>();
        for (final var method : contract.methods()) {
            if (!methodNames.add(method.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        method.name(), "Duplicate contract method."));
            }
            TypeResolver.validateFunctionTypes(context, method.typeDescriptor(), method.name());
            TypeResolver.validateTypeParameterBounds(context, method.typeDescriptor(), method.name());
            if (method.isDefault()) {
                resolveContractMethod(context, contract, method);
            }
        }
        for (final var property : contract.properties()) {
            if (!methodNames.add(property.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        property.name(), "Duplicate contract member."));
            }
            TypeResolver.validateType(context, property.type(), property.name());
        }
    }

    private static void validateSealedContract(final ResolutionContext context, final Stmt.ContractDecl contract) {
        if (!contract.isSealed()) {
            if (!contract.permittedClasses().isEmpty()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        contract.name(),
                        "Only sealed contracts may declare permitted classes."));
            }
            return;
        }
        if (contract.permittedClasses().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                    contract.name(),
                    "A sealed contract must declare at least one permitted class."));
        }

        final var permittedNames = new HashSet<String>();
        for (final var permitted : contract.permittedClasses()) {
            final var className = permitted.name().lexeme();
            if (!permittedNames.add(className)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "Duplicate permitted class."));
            }
            final var implementation = context.classes.get(className);
            if (implementation == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "Unknown permitted class."));
            }
            if (!packageOf(className).equals(packageOf(contract.name().lexeme()))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "A permitted class must be in the sealed contract's package."));
            }
            if (contract.isPublic() && !implementation.isPublic()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "A public sealed contract can only permit public classes."));
            }
            if (implementation.typeParameters().size() != contract.typeParameters().size()
                    || permitted.typeArguments().size() != contract.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "A permitted class must use the sealed contract's type parameters in order."));
            }
            for (int i = 0; i < contract.typeParameters().size(); i++) {
                if (!permitted.typeArguments().get(i).equals(contract.typeParameters().get(i))) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                            permitted.name(),
                            "A permitted class must use the sealed contract's type parameters in order."));
                }
            }
            final var conformance = implementation.contractUses().stream()
                    .filter(use -> use.name().lexeme().equals(contract.name().lexeme()))
                    .findFirst()
                    .orElse(null);
            if (conformance == null || conformance.typeArguments().size() != implementation.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "A permitted class must directly conform to the sealed contract."));
            }
            for (int i = 0; i < implementation.typeParameters().size(); i++) {
                if (!conformance.typeArguments().get(i).equals(implementation.typeParameters().get(i))) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                            permitted.name(),
                            "A permitted class must conform using its type parameters in order."));
                }
            }
        }
    }

    private static void resolveContractMethod(final ResolutionContext context, final Stmt.ContractDecl owner,
                                       final Stmt.ContractMethod method) {
        beginScope(context);
        context.frame.expectedReturnTypes.push(method.typeDescriptor().returnType());
        final var enclosingFlow = context.frame.flowState;
        final var enclosingLoopDepth = context.frame.loopDepth;
        context.frame.flowState = new FlowState();
        context.frame.loopDepth = 0;
        final var thisToken = new Token(TokenType.THIS, "this", null, method.name().span());
        final TypeDescriptor ownerType = owner.typeParameters().isEmpty()
                ? TypeDescriptor.of(owner.name().lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(owner.name().lexeme()),
                        owner.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
        final var thisType = method.isMutating()
                ? new ReferenceDescriptor(ownerType)
                : ownerType;
        declare(context, SYNTHETIC_VAR, thisToken, thisType, BindingMutability.IMMUTABLE);
        define(context, thisToken);
        for (int i = 0; i < method.parameters().size(); i++) {
            final var parameter = method.parameters().get(i);
            declare(context, SYNTHETIC_VAR, parameter, method.typeDescriptor().parameters().get(i),
                    BindingMutability.IMMUTABLE);
            define(context, parameter);
        }
        try {
            resolveStmts(context, method.body());
            ensureReturns(context, method.name(), method.typeDescriptor().returnType(), method.body());
        } finally {
            context.frame.expectedReturnTypes.pop();
            endScope(context);
            context.frame.loopDepth = enclosingLoopDepth;
            context.frame.flowState = enclosingFlow;
        }
    }

    static void resolveClass(final ResolutionContext context, final Stmt.ClassDecl declaration) {
        final var fieldNames = new HashSet<String>();
        for (final var field : declaration.fields()) {
            if (!fieldNames.add(field.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        field.name(), "Duplicate class member."));
            }
            TypeResolver.validateType(context, field.type(), field.name());
        }

        final var methodNames = new HashSet<String>();
        for (final var property : declaration.properties()) {
            if (!fieldNames.add(property.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        property.name(), "Duplicate class member."));
            }
            TypeResolver.validateType(context, property.type(), property.name());
            if (property.isMutating() && property.setterBody() == null && property.isCustom()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        property.name(),
                        "A writable custom property requires a setter."));
            }
        }
        for (final var method : declaration.methods()) {
            if (!fieldNames.add(method.name().lexeme()) || !methodNames.add(method.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        method.name(), "Duplicate class member."));
            }
            TypeResolver.validateFunctionTypes(context, method.typeDescriptor(), method.name());
            TypeResolver.validateTypeParameterBounds(context, method.typeDescriptor(), method.name());
        }

        final var contractNames = new HashSet<String>();
        for (final var contractUse : declaration.contractUses()) {
            final var contractName = contractUse.name();
            if (!contractNames.add(contractName.lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        contractName, "Duplicate contract conformance."));
            }
            final var contract = context.contracts.get(contractName.lexeme());
            if (contract == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND,
                        contractName, "Unknown contract."));
            }
            ensureTypeAccessible(context, contractName.lexeme(), contractName, contract.isPublic());
            if (contract.typeParameters().size() != contractUse.typeArguments().size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        contractName,
                        "Expected " + contract.typeParameters().size() + " contract type arguments, found "
                                + contractUse.typeArguments().size() + "."));
            }
            contractUse.typeArguments().forEach(type -> TypeResolver.validateType(context, type, contractName));
            if (contract.isSealed() && contract.permittedClasses().stream()
                    .noneMatch(permitted -> permitted.name().lexeme().equals(declaration.name().lexeme()))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        contractName,
                        "Class is not listed in the sealed contract's permits clause."));
            }
        }

        final var previousClass = context.frame.currentClassName;
        final var previousInitializerFields = context.frame.initializerVisibleFields;
        context.frame.currentClassName = declaration.name().lexeme();
        try {
            for (int fieldIndex = 0; fieldIndex < declaration.fields().size(); fieldIndex++) {
                final var field = declaration.fields().get(fieldIndex);
                if (field.initializer() == null) continue;
                resolveFieldInitializer(context, declaration, field,
                        declaration.fields().subList(0, fieldIndex));
            }
            for (final var property : declaration.properties()) {
                if (property.initializer() != null) {
                    resolveFieldInitializer(context, declaration,
                            new Stmt.Field(property.name(), property.type(), property.initializer()),
                            declaration.fields());
                }
                if (property.isCustom()) resolvePropertyAccessors(context, declaration, property);
            }
            for (final var method : declaration.methods()) resolveMethod(context, declaration, method);
            for (final var constructor : declaration.namedConstructors()) {
                resolveNamedConstructor(context, constructor);
            }
            for (final var contractUse : declaration.contractUses()) {
                checkConformance(context, declaration, contractUse, context.contracts.get(contractUse.name().lexeme()));
            }
        } finally {
            context.frame.currentClassName = previousClass;
            context.frame.initializerVisibleFields = previousInitializerFields;
        }
    }

    private static void resolveFieldInitializer(final ResolutionContext context, final Stmt.ClassDecl owner,
                                        final Stmt.Field field,
                                        final List<Stmt.Field> earlierFields) {
        validateFieldInitializer(context, field.initializer(), earlierFields, field.name());
        final var previousMethodOwner = context.frame.currentMethodOwner;
        final var previousInitializerFields = context.frame.initializerVisibleFields;
        final var previousFlow = context.frame.flowState;
        context.frame.currentMethodOwner = owner;
        context.frame.initializerVisibleFields = List.copyOf(earlierFields);
        context.frame.flowState = new FlowState();
        beginScope(context);
        final var thisToken = new Token(TokenType.THIS, "this", null, field.name().span());
        declare(context, SYNTHETIC_VAR, thisToken, TypeResolver.classType(context, owner), BindingMutability.IMMUTABLE);
        define(context, thisToken);
        try {
            final var initializerType = ExpressionFlowResolver.resolveExpression(context, field.initializer());
            ensureAssignable(context, field.type(), initializerType, field.name());
        } finally {
            endScope(context);
            context.frame.currentMethodOwner = previousMethodOwner;
            context.frame.initializerVisibleFields = previousInitializerFields;
            context.frame.flowState = previousFlow;
        }
    }

    private static void validateFieldInitializer(final ResolutionContext context, final Expr initializer,
                                          final List<Stmt.Field> earlierFields,
                                          final Token where) {
        switch (initializer) {
            case Expr.Literal _ -> {}
            case Expr.Grouping grouping ->
                    validateFieldInitializer(context, grouping.expression, earlierFields, where);
            case Expr.Unary unary ->
                    validateFieldInitializer(context, unary.right, earlierFields, where);
            case Expr.Binary binary -> {
                validateFieldInitializer(context, binary.left, earlierFields, where);
                validateFieldInitializer(context, binary.right, earlierFields, where);
            }
            case Expr.Logical logical -> {
                validateFieldInitializer(context, logical.left, earlierFields, where);
                validateFieldInitializer(context, logical.right, earlierFields, where);
            }
            case Expr.Variable variable -> {
                if (earlierFields.stream().noneMatch(candidate ->
                        candidate.name().lexeme().equals(variable.name.lexeme()))
                        || !variable.explicitFunctionTypeArguments.isEmpty()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_CONTROL_FLOW_OR_INITIALIZATION_FLOW,
                            variable.name,
                            "A field initializer may read only fields declared earlier."));
                }
            }
            default -> Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.INVALID_CONTROL_FLOW_OR_INITIALIZATION_FLOW, where,
                    "Field initializers currently allow only literals, operators, and reads of earlier fields."));
        }
    }

    private static void resolveMethod(final ResolutionContext context, final Stmt.ClassDecl owner, final Stmt.Method method) {
        beginScope(context);
    context.frame.expectedReturnTypes.push(method.typeDescriptor().returnType());
        final var enclosingFlow = context.frame.flowState;
        final var enclosingMethodOwner = context.frame.currentMethodOwner;
        context.frame.currentMethodOwner = owner;
        context.frame.flowState = new FlowState();
        final var thisToken = new Token(TokenType.THIS, "this", null, method.name().span());
        final TypeDescriptor ownerType = TypeResolver.classType(context, owner);
        final TypeDescriptor thisType = method.isMutating()
            ? new ReferenceDescriptor(ownerType)
            : ownerType;
        declare(context, SYNTHETIC_VAR, thisToken, thisType, BindingMutability.IMMUTABLE);
        define(context, thisToken);
        for (int i = 0; i < method.parameters().size(); i++) {
            final var parameter = method.parameters().get(i);
            declare(context, SYNTHETIC_VAR, parameter, method.typeDescriptor().parameters().get(i), BindingMutability.IMMUTABLE);
            define(context, parameter);
        }
        try {
            resolveStmts(context, method.body());
            ensureReturns(context, method.name(), method.typeDescriptor().returnType(), method.body());
        } finally {
            context.frame.expectedReturnTypes.pop();
            endScope(context);
            context.frame.flowState = enclosingFlow;
            context.frame.currentMethodOwner = enclosingMethodOwner;
        }
    }

    private static void resolvePropertyAccessors(final ResolutionContext context, final Stmt.ClassDecl owner,
                                          final Stmt.Property property) {
        if (property.getterBody() != null) {
            final var getterName = new Token(TokenType.IDENTIFIER,
                    Stmt.propertyGetterName(property.name().lexeme()), null, property.name().span());
            final var getterType = TypeDescriptor.functionOf(getterName.lexeme(), property.type());
            resolveMethod(context, owner, new Stmt.Method(getterName, List.of(), getterType,
                    property.isPublic(), false, property.getterBody()));
        }
        if (property.setterBody() != null) {
            final var setterName = new Token(TokenType.IDENTIFIER,
                    Stmt.propertySetterName(property.name().lexeme()), null, property.name().span());
            final var setterType = TypeDescriptor.functionOf(setterName.lexeme(),
                    TypeDescriptor.ofUnit(), property.type());
            resolveMethod(context, owner, new Stmt.Method(setterName, List.of(property.setterParameter()),
                    setterType, property.isPublic(), true, property.setterBody()));
        }
    }

    private static void resolveNamedConstructor(final ResolutionContext context, final Stmt.NamedConstructor constructor) {
        beginScope(context);
        final var enclosingFlow = context.frame.flowState;
        final var enclosingLoopDepth = context.frame.loopDepth;
        context.frame.flowState = new FlowState();
        context.frame.loopDepth = 0;
        try {
            context.frame.expectedReturnTypes.push(constructor.typeDescriptor().returnType());
            for (int i = 0; i < constructor.parameters().size(); i++) {
                final var parameter = constructor.parameters().get(i);
                declare(context, SYNTHETIC_VAR, parameter, constructor.typeDescriptor().parameters().get(i),
                        BindingMutability.IMMUTABLE);
                define(context, parameter);
            }
            resolveStmts(context, constructor.body());
            if (context.frame.flowState.isReachable()) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.INVALID_CONTROL_FLOW_OR_INITIALIZATION_FLOW, constructor.name(),
                        "Named constructor must return an instance on every normal path."));
            }
        } finally {
            if (!context.frame.expectedReturnTypes.isEmpty()) context.frame.expectedReturnTypes.pop();
            endScope(context);
            context.frame.loopDepth = enclosingLoopDepth;
            context.frame.flowState = enclosingFlow;
        }
    }

    private static void checkConformance(final ResolutionContext context, final Stmt.ClassDecl declaration,
                                  final Stmt.ContractUse contractUse,
                                  final Stmt.ContractDecl contract) {
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < contract.typeParameters().size(); i++) {
            substitutions.put(contract.typeParameters().get(i), contractUse.typeArguments().get(i));
        }
        for (final var required : contract.methods()) {
            final var implementation = declaration.methods().stream()
                    .filter(method -> method.name().lexeme().equals(required.name().lexeme()))
                    .findFirst()
                    .orElse(null);
            final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                    required.typeDescriptor(), substitutions);
            final var defaults = implementation == null
                    ? defaultMethodsFor(context, declaration, requiredType)
                    : List.<Resolver.DefaultMethodSelection>of();
            if (defaults.size() > 1) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        contractUse.name(),
                        "Multiple default contract methods named '" + required.name().lexeme()
                                + "' require an explicit class implementation."));
            }
            final var defaultMethod = defaults.size() == 1
                    && defaults.getFirst().method().isMutating() == required.isMutating()
                    ? defaults.getFirst()
                    : null;
            if (implementation == null && defaultMethod == null
                    || implementation != null && (!implementation.isPublic()
                    || implementation.isMutating() != required.isMutating()
                    || !compatibleMethodSignatures(context, requiredType, implementation.typeDescriptor()))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        contractUse.name(),
                        "Class does not provide a compatible public contract method '"
                                + required.name().lexeme() + "'."));
            }
        }

        for (final var required : contract.properties()) {
            final var implementation = declaration.properties().stream()
                    .filter(property -> property.name().lexeme().equals(required.name().lexeme()))
                    .findFirst()
                    .orElse(null);
            final var requiredType = TypeSubstitution.substitute(required.type(), substitutions);
            if (implementation == null || !implementation.isPublic()
                    || (required.isMutating() && !implementation.isMutating())
                    || !requiredType.equals(implementation.type())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE,
                        contractUse.name(),
                        "Class does not provide a compatible public contract property '"
                                + required.name().lexeme() + "'."));
            }
        }
    }

    static Resolver.DefaultMethodSelection defaultMethodFor(final ResolutionContext context, final Stmt.ClassDecl declaration,
                                                            final Stmt.ContractUse requiredUse,
                                                            final Stmt.ContractMethod required) {
        final var substitutions = contractSubstitutions(context, requiredUse);
        final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                required.typeDescriptor(), substitutions);
        final var matches = defaultMethodsFor(context, declaration, requiredType);
        return matches.size() == 1
                && matches.getFirst().method().isMutating() == required.isMutating()
                ? matches.getFirst()
                : null;
    }

    private static List<Resolver.DefaultMethodSelection> defaultMethodsFor(final ResolutionContext context, final Stmt.ClassDecl declaration,
                                                                   final FunctionDescriptor requiredType) {
        final var matches = new ArrayList<Resolver.DefaultMethodSelection>();
        for (final var contractUse : declaration.contractUses()) {
            final var candidateContract = context.contracts.get(contractUse.name().lexeme());
            final var substitutions = contractSubstitutions(context, contractUse);
            for (final var candidate : candidateContract.methods()) {
                if (!candidate.isDefault()
                        || !candidate.name().lexeme().equals(requiredType.name())) continue;
                final var candidateType = (FunctionDescriptor) TypeSubstitution.substitute(
                        candidate.typeDescriptor(), substitutions);
                if (compatibleMethodSignatures(context, requiredType, candidateType)) {
                    matches.add(new Resolver.DefaultMethodSelection(candidateContract.name().lexeme(), candidate,
                            candidateType));
                }
            }
        }
        return List.copyOf(matches);
    }

    static List<Resolver.DefaultMethodSelection> defaultMethodsOnClass(final ResolutionContext context, final Stmt.ClassDecl declaration,
                                                                       final String methodName) {
        final var matches = new ArrayList<Resolver.DefaultMethodSelection>();
        for (final var contractUse : declaration.contractUses()) {
            final var contract = context.contracts.get(contractUse.name().lexeme());
            final var substitutions = contractSubstitutions(context, contractUse);
            for (final var method : contract.methods()) {
                if (!method.isDefault() || !method.name().lexeme().equals(methodName)) continue;
                final var type = (FunctionDescriptor) TypeSubstitution.substitute(
                        method.typeDescriptor(), substitutions);
                matches.add(new Resolver.DefaultMethodSelection(contract.name().lexeme(), method, type));
            }
        }
        return List.copyOf(matches);
    }

    private static LinkedHashMap<TypeParameterDescriptor, TypeDescriptor> contractSubstitutions(
            final ResolutionContext context,
            final Stmt.ContractUse contractUse) {
        final var contract = context.contracts.get(contractUse.name().lexeme());
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < contract.typeParameters().size(); i++) {
            substitutions.put(contract.typeParameters().get(i), contractUse.typeArguments().get(i));
        }
        return substitutions;
    }

    private static boolean compatibleMethodSignatures(final ResolutionContext context, final FunctionDescriptor required,
                                               final FunctionDescriptor implementation) {
        if (required.typeParameters().size() != implementation.typeParameters().size()
                || required.arity() != implementation.arity()) return false;
        final var methodSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < required.typeParameters().size(); i++) {
            methodSubstitutions.put(required.typeParameters().get(i),
                    implementation.typeParameters().get(i));
        }
        for (int i = 0; i < required.typeParameters().size(); i++) {
            final var requiredBounds = required.typeParameters().get(i).bounds().stream()
                    .map(bound -> TypeSubstitution.substitute(bound, methodSubstitutions))
                    .toList();
            if (!requiredBounds.equals(implementation.typeParameters().get(i).bounds())) return false;
        }
        for (int i = 0; i < required.arity(); i++) {
            if (!TypeSubstitution.substitute(required.parameters().get(i), methodSubstitutions)
                    .equals(implementation.parameters().get(i))) return false;
        }
        final var requiredReturn = TypeSubstitution.substitute(required.returnType(), methodSubstitutions);
        return context.typeCompatibility.canAssign(requiredReturn, implementation.returnType());
    }

}
