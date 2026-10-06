package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.NamespaceMembers;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.DiagnosticHelp;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class Resolver {
    public record IterationProtocol(String iterableName, String iteratorName) {}

    public record DefaultMethodSelection(String ownerName, Stmt.ContractMethod method,
                                         FunctionDescriptor instantiatedType) {}

    static final AtomicInteger LAMBDA_TYPE_SCOPES = new AtomicInteger(Integer.MIN_VALUE + 1_000);

    public static final Token SYNTHETIC_IDENTIFIER = new Token(
            TokenType.IDENTIFIER,"<synthetic>", null, -1);

    public static final Stmt SYNTHETIC_VAR = new Stmt.Var(
            SYNTHETIC_IDENTIFIER,
            TypeDescriptor.ofNever(),
            null,
            BindingMutability.IMMUTABLE);
            
    public static final Stmt SYNTHETIC_FUN = new Stmt.Function(
            SYNTHETIC_IDENTIFIER,
            List.of(),
            TypeDescriptor.functionOf("<synthetic>", TypeDescriptor.ofUnit()),
            List.of(),
            false);

    static String qualify(final String ownerPackage, final String simpleName) {
        return ownerPackage == null || ownerPackage.isEmpty()
                ? simpleName
                : ownerPackage + "." + simpleName;
    }

    static ResolutionResult resolve(final ResolutionContext context, final List<Stmt> statements) {
        return resolveUnits(context,
                List.of(new CompilationUnit(null, context.packageName, statements)));
    }

    static ResolutionResult resolveUnits(
            final ResolutionContext context, final List<CompilationUnit> units) {
        for (final var unit : units) {
            for (final var entry : NamespaceMembers.flatten(unit.declarations())) {
                final var declaration = entry.declaration();
                if (declarationName(declaration) != null
                        && (declaration instanceof Stmt.FunctionDeclaration || declaration instanceof Stmt.Var)) {
                    final var qualifiedName = entry.namespaceName() == null
                            ? qualify(unit.packageName(), declarationName(declaration).lexeme())
                            : NamespaceMembers.qualifiedName(unit.packageName(), entry.namespaceName(),
                                declarationName(declaration).lexeme());
                    context.declarationPackages.put(qualifiedName, unit.packageName());
                    if (entry.namespaceName() != null) {
                        context.namespaceMemberPackages.put(qualifiedName, unit.packageName());
                    }
                }
                context.sourcePathsByDeclaration.put(declaration, unit.sourcePath());
                if (entry.namespaceName() != null) {
                    context.namespaceNamesByDeclaration.put(declaration, entry.namespaceName());
                }
                final var name = declarationName(declaration);
                if (name != null) context.sourcePathsByToken.put(name, unit.sourcePath());
            }
        }
        for (final var unit : units) {
            context.currentSourcePath = unit.sourcePath();
            for (final var entry : NamespaceMembers.flatten(unit.declarations())) {
                final var declaration = entry.declaration();
                if (declaration instanceof Stmt.ClassDecl classDeclaration) {
                    attempt(context, () -> context.declarationRegistrar.registerTypes(
                            unit.packageName(), List.of(classDeclaration)));
                } else if (declaration instanceof Stmt.ContractDecl contractDeclaration) {
                    attempt(context, () -> context.declarationRegistrar.registerTypes(
                            unit.packageName(), List.of(contractDeclaration)));
                } else if (declaration instanceof Stmt.ExternalClass externalClass) {
                    attempt(context, () -> context.declarationRegistrar.registerTypes(
                            unit.packageName(), List.of(externalClass)));
                }
            }
        }
        for (var unitIndex = 0; unitIndex < units.size(); unitIndex++) {
            final var unit = units.get(unitIndex);
            context.currentSourcePath = unit.sourcePath();
            for (final var entry : NamespaceMembers.flatten(unit.declarations())) {
                final var declaration = entry.declaration();
                if (declaration instanceof Stmt.Var variable) {
                    attempt(context, () -> context.declarationRegistrar.registerTopLevelValue(
                            unit, variable, entry.namespaceName()));
                }
                if (!(declaration instanceof Stmt.FunctionDeclaration function)) continue;
                if (unitIndex > 0 && function.typeDescriptor().returnType() instanceof InferDescriptor) {
                    attempt(context, () -> Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE, function.name(),
                            "Non-entry compilation-unit functions require an explicit return type.")));
                    continue;
                }
                if (!(function.typeDescriptor().returnType() instanceof InferDescriptor)) {
                    attempt(context, () -> context.declarationRegistrar.registerFunction(
                            unit.packageName(), entry.namespaceName(), function));
                }
            }
        }
        for (final var value : context.topLevelValues.entrySet()) {
            if (context.functions.containsKey(value.getKey())) {
                context.currentSourcePath = context.sourcePath(value.getValue());
                attempt(context, () -> Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME, value.getValue().name(),
                        "A top-level function and value cannot share the name '" + value.getKey() + "'.")));
            }
        }
        final var importsByUnit = new IdentityHashMap<CompilationUnit, ImportResolver.ImportEnvironment>();
        final var invalidAliasesByUnit = new IdentityHashMap<CompilationUnit, Set<String>>();
        for (final var unit : units) {
            context.packageName = unit.packageName();
            context.currentSourcePath = unit.sourcePath();
            final var validation = context.importResolver.validate(unit);
            importsByUnit.put(unit, validation.environment());
            invalidAliasesByUnit.put(unit, validation.invalidAliases());
            validation.errors().forEach(error -> context.diagnostics.record(error, unit.sourcePath()));
        }
        for (final var unit : units) {
            context.packageName = unit.packageName();
            context.currentSourcePath = unit.sourcePath();
            context.currentImports = importsByUnit.get(unit);
            context.invalidImportAliases = invalidAliasesByUnit.get(unit);
            for (final var entry : NamespaceMembers.flatten(unit.declarations())) {
                final var declaration = entry.declaration();
                if (!(declaration instanceof Stmt.Var variable)) continue;
                if (variable.type() instanceof InferDescriptor) continue;
                final var symbol = context.topLevelTokensByDeclaration.get(variable);
                if (symbol != null && !context.symbols.containsSymbol(symbol)) {
                    attempt(context,
                            () -> context.symbols.declareSymbol(variable, symbol, variable.type(), variable.mutability()));
                }
            }
        }
        List<TopLevelValuePlanner.SourceValue> valueOrder;
        try {
            valueOrder = context.topLevelValuePlanner.resolutionOrder(
                    units);
        } catch (final ResolutionError error) {
            context.diagnostics.record(error, context.sourcePath(error.token));
            valueOrder = List.of();
            context.recoverAfterDeclarationFailure();
        }
        for (final var sourceValue : valueOrder) {
            context.packageName = sourceValue.unit().packageName();
            context.currentSourcePath = sourceValue.unit().sourcePath();
            context.currentImports = importsByUnit.get(sourceValue.unit());
            context.invalidImportAliases = invalidAliasesByUnit.get(sourceValue.unit());
            context.currentNamespaceName = context.namespaceNamesByDeclaration.get(sourceValue.declaration());
            if (!attempt(context, () -> resolve(context, sourceValue.declaration()))) {
                final var symbol = context.topLevelTokensByDeclaration.get(sourceValue.declaration());
                if (symbol != null) context.symbols.removeGlobalSymbol(symbol, sourceValue.declaration());
            }
            context.currentNamespaceName = null;
        }
        for (final var unit : units) {
            context.packageName = unit.packageName();
            context.currentSourcePath = unit.sourcePath();
            context.currentImports = importsByUnit.get(unit);
            context.invalidImportAliases = invalidAliasesByUnit.get(unit);
            if (unit.metadataOnly()) continue;
            for (final var entry : NamespaceMembers.flatten(unit.declarations())) {
                final var statement = entry.declaration();
                if (!(statement instanceof Stmt.Var) && !(statement instanceof Stmt.ExternalClass)) {
                    context.currentNamespaceName = entry.namespaceName();
                    attempt(context, () -> resolve(context, statement));
                }
            }
            context.currentNamespaceName = null;
        }
        for (final var lambda : context.resolvedLambdas) {
            if (LambdaResolver.containsInfer(context, lambda.getType())) {
                context.diagnostics.record(ResolutionError.withHelp(
                        DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE,
                        lambda.arrow,
                        "Cannot infer the lambda's function type.",
                        new DiagnosticHelp("Add an explicit function type annotation to the binding.")),
                        lambda.arrow.span().sourcePath());
            }
        }
        Zeron.debug("resolution finished successfully with symbol table: \n" + context.symbols);
        return new ResolutionResult(
                context.symbols,
                context.types,
                context.diagnostics.snapshot(),
                context.classes,
                context.contracts,
                context.topLevelTokensByDeclaration,
                context.externalFunctionBindings,
                context.functionSymbolTokens,
                context.functionNamesByDeclaration,
                context.iterationElementTypes,
                context.iterationProtocols,
                context.packageName);
    }

    private static boolean attempt(final ResolutionContext context, final Runnable operation) {
        try {
            operation.run();
            return true;
        } catch (final SkipResolutionUnit _) {
            context.recoverAfterDeclarationFailure();
            return false;
        } catch (final ResolutionError error) {
            context.diagnostics.record(error, context.currentSourcePath);
            context.recoverAfterDeclarationFailure();
            return false;
        }
    }

    private static Token declarationName(final Stmt declaration) {
        return switch (declaration) {
            case Stmt.ClassDecl classDeclaration -> classDeclaration.name();
            case Stmt.ContractDecl contractDeclaration -> contractDeclaration.name();
            case Stmt.ExternalClass externalClass -> externalClass.name();
            case Stmt.FunctionDeclaration function -> function.name();
            case Stmt.Var variable -> variable.name();
            default -> null;
        };
    }

    static void resolveContract(final ResolutionContext context, final Stmt.ContractDecl contract) {
        DeclarationResolver.resolveContract(context, contract);
    }

    static void resolveClass(final ResolutionContext context, final Stmt.ClassDecl declaration) {
        DeclarationResolver.resolveClass(context, declaration);
    }

    static Resolver.DefaultMethodSelection defaultMethodFor(final ResolutionContext context,
            final Stmt.ClassDecl declaration, final Stmt.ContractUse requiredUse,
            final Stmt.ContractMethod required) {
        return DeclarationResolver.defaultMethodFor(context, declaration, requiredUse, required);
    }

    static void validateFunctionTypes(final ResolutionContext context, final FunctionDescriptor function, final Token where) {
        TypeResolver.validateFunctionTypes(context, function, where);
    }

    static void validateTypeParameterBound(final ResolutionContext context, final TypeParameterDescriptor parameter, final Token where) {
        TypeResolver.validateTypeParameterBound(context, parameter, where);
    }

    static void validateType(final ResolutionContext context, final TypeDescriptor type, final Token where) {
        TypeResolver.validateType(context, type, where);
    }

    static TypeDescriptor classType(final ResolutionContext context, final Stmt.ClassDecl declaration) {
        return TypeResolver.classType(context, declaration);
    }

    static Map<TypeParameterDescriptor, TypeDescriptor> substitutionsFor(
            final ResolutionContext context, final List<TypeParameterDescriptor> parameters, final TypeDescriptor receiverType) {
        return TypeResolver.substitutionsFor(context, parameters, receiverType);
    }

    private static void resolve(final ResolutionContext context, final Stmt stmt) {
        context.statementResolver.resolve(stmt);
    }

    static void resolveStmts(final ResolutionContext context, final List<Stmt> statements) {
        context.statementResolver.resolveStatements(statements);
    }

    static TypeDescriptor resolveExpression(final ResolutionContext context, final Expr expression) {
        return ExpressionFlowResolver.resolveExpression(context, expression);
    }

    static StatementResolver.ConditionFlows resolveCondition(final ResolutionContext context,
            final Expr expression, final FlowState incoming, final Token where) {
        return ExpressionFlowResolver.resolveCondition(context, expression, incoming, where);
    }

    static TypeDescriptor resolveMemberCall(final ResolutionContext context, final Expr.MemberCall call) {
        return MemberInteropResolver.resolveMemberCall(context, call);
    }

    static TypeDescriptor resolveProperty(final ResolutionContext context, final Expr.Property property) {
        return MemberInteropResolver.resolveProperty(context, property);
    }

    static String resolveFunctionName(final ResolutionContext context, final String name, final Token where) {
        return MemberInteropResolver.resolveFunctionName(context, name, where);
    }

    static Token resolveTopLevelValueSymbol(final ResolutionContext context, final Token name) {
        return MemberInteropResolver.resolveTopLevelValueSymbol(context, name);
    }

    static TypeDescriptor resolveArgument(final ResolutionContext context, final Expr argument,
            final TypeDescriptor expectedType) {
        return MemberInteropResolver.resolveArgument(context, argument, expectedType);
    }

    static FunctionDescriptor generalizeLambda(final ResolutionContext context, final Expr.Lambda lambda,
            final FunctionDescriptor inferredType) {
        return LambdaResolver.generalizeLambda(context, lambda, inferredType);
    }

    static FunctionDescriptor resolveLambda(final ResolutionContext context, final Expr.Lambda lambda,
            final FunctionDescriptor expectedType) {
        return LambdaResolver.resolveLambda(context, lambda, expectedType);
    }

    static void resolveFunction(final ResolutionContext context, final Stmt.Function function) {
        LambdaResolver.resolveFunction(context, function);
    }

    static String className(final ResolutionContext context, final TypeDescriptor type) {
        var baseType = type instanceof ReferenceDescriptor reference ? reference.baseType() : type;
        if (baseType instanceof GenericDescriptor generic) baseType = generic.baseType();
        return baseType instanceof NominalDescriptor nominal ? nominal.name() : "";
    }

    static void ensureTypeAccessible(final ResolutionContext context, final String name,
                                      final Token where,
                                      final boolean isPublic) {
        if (!packageOf(name).equals(context.packageName) && !isPublic
                && !context.currentImports.types().containsValue(name)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                    where, "Type '" + name + "' is not public."));
        }
    }

    static String packageOf(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? "" : qualifiedName.substring(0, separator);
    }

    static String simpleName(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    }

    static void beginScope(final ResolutionContext context) {
        context.symbols.beginScope();
    }

    static void endScope(final ResolutionContext context) {
        context.symbols.endScope();
    }

    static void declare(final ResolutionContext context, final Stmt declaration,
                         final Token name,
                         final TypeDescriptor type,
                         final BindingMutability mutability) {
        context.symbols.declareSymbol(declaration, name, type, mutability);
    }

    static void define(final ResolutionContext context, final Token name) {
        context.symbols.define(name);
    }

    static TypeDescriptor arrayElementType(final ResolutionContext context, final TypeDescriptor resultType, final Token where) {
        return IntrinsicResolver.arrayElementType(context, resultType, where);
    }

    static ArrayDescriptor resolveArrayType(final ResolutionContext context, final TypeDescriptor type, final Token where) {
        return IntrinsicResolver.resolveArrayType(context, type, where);
    }

    static ResolvedIntrinsicOperation resolveIntrinsic(final ResolutionContext context, final IntrinsicId id,
            final List<TypeDescriptor> typeArguments, final List<TypeDescriptor> argumentTypes, final Token where) {
        return IntrinsicResolver.resolveIntrinsic(context, id, typeArguments, argumentTypes, where);
    }

    static TypeDescriptor ensureReturns(final ResolutionContext context, final Token where,
                                        final TypeDescriptor expectedType,
                                        final List<Stmt> statements) {
        var currentType = expectedType;
        for (final var statement : statements) {
            if (statement instanceof Stmt.Return(Expr value, Token location)) {
                final var returnType = value == null
                        ? TypeDescriptor.ofUnit()
                        : resolveArgument(context, value, expectedType);
                if (currentType instanceof InferDescriptor)
                    currentType = returnType;
                else
                    ensureAssignable(context, currentType, returnType, location == null ? where : location);
            }
        }
        return currentType instanceof InferDescriptor ? TypeDescriptor.ofUnit() : currentType;
    }

    static TypeDescriptor ensureExact(final ResolutionContext context, final Token where,
                                      final TypeDescriptor typeA,
                                      final TypeDescriptor typeB) {
        return context.typeCompatibility.ensureExact(where, typeA, typeB);
    }

    static TypeDescriptor ensureCommonParent(final ResolutionContext context, final Token where,
                                             final TypeDescriptor typeA,
                                             final TypeDescriptor typeB) {
        return context.typeCompatibility.ensureCommonParent(where, typeA, typeB);
    }

    static TypeDescriptor ensureAssignable(final ResolutionContext context, TypeDescriptor expectedType, TypeDescriptor resolvedType) {
        return ensureAssignable(context, expectedType, resolvedType, SYNTHETIC_IDENTIFIER);
    }

    static TypeDescriptor ensureAssignable(final ResolutionContext context, TypeDescriptor expectedType,
                                            TypeDescriptor resolvedType,
                                            Token where) {
        return context.typeCompatibility.ensureAssignable(expectedType, resolvedType, where);
    }

    static boolean isContractProjection(final ResolutionContext context,
                                        final TypeDescriptor expectedType,
                                        final TypeDescriptor resolvedType) {
        return context.typeCompatibility.isContractProjection(expectedType, resolvedType);
    }

    static void ensureBoolean(final ResolutionContext context, TypeDescriptor type) {
        ensureBoolean(context, type, SYNTHETIC_IDENTIFIER);
    }

    static void ensureBoolean(final ResolutionContext context, TypeDescriptor type, Token where) {
        context.typeCompatibility.ensureBoolean(type, where);
    }

    static void ensureIterable(final ResolutionContext context, final TypeDescriptor type) {
        ensureIterable(context, type, SYNTHETIC_IDENTIFIER);
    }

    static TypeDescriptor ensureIterable(final ResolutionContext context, final TypeDescriptor type, final Token where) {
        final var arrayType = type instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : type;
        if (arrayType instanceof ArrayDescriptor array) return array.elementType();
        final var baseName = className(context, arrayType);
        final var iterableName = "zeron.collections.Iterable";
        if (baseName.equals(iterableName) && arrayType instanceof GenericDescriptor generic
                && generic.typeParameters().size() == 1) {
            recordIterationProtocol(context, where, iterableName);
            return generic.typeParameters().getFirst();
        }
        final var declaration = context.classes.get(baseName);
        if (declaration != null) {
            final var substitutions = substitutionsFor(context, declaration.typeParameters(), arrayType);
            for (final var contractUse : declaration.contractUses()) {
                if (!iterableName.equals(contractUse.name().lexeme())
                        || contractUse.typeArguments().size() != 1) continue;
                recordIterationProtocol(context, where, iterableName);
                return TypeSubstitution.substitute(contractUse.typeArguments().getFirst(), substitutions);
            }
        }
        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_CONTROL_FLOW_OR_INITIALIZATION_FLOW, where,
                "For loops require Array<T>, an integer range literal, or a type conforming to Iterable<T>."));
        return TypeDescriptor.ofInfer();
    }

    private static void recordIterationProtocol(final ResolutionContext context, final Token iterationBind, final String iterableName) {
        final var iterable = context.contracts.get(iterableName);
        if (iterable == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    iterationBind,
                    "Missing iterator protocol contract '" + iterableName + "'."));
        }
        final var iteratorMethod = iterable.methods().stream()
                .filter(method -> method.name().lexeme().equals("iterator"))
                .findFirst()
                .orElseThrow(() -> new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        iterationBind,
                        "Iterable contract must declare iterator()."));
        var iteratorType = iteratorMethod.typeDescriptor().returnType();
        if (iteratorType instanceof ReferenceDescriptor reference) iteratorType = reference.baseType();
        final String iteratorName = iteratorType instanceof GenericDescriptor generic
                ? generic.baseType().name()
                : iteratorType instanceof NominalDescriptor nominal ? nominal.name() : null;
        if (iteratorName == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    iterationBind,
                    "Iterable.iterator() must return an Iterator<T> reference."));
        }
        context.iterationProtocols.put(iterationBind, new Resolver.IterationProtocol(iterableName, iteratorName));
    }
}
