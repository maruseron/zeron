package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.DiagnosticHelp;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.*;

final class CallResolver {
    private final ResolutionContext context;

    CallResolver(final ResolutionContext context) {
        this.context = context;
    }

    TypeDescriptor resolve(final Expr.Call call) {
        return resolve(call, null);
    }

    TypeDescriptor resolveNamespaceCall(final Expr.MemberCall memberCall, final String functionName) {
        final var call = new Expr.Call(memberCall.name, memberCall.paren, memberCall.arguments,
                memberCall.explicitTypeArguments, TypeDescriptor.ofInfer());
        resolve(call, functionName);
        memberCall.setNamespaceCall(call);
        memberCall.setType(call.getType());
        return call.getType();
    }

    private TypeDescriptor resolve(final Expr.Call call, final String forcedFunctionName) {
        FunctionDescriptor descriptor;
        final var callableSymbol = forcedFunctionName == null
                ? context.symbols.containsSymbol(call.callee)
                    ? call.callee : MemberInteropResolver.resolveTopLevelValueSymbol(context, call.callee)
                : null;
        if (forcedFunctionName != null) {
            final var functionToken = context.functionSymbolTokens.get(forcedFunctionName);
            if (functionToken == null) throw new IllegalStateException("Resolved namespace function disappeared.");
            call.setResolvedFunctionName(forcedFunctionName);
            final var declaration = context.functions.get(forcedFunctionName);
            if (declaration == null || declaration instanceof Stmt.ExtensionMethod) {
                throw new IllegalStateException("Resolved namespace function disappeared.");
            }
            call.setResolvedFunctionDeclaration(declaration);
            context.recordReference(call.callee, declaration.name(), declaration.typeDescriptor());
            descriptor = declaration.typeDescriptor();
        } else if (callableSymbol != null && context.symbols.containsSymbol(callableSymbol)) {
            call.setResolvedSymbolToken(callableSymbol);
            final var symbol = context.symbols.getSymbol(callableSymbol).type();
            context.recordReference(call.callee, context.symbols.getSymbol(callableSymbol).name(), symbol);
            if (symbol instanceof FunctionDescriptor function) {
                descriptor = function;
            } else if (symbol instanceof ReferenceDescriptor reference
                    && reference.baseType() instanceof FunctionDescriptor function) {
                descriptor = function;
            } else {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        call.callee, "Callee is not a function."));
                return TypeDescriptor.ofInfer();
            }
        } else {
            final var functionName = MemberInteropResolver.resolveFunctionName(context, call.callee.lexeme(), call.callee);
            final var functionToken = functionName == null ? null : context.functionSymbolTokens.get(functionName);
            if (functionToken == null) {
                if (context.frame.currentMethodOwner == null) {
                    Zeron.resolutionError(ResolutionError.withHelp(DiagnosticCatalog.NAME_NOT_FOUND,
                            call.callee,
                            "Unknown function '" + call.callee.lexeme() + "' in the current scope.",
                            new DiagnosticHelp(
                                    "Declare this function or import it from the package that provides it.")));
                }
                final var receiver = new Expr.Variable(
                        new Token(TokenType.THIS, "this", null, call.callee.span()),
                        TypeDescriptor.ofInfer());
                final var implicitCall = new Expr.MemberCall(receiver, call.callee, call.paren,
                        call.arguments, call.explicitTypeArguments, TypeDescriptor.ofInfer());
                call.setImplicitMemberCall(implicitCall);
                final var resultType = MemberInteropResolver.resolveMemberCall(context, implicitCall);
                recordCallSiteFunctionType(call.callee, implicitCall.resolvedDescriptor(),
                        implicitCall.resolvedExtensionMethod() != null);
                call.setType(resultType);
                return resultType;
            }
            call.setResolvedFunctionName(functionName);
            final var declaration = context.functions.get(functionName);
            if (declaration == null || declaration instanceof Stmt.ExtensionMethod) {
                throw new IllegalStateException("Resolved function disappeared.");
            }
            call.setResolvedFunctionDeclaration(declaration);
            context.recordReference(call.callee, declaration.name(), declaration.typeDescriptor());
            descriptor = declaration.typeDescriptor();
        }
        if (descriptor.isGeneric()) {
            final var resultType = resolveGenericCall(call, descriptor);
            attachIntrinsicOperation(call);
            return resultType;
        }

        if (!call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    call.callee,
                    "This function does not declare type parameters."));
        }
        final var parameters = descriptor.parameters();
        final var declaration = declaration(call);
        final var variadic = declaration != null && declaration.variadic();
        final var fixedArity = Stmt.fixedArity(declaration == null ? List.of() : declaration.parameters(),
                variadic);
        if (variadic) {
            final var arrayType = parameters.getLast();
            call.setVariadic(((ArrayDescriptor) arrayType).elementType(), fixedArity);
        }
        Zeron.debug("resolving call              " + call.callee.lexeme() + parameters
                + " -> " + descriptor.returnType());
        final var minimumArity = minimumArity(call, descriptor);
        if (call.arguments.size() < minimumArity || !variadic && call.arguments.size() > descriptor.arity()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.callee,
                    variadic
                            ? "Expected at least " + minimumArity + " arguments, found " + call.arguments.size()
                            : "Expected between " + minimumArity + " and " + descriptor.arity()
                                    + " arguments, found " + call.arguments.size()));
        }

        if (descriptor.returnType() instanceof InferDescriptor) {
            descriptor = resolveCallWithTypes(call.callee, call.arguments);
        } else {
            for (var i = 0; i < call.arguments.size(); i++) {
                final var argument = call.arguments.get(i);
                final var expected = parameterType(parameters, i, fixedArity, variadic);
                final var resolved = MemberInteropResolver.resolveArgument(context, argument, expected);
                Resolver.ensureAssignable(context, expected, resolved, call.callee);
            }
        }

        call.setGenericFunctionType(descriptor);
        call.setType(descriptor.returnType());
        recordCallSiteFunctionType(call.callee, descriptor, false);
        attachIntrinsicOperation(call);
        return descriptor.returnType();
    }

    private void attachIntrinsicOperation(final Expr.Call call) {
        if ("zeron.collections.allocateArray".equals(call.resolvedFunctionName())
                || "zeron.internal.allocateArray".equals(call.resolvedFunctionName())) {
            final var arrayElement = IntrinsicResolver.arrayElementType(context, call.getType(), call.callee);
            call.setIntrinsicOperation(IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.ARRAY_ALLOC,
                    List.of(arrayElement),
                    call.arguments.stream().map(Expr::getType).toList(), call.callee));
        } else if ("zeron.internal.allocateByteArray".equals(call.resolvedFunctionName())
                || "zeron.io.allocateByteArray".equals(call.resolvedFunctionName())) {
            call.setIntrinsicOperation(IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.BYTE_ARRAY_ALLOC,
                    List.of(), call.arguments.stream().map(Expr::getType).toList(), call.callee));
        } else if ("zeron.internal.intToByte".equals(call.resolvedFunctionName())) {
            call.setIntrinsicOperation(IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.INT_TO_BYTE,
                    List.of(), call.arguments.stream().map(Expr::getType).toList(), call.callee));
        } else if ("zeron.internal.byteToInt".equals(call.resolvedFunctionName())) {
            call.setIntrinsicOperation(IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.BYTE_TO_INT,
                    List.of(), call.arguments.stream().map(Expr::getType).toList(), call.callee));
        } else if ("zeron.collections.clearArraySlot".equals(call.resolvedFunctionName())) {
            final var arrayElement = IntrinsicResolver.arrayElementType(context,
                    call.arguments.getFirst().getType(), call.callee);
            call.setIntrinsicOperation(IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.ARRAY_CLEAR_SLOT,
                    List.of(arrayElement),
                    call.arguments.stream().map(Expr::getType).toList(), call.callee));
        } else if ("zeron.internal.unwrapSome".equals(call.resolvedFunctionName())) {
            var optionType = call.arguments.getFirst().getType();
            if (optionType instanceof ReferenceDescriptor reference) optionType = reference.baseType();
            final var optionValue = ((GenericDescriptor) optionType).typeParameters().getFirst();
            call.setIntrinsicOperation(IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.OPTION_UNWRAP_SOME,
                    List.of(optionValue),
                    call.arguments.stream().map(Expr::getType).toList(), call.callee));
        }
    }

    private TypeDescriptor resolveGenericCall(final Expr.Call call,
                                              final FunctionDescriptor genericType) {
        call.setGenericFunctionType(genericType);
        final var typeParameters = genericType.typeParameters();
        final var hasExplicitTypeArguments = !call.explicitTypeArguments.isEmpty();
        if (hasExplicitTypeArguments
                && call.explicitTypeArguments.size() != typeParameters.size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.callee,
                    "Expected " + typeParameters.size() + " type arguments, found "
                            + call.explicitTypeArguments.size() + "."));
        }
        final var minimumArity = minimumArity(call, genericType);
        final var declaration = declaration(call);
        final var variadic = declaration != null && declaration.variadic();
        final var fixedArity = variadic
                ? Stmt.fixedArity(declaration.parameters(), true)
                : genericType.arity();
        if (variadic) {
            call.setVariadic(((ArrayDescriptor) genericType.parameters().getLast()).elementType(), fixedArity);
        }
        if (call.arguments.size() < minimumArity || !variadic && call.arguments.size() > genericType.arity()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.callee,
                    variadic
                            ? "Expected at least " + minimumArity + " arguments, found " + call.arguments.size()
                            : "Expected between " + minimumArity + " and " + genericType.arity()
                                    + " arguments, found " + call.arguments.size()));
        }

        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        final var localLambdaBinding = localLambdaBinding(call);
        for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
            final var explicitType = call.explicitTypeArguments.get(i);
            TypeResolver.validateType(context, explicitType, call.callee);
            substitutions.put(typeParameters.get(i), explicitType);
        }

        final var resolvedArguments = new TypeDescriptor[call.arguments.size()];
        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (argument instanceof Expr.Lambda || isFunctionReferenceCandidate(argument)) continue;
            resolvedArguments[i] = ExpressionFlowResolver.resolveExpression(context, argument);
            if (!hasExplicitTypeArguments) {
                TypeUnifier.unify(parameterType(genericType.parameters(), i, fixedArity, variadic),
                        resolvedArguments[i], substitutions, call.callee);
            }
        }

        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (!(argument instanceof Expr.Lambda) && !isFunctionReferenceCandidate(argument)) continue;
            final var expected = TypeSubstitution.substitute(
                    parameterType(genericType.parameters(), i, fixedArity, variadic), substitutions);
            if (functionType(expected) == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.callee,
                        "A function value argument requires a function parameter type."));
            }
            resolvedArguments[i] = MemberInteropResolver.resolveArgument(context, argument, expected);
            if (!hasExplicitTypeArguments) {
                TypeUnifier.unify(parameterType(genericType.parameters(), i, fixedArity, variadic),
                        resolvedArguments[i], substitutions, call.callee);
            }
        }

        for (final var parameter : typeParameters) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.GENERIC_INFERENCE_FAILURE,
                        call.callee,
                        "Cannot infer type parameter '" + parameter.name()
                                + "'; provide an explicit type argument."));
            }
            substitutions.put(parameter, TypeResolver.validateTypeArgument(
                    context, parameter, substitutions.get(parameter), call.callee));
            for (final var bound : parameter.bounds()) {
                final var requiredBound = TypeSubstitution.substitute(bound, substitutions);
                final var actual = substitutions.get(parameter);
                if (localLambdaBinding != null) {
                    LambdaResolver.ensureLambdaSchemeBound(
                            context, requiredBound, actual, call.callee, localLambdaBinding);
                } else if (!TypeClassEvidence.hasEvidence(context, requiredBound, actual, call.callee)) {
                    Resolver.ensureAssignable(context, requiredBound, actual, call.callee);
                }
            }
        }

        call.setEvidenceArguments(TypeClassEvidence.selectArguments(
                context, typeParameters, substitutions, call.callee));

        final var instantiatedParameters = genericType.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                .toList();
        for (int i = 0; i < resolvedArguments.length; i++) {
            Resolver.ensureAssignable(context,
                    parameterType(instantiatedParameters, i, fixedArity, variadic),
                    resolvedArguments[i], call.callee);
        }
        final var instantiatedReturn = TypeSubstitution.substitute(genericType.returnType(), substitutions);
        final var instantiatedEffects = genericType.raisedEffects().stream()
                .map(effect -> TypeSubstitution.substitute(effect, substitutions))
                .toList();
        final var specializedFunctionType = TypeDescriptor.functionWithEffectsOf(
                genericType.name(), instantiatedReturn, instantiatedParameters, List.of(),
                instantiatedEffects);
        call.setSpecializedFunctionType(specializedFunctionType);
        context.recordCallSiteFunctionType(call.callee, specializedFunctionType);
        call.setType(instantiatedReturn);
        return instantiatedReturn;
    }

    private Stmt.Var localLambdaBinding(final Expr.Call call) {
        if (call.resolvedFunctionName() != null
                || !context.symbols.containsSymbol(call.resolvedSymbolToken())) return null;
        final var declaration = context.symbols.getSymbol(call.resolvedSymbolToken()).declaration();
        return declaration instanceof Stmt.Var variable
                && variable.mutability() == BindingMutability.IMMUTABLE
                && variable.initializer() instanceof Expr.Lambda
                ? variable : null;
    }

    private void recordCallSiteFunctionType(final Token callSite,
                                            final FunctionDescriptor descriptor,
                                            final boolean extensionCall) {
        if (descriptor == null) return;
        final var parameters = extensionCall && !descriptor.parameters().isEmpty()
                ? descriptor.parameters().subList(1, descriptor.parameters().size())
                : descriptor.parameters();
        context.recordCallSiteFunctionType(callSite, TypeDescriptor.functionWithEffectsOf(
                descriptor.name(), descriptor.returnType(), parameters, descriptor.typeParameters(),
                descriptor.raisedEffects()));
    }

    private int minimumArity(final Expr.Call call, final FunctionDescriptor descriptor) {
        final var declaration = declaration(call);
        return declaration == null ? descriptor.arity() : declaration.minimumArity();
    }

    private Stmt.FunctionDeclaration declaration(final Expr.Call call) {
        return call.resolvedFunctionDeclaration();
    }

    private static TypeDescriptor parameterType(final List<TypeDescriptor> parameters,
                                                final int argumentIndex,
                                                final int fixedArity,
                                                final boolean variadic) {
        if (!variadic || argumentIndex < fixedArity) return parameters.get(argumentIndex);
        return ((ArrayDescriptor) parameters.getLast()).elementType();
    }

    private FunctionDescriptor resolveCallWithTypes(final Token callee, final List<Expr> arguments) {
        final var candidateType = TypeDescriptor.functionOf(
                "",
                TypeDescriptor.ofInfer(),
                arguments.stream().map(expression -> ExpressionFlowResolver.resolveExpression(context, expression))
                        .toArray(TypeDescriptor[]::new));
        final var declaration = (Stmt.Var) context.symbols.getSymbol(callee).declaration();
        final var lambda = (Expr.Lambda) declaration.initializer();
        final var candidate = new Expr.Lambda(lambda.arrow, lambda.params, lambda.body, candidateType);
        final var resolvedType = LambdaResolver.resolveLambda(context, candidate, candidateType);
        lambda.setType(resolvedType);
        context.symbols.setResolvedType(declaration.name(), resolvedType);
        return resolvedType;
    }

    private boolean isFunctionReferenceCandidate(final Expr expression) {
        if (!(expression instanceof Expr.Variable variable)
                || !variable.explicitFunctionTypeArguments.isEmpty()
                || variable.resolvedFunctionName() != null) return false;
        if (context.symbols.containsSymbol(variable.name)) {
            final var bindingType = context.symbols.getSymbol(variable.name).type();
            if (bindingType instanceof FunctionDescriptor function) return function.isGeneric();
            return bindingType instanceof ReferenceDescriptor reference
                    && reference.baseType() instanceof FunctionDescriptor referencedFunction
                    && referencedFunction.isGeneric();
        }
        return MemberInteropResolver.resolveFunctionName(context, variable.name.lexeme(), variable.name) != null;
    }

    private FunctionDescriptor functionType(final TypeDescriptor type) {
        if (type instanceof ReferenceDescriptor reference) return functionType(reference.baseType());
        return type instanceof FunctionDescriptor function ? function : null;
    }
}
