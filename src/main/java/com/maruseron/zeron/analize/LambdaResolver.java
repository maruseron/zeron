package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.*;

import static com.maruseron.zeron.analize.Resolver.*;

final class LambdaResolver {
    static FunctionDescriptor instantiateLambdaScheme(final ResolutionContext context, final FunctionDescriptor scheme,
                                                        final FunctionDescriptor expectedType,
                                                        final Token where) {
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        if (TypeSubstitution.containsTypeParameter(expectedType)) {
            unifyFunctionReferenceType(context, scheme, expectedType, substitutions, where);
        } else {
            TypeUnifier.unify(scheme, expectedType, substitutions, where);
        }
        for (final var parameter : scheme.typeParameters()) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, where,
                        "Cannot infer type parameter '" + parameter.name()
                                + "' for polymorphic lambda at this use."));
            }
        }
        final var specialized = TypeDescriptor.functionWithEffectsOf(scheme.name(),
                TypeSubstitution.substitute(scheme.returnType(), substitutions),
                scheme.parameters().stream()
                        .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                        .toList(), List.of(), scheme.raisedEffects().stream()
                        .map(effect -> TypeSubstitution.substitute(effect, substitutions)).toList());
        if (!TypeSubstitution.containsTypeParameter(expectedType)) {
            ensureAssignable(context, expectedType, specialized, where);
        }
        return specialized;
    }

    static FunctionDescriptor resolveFunctionReference(final ResolutionContext context, final Expr.Variable reference,
                                                         final TypeDescriptor expectedType) {
        final var functionName = reference.resolvedFunctionName() == null
                ? MemberInteropResolver.resolveFunctionName(context, reference.name.lexeme(), reference.name)
                : reference.resolvedFunctionName();
        if (functionName == null || context.symbols.containsSymbol(reference.name)
                && reference.resolvedFunctionName() == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND, reference.name,
                    "Function reference does not resolve to a top-level function."));
        }
        final var functionToken = context.functionSymbolTokens.get(functionName);
        final var declarations = context.functionOverloads.getOrDefault(functionName, List.of()).stream()
                .filter(candidate -> !(candidate instanceof Stmt.ExtensionMethod)).toList();
        final var selectedDeclaration = declarations.size() > 1
                ? selectFunctionReferenceOverload(context, reference, expectedType, declarations)
                : declarations.isEmpty() ? null : declarations.getFirst();
        final var sourceType = selectedDeclaration == null
                || selectedDeclaration.typeDescriptor().returnType() instanceof InferDescriptor
                ? (FunctionDescriptor) context.symbols.getFunction(functionToken).type()
                : selectedDeclaration.typeDescriptor();
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        final var explicitTypes = reference.explicitFunctionTypeArguments;
        if (!explicitTypes.isEmpty()) {
            if (explicitTypes.size() != sourceType.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        reference.name,
                        "Expected " + sourceType.typeParameters().size() + " type arguments, found "
                                + explicitTypes.size() + "."));
            }
            for (int i = 0; i < explicitTypes.size(); i++) {
                TypeResolver.validateType(context, explicitTypes.get(i), reference.name);
                substitutions.put(sourceType.typeParameters().get(i), explicitTypes.get(i));
            }
        } else if (sourceType.isGeneric()) {
            final var expectedFunction = functionType(context, expectedType);
            if (expectedFunction == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        reference.name,
                        "A generic function value needs explicit type arguments or an expected function type."));
            }
            if (TypeSubstitution.containsTypeParameter(expectedFunction)) {
                unifyFunctionReferenceType(context, sourceType, expectedFunction, substitutions, reference.name);
            } else {
                TypeUnifier.unify(sourceType, expectedFunction, substitutions, reference.name);
            }
        }

        for (final var parameter : sourceType.typeParameters()) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        reference.name,
                        "Cannot infer type parameter '" + parameter.name()
                                + "' for function value; provide an explicit type argument."));
            }
            for (final var bound : parameter.bounds()) {
                final var requiredBound = TypeSubstitution.substitute(bound, substitutions);
                ensureAssignable(context, requiredBound, substitutions.get(parameter), reference.name);
            }
        }

        final var specializedType = sourceType.isGeneric()
                ? TypeDescriptor.functionWithEffectsOf(sourceType.name(),
                        TypeSubstitution.substitute(sourceType.returnType(), substitutions),
                        sourceType.parameters().stream()
                                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                                .toList(), List.of(), sourceType.raisedEffects().stream()
                                .map(effect -> TypeSubstitution.substitute(effect, substitutions)).toList())
                : sourceType;
        final var expectedFunction = expectedType == null ? null : functionType(context, expectedType);
        if (expectedFunction != null && !TypeSubstitution.containsTypeParameter(expectedFunction)) {
            ensureAssignable(context, expectedFunction, specializedType, reference.name);
        }
        reference.setResolvedFunctionName(functionName);
        reference.setResolvedFunctionDeclaration(selectedDeclaration);
        reference.setSourceFunctionType(sourceType);
        reference.setSpecializedFunctionType(specializedType);
        reference.setType(specializedType);
        return specializedType;
    }

    private static Stmt.FunctionDeclaration selectFunctionReferenceOverload(
            final ResolutionContext context, final Expr.Variable reference, final TypeDescriptor expectedType,
            final List<Stmt.FunctionDeclaration> declarations) {
        final var expected = expectedType == null ? null : functionType(context, expectedType);
        final var matches = new ArrayList<Stmt.FunctionDeclaration>();
        for (final var declaration : declarations) {
            final var source = declaration.typeDescriptor();
            final var explicit = reference.explicitFunctionTypeArguments;
            if (!explicit.isEmpty() && explicit.size() != source.typeParameters().size()) continue;
            if (expected == null && (source.isGeneric() && explicit.isEmpty()
                    || declarations.size() > 1 && explicit.isEmpty())) continue;
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            try {
                for (int i = 0; i < explicit.size(); i++) {
                    TypeResolver.validateType(context, explicit.get(i), reference.name);
                    substitutions.put(source.typeParameters().get(i), explicit.get(i));
                }
                if (source.isGeneric() && explicit.isEmpty()) {
                    TypeUnifier.unify(source, expected, substitutions, reference.name);
                }
                if (source.typeParameters().stream().anyMatch(parameter -> !substitutions.containsKey(parameter))) {
                    continue;
                }
                final var specialized = source.isGeneric()
                        ? TypeDescriptor.functionWithEffectsOf(source.name(),
                                TypeSubstitution.substitute(source.returnType(), substitutions),
                                source.parameters().stream()
                                        .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                                        .toList(), List.of(), source.raisedEffects().stream()
                                        .map(effect -> TypeSubstitution.substitute(effect, substitutions)).toList())
                        : source;
                if (expected == null || specialized.equals(expected)
                        || context.typeCompatibility.canAssign(expected, specialized)) matches.add(declaration);
            } catch (final ResolutionError _) {
                continue;
            }
        }
        if (matches.size() == 1) return matches.getFirst();
        if (matches.size() > 1) {
            final var nonGeneric = matches.stream().filter(candidate ->
                    !candidate.typeDescriptor().isGeneric()).toList();
            if (nonGeneric.size() == 1) return nonGeneric.getFirst();
        }
        Zeron.resolutionError(new ResolutionError(
                matches.isEmpty() ? DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE
                        : DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                reference.name,
                matches.isEmpty() ? "No function overload matches the expected function type."
                        : "Function reference is ambiguous; provide a more specific expected function type."));
        return matches.getFirst();
    }

    private static void unifyFunctionReferenceType(final ResolutionContext context, final TypeDescriptor pattern,
                                           TypeDescriptor actual,
                                           final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                                           final Token where) {
        if (pattern.equals(actual)) return;
        if (pattern instanceof TypeParameterDescriptor parameter) {
            final var previous = substitutions.putIfAbsent(parameter, actual);
            if (previous == null || previous.equals(actual)) return;
            if (actual instanceof TypeParameterDescriptor) return;
            if (previous instanceof TypeParameterDescriptor) {
                substitutions.put(parameter, actual);
                return;
            }
            TypeUnifier.unify(pattern, actual, substitutions, where);
            return;
        }
        if (actual instanceof TypeParameterDescriptor) return;
        if (pattern instanceof NullableDescriptor nullablePattern) {
            if (actual instanceof NullDescriptor) return;
            final var actualBase = actual instanceof NullableDescriptor nullableActual
                    ? nullableActual.baseType()
                    : actual;
            unifyFunctionReferenceType(context, nullablePattern.baseType(), actualBase, substitutions, where);
            return;
        }
        if (pattern instanceof ReferenceDescriptor referencePattern) {
            final var actualBase = actual instanceof ReferenceDescriptor referenceActual
                    ? referenceActual.baseType()
                    : actual;
            unifyFunctionReferenceType(context, referencePattern.baseType(), actualBase, substitutions, where);
            return;
        }
        if (pattern instanceof ArrayDescriptor arrayPattern
                && actual instanceof ReferenceDescriptor reference
                && reference.baseType() instanceof ArrayDescriptor) {
            actual = reference.baseType();
        }
        if (pattern instanceof ArrayDescriptor arrayPattern && actual instanceof ArrayDescriptor arrayActual) {
            unifyFunctionReferenceType(context, arrayPattern.elementType(), arrayActual.elementType(), substitutions, where);
            return;
        }
        if (pattern instanceof GenericDescriptor genericPattern
                && actual instanceof GenericDescriptor genericActual
                && genericPattern.baseType().equals(genericActual.baseType())
                && genericPattern.typeParameters().size() == genericActual.typeParameters().size()) {
            for (int i = 0; i < genericPattern.typeParameters().size(); i++) {
                unifyFunctionReferenceType(context, genericPattern.typeParameters().get(i),
                        genericActual.typeParameters().get(i), substitutions, where);
            }
            return;
        }
        if (pattern instanceof FunctionDescriptor functionPattern
                && actual instanceof FunctionDescriptor functionActual
                && functionPattern.arity() == functionActual.arity()) {
            for (int i = 0; i < functionPattern.arity(); i++) {
                unifyFunctionReferenceType(context, functionPattern.parameters().get(i),
                        functionActual.parameters().get(i), substitutions, where);
            }
            unifyFunctionReferenceType(context, functionPattern.returnType(),
                    functionActual.returnType(), substitutions, where);
            return;
        }
        TypeUnifier.unify(pattern, actual, substitutions, where);
    }

    static FunctionDescriptor functionType(final ResolutionContext context, final TypeDescriptor type) {
        return switch (type) {
            case FunctionDescriptor function -> function;
            case NullableDescriptor nullable -> functionType(context, nullable.baseType());
            case ReferenceDescriptor reference -> functionType(context, reference.baseType());
            default -> null;
        };
    }

    static FunctionDescriptor inferLambdaType(final ResolutionContext context, final Expr.Lambda lambda) {
        final var enclosingLoopDepth = context.frame.loopDepth;
        final var enclosingFlow = context.frame.flowState;
        final var enclosingEffects = RaisedEffectFlow.beginCallable(context);
        context.frame.loopDepth = 0;
        context.frame.flowState = new FlowState();
        beginScope(context);
        try {
            for (final var param : lambda.params) {
                declare(context, SYNTHETIC_VAR, param, TypeDescriptor.ofInfer(), BindingMutability.IMMUTABLE);
                define(context, param);
            }

            TypeDescriptor returnType = TypeDescriptor.ofUnit();
            for (final var stmt : lambda.body) {
                if (stmt instanceof Stmt.Return(Expr value, Token _)) {
                    returnType = value == null ? TypeDescriptor.ofUnit() : ExpressionFlowResolver.resolveExpression(context, value);
                } else {
                    context.statementResolver.resolve(stmt);
                }
            }

            final var parameterTypes = new ArrayList<TypeDescriptor>();
            for (final var param : lambda.params) {
                final var binding = context.symbols.getSymbol(param);
                parameterTypes.add(binding.type() instanceof InferDescriptor
                        ? TypeDescriptor.ofInfer()
                        : binding.type());
            }

            return TypeDescriptor.functionWithEffectsOf("", returnType, parameterTypes,
                    List.of(), List.copyOf(context.frame.raisedEffects));
        } finally {
            endScope(context);
            context.frame.loopDepth = enclosingLoopDepth;
            context.frame.flowState = enclosingFlow;
            RaisedEffectFlow.endCallable(context, enclosingEffects);
        }
    }

        static FunctionDescriptor generalizeLambda(final ResolutionContext context, final Expr.Lambda lambda,
                            final FunctionDescriptor inferredType) {
            if (inferredType.parameters().stream().anyMatch(type -> containsFunctionScheme(context, type))
                || containsFunctionScheme(context, inferredType.returnType())) return null;
        final var unresolvedParameters = new HashSet<String>();
        for (int i = 0; i < lambda.params.size(); i++) {
            if (containsInfer(context, inferredType.parameters().get(i))) {
            unresolvedParameters.add(lambda.params.get(i).lexeme());
            }
        }
        if (unresolvedParameters.isEmpty() && !containsInfer(context, inferredType.returnType())) return null;

        if (lambda.body.size() != 1 || !(lambda.body.getFirst() instanceof Stmt.Return returnStmt)) {
            return null;
        }
        final var returnedParameter = returnStmt.value() instanceof Expr.Variable variable
            ? lambda.params.stream().filter(parameter -> parameter.lexeme().equals(variable.name.lexeme()))
                .findFirst().orElse(null)
            : null;
        if (containsInfer(context, inferredType.returnType()) && returnedParameter == null) return null;
        if (returnStmt.value() != null && returnedParameter == null
            && referencesAnyVariable(context, returnStmt.value(), unresolvedParameters)) return null;

        final var scopeId = LAMBDA_TYPE_SCOPES.incrementAndGet();
        final var typeParameters = new ArrayList<TypeParameterDescriptor>();
        final var parameterTypes = new ArrayList<TypeDescriptor>(lambda.params.size());
        final var quantifiedParameters = new HashMap<String, TypeParameterDescriptor>();
        for (int i = 0; i < lambda.params.size(); i++) {
            final var parameterType = inferredType.parameters().get(i);
            if (!containsInfer(context, parameterType)) {
            parameterTypes.add(parameterType);
            continue;
            }
            if (!(parameterType instanceof InferDescriptor)) return null;
            final var typeParameter = new TypeParameterDescriptor(scopeId,
                "LambdaT" + i);
            typeParameters.add(typeParameter);
            quantifiedParameters.put(lambda.params.get(i).lexeme(), typeParameter);
            parameterTypes.add(typeParameter);
        }

        final TypeDescriptor returnType;
        if (containsInfer(context, inferredType.returnType())) {
            final var typeParameter = quantifiedParameters.get(returnedParameter.lexeme());
            if (typeParameter == null) return null;
            returnType = typeParameter;
        } else {
            returnType = inferredType.returnType();
        }

        if (typeParameters.isEmpty()) return null;
        final var generalized = TypeDescriptor.genericFunctionOf("", returnType,
            parameterTypes, typeParameters);
        lambda.setType(generalized);
        return generalized;
        }

    private static boolean containsFunctionScheme(
            final ResolutionContext context, final TypeDescriptor descriptor) {
        return switch (descriptor) {
            case FunctionDescriptor function -> function.isGeneric()
                    || function.parameters().stream().anyMatch(type -> containsFunctionScheme(context, type))
                    || containsFunctionScheme(context, function.returnType());
            case NullableDescriptor nullable -> containsFunctionScheme(context, nullable.baseType());
            case ReferenceDescriptor reference -> containsFunctionScheme(context, reference.baseType());
            case ArrayDescriptor array -> containsFunctionScheme(context, array.elementType());
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(type -> containsFunctionScheme(context, type));
            default -> false;
        };
    }

        static boolean containsInfer(
                final ResolutionContext context, final TypeDescriptor descriptor) {
        return switch (descriptor) {
            case InferDescriptor _ -> true;
            case NullableDescriptor nullable -> containsInfer(context, nullable.baseType());
            case ReferenceDescriptor reference -> containsInfer(context, reference.baseType());
            case ArrayDescriptor array -> containsInfer(context, array.elementType());
            case GenericDescriptor generic -> generic.typeParameters().stream().anyMatch(type -> containsInfer(context, type));
            case FunctionDescriptor function -> containsInfer(context, function.returnType())
                || function.parameters().stream().anyMatch(type -> containsInfer(context, type));
            default -> false;
        };
        }

        private static boolean referencesAnyVariable(final ResolutionContext context, final Expr expression, final Set<String> names) {
        return switch (expression) {
            case Expr.Variable variable -> names.contains(variable.name.lexeme());
            case Expr.Assignment assignment -> names.contains(assignment.name.lexeme())
                || referencesAnyVariable(context, assignment.value, names);
            case Expr.CoalesceAssignment assignment -> names.contains(assignment.name.lexeme())
                || referencesAnyVariable(context, assignment.value, names);
            case Expr.Binary binary -> referencesAnyVariable(context, binary.left, names)
                || referencesAnyVariable(context, binary.right, names);
            case Expr.Call call -> names.contains(call.callee.lexeme())
                || call.arguments.stream().anyMatch(argument -> referencesAnyVariable(context, argument, names));
            case Expr.MemberCall call -> referencesAnyVariable(context, call.receiver, names)
                || call.arguments.stream().anyMatch(argument -> referencesAnyVariable(context, argument, names));
            case Expr.Property property -> referencesAnyVariable(context, property.receiver, names);
            case Expr.PropertyAssignment assignment -> referencesAnyVariable(context, assignment.property.receiver, names)
                || referencesAnyVariable(context, assignment.value, names);
            case Expr.PropertyCompoundAssignment assignment ->
                referencesAnyVariable(context, assignment.property.receiver, names)
                    || referencesAnyVariable(context, assignment.value, names);
            case Expr.ArrayLiteral literal ->
                literal.elements.stream().anyMatch(element -> referencesAnyVariable(context, element, names));
            case Expr.Index index -> referencesAnyVariable(context, index.array, names)
                || referencesAnyVariable(context, index.index, names);
            case Expr.IndexAssignment assignment -> referencesAnyVariable(context, assignment.array, names)
                || referencesAnyVariable(context, assignment.index, names)
                || referencesAnyVariable(context, assignment.value, names);
            case Expr.Grouping grouping -> referencesAnyVariable(context, grouping.expression, names);
            case Expr.If iff -> referencesAnyVariable(context, iff.condition, names)
                || referencesAnyVariable(context, iff.thenExpr, names)
                || referencesAnyVariable(context, iff.elseExpr, names);
            case Expr.Pipeline pipeline -> referencesAnyVariable(context, pipeline.source, names)
                || pipeline.stages.stream().anyMatch(stage -> {
                    final var stageNames = new HashSet<>(names);
                    if (stage instanceof Expr.PipelineStage.Map m && m.binding() != null) stageNames.remove(m.binding().lexeme());
                    if (stage instanceof Expr.PipelineStage.FlatMap f && f.binding() != null) stageNames.remove(f.binding().lexeme());
                    if (stage instanceof Expr.PipelineStage.Filter f && f.binding() != null) stageNames.remove(f.binding().lexeme());
                    return referencesAnyVariable(context, stage.expression(), stageNames);
                });
            case Expr.Match match -> referencesAnyVariable(context, match.scrutinee, names)
                || match.arms.stream().anyMatch(arm -> {
                    final var armNames = new HashSet<>(names);
                    if (arm.alias() != null) armNames.remove(arm.alias().lexeme());
                    if (arm.binding() != null) armNames.remove(arm.binding().lexeme());
                    return arm.guard() != null && referencesAnyVariable(context, arm.guard(), armNames)
                            || referencesAnyVariable(context, arm.expression(), armNames);
                });
            case Expr.Raise raise -> referencesAnyVariable(context, raise.effect, names);
            case Expr.Handle handle -> referencesAnyVariable(context, handle.expression, names)
                || handle.arms.stream().anyMatch(arm -> {
                    final var armNames = new HashSet<>(names);
                    if (arm.alias() != null) armNames.remove(arm.alias().lexeme());
                    if (arm.binding() != null) armNames.remove(arm.binding().lexeme());
                    return referencesAnyVariable(context, arm.expression(), armNames);
                });
            case Expr.Logical logical -> referencesAnyVariable(context, logical.left, names)
                || referencesAnyVariable(context, logical.right, names);
            case Expr.Coalesce coalesce -> referencesAnyVariable(context, coalesce.left, names)
                || referencesAnyVariable(context, coalesce.right, names);
            case Expr.Unary unary -> referencesAnyVariable(context, unary.right, names);
            case Expr.TypeTest test -> referencesAnyVariable(context, test.value, names);
            case Expr.Cast cast -> referencesAnyVariable(context, cast.value, names);
            case Expr.Lambda nested -> nested.body.stream().anyMatch(statement ->
                statement instanceof Stmt.Return returned && returned.value() != null
                    && referencesAnyVariable(context, returned.value(), names));
            case Expr.Literal _ -> false;
        };
        }

    static FunctionDescriptor resolveLambda(final ResolutionContext context, final Expr.Lambda lambda,
                                             final FunctionDescriptor expectedType) {
        final var lambdaArity = lambda.params.size();
        if (lambdaArity != expectedType.arity()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE,
                    lambda.arrow,
                    "Expected " + expectedType.arity() + " lambda parameters, found " + lambdaArity));
            return expectedType;
        }

        final var enclosingLoopDepth = context.frame.loopDepth;
        final var enclosingFlow = context.frame.flowState;
        final var enclosingEffects = RaisedEffectFlow.beginCallable(context);
        context.frame.loopDepth = 0;
        context.frame.flowState = new FlowState();
        beginScope(context);
        try {
            for (int i = 0; i < lambda.params.size(); i++) {
                final var parameter = lambda.params.get(i);
                final var parameterType = expectedType.parameters().get(i);
                declare(context, SYNTHETIC_VAR, parameter, parameterType, BindingMutability.IMMUTABLE);
                define(context, parameter);
            }

            TypeDescriptor returnType = TypeDescriptor.ofUnit();
            for (final var stmt : lambda.body) {
                if (stmt instanceof Stmt.Return(Expr value, Token _)) {
                    returnType = value == null ? TypeDescriptor.ofUnit() : ExpressionFlowResolver.resolveExpression(context, value);
                } else {
                    context.statementResolver.resolve(stmt);
                }
            }

            final var unresolvedReturn = TypeSubstitution.containsTypeParameter(expectedType.returnType());
            if (!(expectedType.returnType() instanceof InferDescriptor) && !unresolvedReturn) {
                ensureAssignable(context, expectedType.returnType(), returnType, lambda.arrow);
            }

            final var resolvedType = expectedType.returnType() instanceof InferDescriptor || unresolvedReturn
                    ? expectedType.toReturnType(returnType)
                    : expectedType;
            RaisedEffectFlow.verifyCallable(context, expectedType.raisedEffects(), lambda.arrow);
            lambda.setType(resolvedType);
            return resolvedType;
        } finally {
            endScope(context);
            context.frame.loopDepth = enclosingLoopDepth;
            context.frame.flowState = enclosingFlow;
            RaisedEffectFlow.endCallable(context, enclosingEffects);
        }
    }

    static boolean isFunctionReferenceCandidate(final ResolutionContext context, final Expr expression) {
        return expression instanceof Expr.Variable variable
                && variable.explicitFunctionTypeArguments.isEmpty()
                && variable.resolvedFunctionName() == null
                && !context.symbols.containsSymbol(variable.name)
                && MemberInteropResolver.resolveFunctionName(context, variable.name.lexeme(), variable.name) != null;
    }

    static TypeDescriptor refineInferredType(final ResolutionContext context, final Expr expr,
                                             final TypeDescriptor type,
                                             final TypeDescriptor otherType) {
        if (!(expr instanceof Expr.Variable variable)
                || !(type instanceof InferDescriptor)
                || otherType instanceof InferDescriptor
                || !context.symbols.containsSymbol(variable.name)
                || !(context.symbols.getSymbol(variable.name).type() instanceof InferDescriptor)) {
            return type;
        }

        context.symbols.setResolvedType(variable.name, otherType);
        return otherType;
    }

    static void resolveFunction(final ResolutionContext context, final Stmt.Function function) {
        if (Objects.equals(context.currentSourcePath, context.entrySourcePath)
                && function.name().lexeme().equals("main")
                && function.parameters().isEmpty()
                && (function.typeDescriptor().returnType() instanceof UnitDescriptor
                    || function.typeDescriptor().returnType() instanceof InferDescriptor)
                && !function.typeDescriptor().raisedEffects().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    function.name(), "The entry-point main function cannot declare raised effects."));
        }
        final var enclosingLoopDepth = context.frame.loopDepth;
        final var enclosingFlow = context.frame.flowState;
        final var enclosingEffects = RaisedEffectFlow.beginCallable(context);
        context.frame.loopDepth = 0;
        context.frame.flowState = new FlowState();
        beginScope(context);
        context.frame.expectedReturnTypes.push(function.typeDescriptor().returnType());
        try {
            resolveParametersAndDefaults(context, function, function.parameters(), function.typeDescriptor(),
                    function.defaultValues(), function.minimumArity());
            resolveStmts(context, function.body());
            final var resolvedType = ensureReturns(context,
                    function.name(),
                    function.typeDescriptor().returnType(),
                    function.body());
            if (function.typeDescriptor().returnType() instanceof InferDescriptor) {
                context.symbols.setResolvedReturnType(
                        MemberInteropResolver.functionSymbolToken(context, function.name()), resolvedType);
            }
            RaisedEffectFlow.verifyCallable(context, function.typeDescriptor().raisedEffects(), function.name());

                Zeron.debug(" resolved function " + function.name().lexeme()
                    + " -> " + context.symbols.getFunction(
                            MemberInteropResolver.functionSymbolToken(context, function.name())).type());
        } finally {
            context.frame.expectedReturnTypes.pop();
            endScope(context);
            context.frame.loopDepth = enclosingLoopDepth;
            context.frame.flowState = enclosingFlow;
            RaisedEffectFlow.endCallable(context, enclosingEffects);
        }
    }

    static void resolveExtensionMethod(final ResolutionContext context,
                                       final Stmt.ExtensionMethod extension) {
        final var enclosingLoopDepth = context.frame.loopDepth;
        final var enclosingFlow = context.frame.flowState;
        final var enclosingEffects = RaisedEffectFlow.beginCallable(context);
        context.frame.loopDepth = 0;
        context.frame.flowState = new FlowState();
        beginScope(context);
        context.frame.expectedReturnTypes.push(extension.typeDescriptor().returnType());
        try {
            resolveParametersAndDefaults(context, extension, extension.parameters(),
                    extension.typeDescriptor(), extension.defaultValues(), extension.minimumArity());
            resolveStmts(context, extension.body());
            ensureReturns(context, extension.name(), extension.typeDescriptor().returnType(), extension.body());
            RaisedEffectFlow.verifyCallable(context, extension.typeDescriptor().raisedEffects(), extension.name());
        } finally {
            context.frame.expectedReturnTypes.pop();
            endScope(context);
            context.frame.loopDepth = enclosingLoopDepth;
            context.frame.flowState = enclosingFlow;
            RaisedEffectFlow.endCallable(context, enclosingEffects);
        }
    }

    static void resolveExternalDefaults(final ResolutionContext context,
                                        final Stmt.ExternalFunction function) {
        if (function.defaultValues().isEmpty()) return;
        beginScope(context);
        try {
            resolveParametersAndDefaults(context, function, function.parameters(), function.typeDescriptor(),
                    function.defaultValues(), function.minimumArity());
        } finally {
            endScope(context);
        }
    }

    private static void resolveParametersAndDefaults(final ResolutionContext context,
                                                    final Stmt declaration,
                                                    final List<Token> parameterNames,
                                                    final FunctionDescriptor descriptor,
                                                    final List<Expr> defaultValues,
                                                    final int minimumArity) {
        for (int i = 0; i < parameterNames.size(); i++) {
            final var parameterType = descriptor.parameters().get(i);
            if (i >= minimumArity && i - minimumArity < defaultValues.size()) {
                final var defaultValue = defaultValues.get(i - minimumArity);
                final var resolvedDefault = MemberInteropResolver.resolveArgument(context, defaultValue,
                        parameterType);
                ensureAssignable(context, parameterType, resolvedDefault, parameterNames.get(i));
            }
            declare(context, SYNTHETIC_VAR, parameterNames.get(i), parameterType,
                    BindingMutability.IMMUTABLE);
            define(context, parameterNames.get(i));
        }
    }

}
