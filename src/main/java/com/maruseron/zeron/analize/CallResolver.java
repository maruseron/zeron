package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
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
        FunctionDescriptor descriptor;
        final var callableSymbol = context.symbols.containsSymbol(call.callee)
                ? call.callee : MemberInteropResolver.resolveTopLevelValueSymbol(context, call.callee);
        if (callableSymbol != null && context.symbols.containsSymbol(callableSymbol)) {
            call.setResolvedSymbolToken(callableSymbol);
            final var symbol = context.symbols.getSymbol(callableSymbol).type();
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
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND, call.callee,
                            "Unknown function '" + call.callee.lexeme() + "'."));
                }
                final var receiver = new Expr.Variable(
                        new Token(TokenType.THIS, "this", null, call.callee.span()),
                        TypeDescriptor.ofInfer());
                final var implicitCall = new Expr.MemberCall(receiver, call.callee, call.paren,
                        call.arguments, call.explicitTypeArguments, TypeDescriptor.ofInfer());
                call.setImplicitMemberCall(implicitCall);
                final var resultType = MemberInteropResolver.resolveMemberCall(context, implicitCall);
                call.setType(resultType);
                return resultType;
            }
            call.setResolvedFunctionName(functionName);
            descriptor = (FunctionDescriptor) context.symbols.getFunction(functionToken).type();
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
        Zeron.debug("resolving call              " + call.callee.lexeme() + parameters
                + " -> " + descriptor.returnType());
        if (descriptor.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.callee,
                    "Expected " + descriptor.arity() + " arguments, found " + call.arguments.size()));
        }

        if (descriptor.returnType() instanceof InferDescriptor) {
            descriptor = resolveCallWithTypes(call.callee, call.arguments);
        } else {
            for (var i = 0; i < call.arguments.size(); i++) {
                final var argument = call.arguments.get(i);
                final var expected = parameters.get(i);
                final var resolved = MemberInteropResolver.resolveArgument(context, argument, expected);
                Resolver.ensureAssignable(context, expected, resolved, call.callee);
            }
        }

        call.setType(descriptor.returnType());
        return descriptor.returnType();
    }

    private void attachIntrinsicOperation(final Expr.Call call) {
        if ("zeron.collections.allocateArray".equals(call.resolvedFunctionName())) {
            final var arrayElement = IntrinsicResolver.arrayElementType(context, call.getType(), call.callee);
            call.setIntrinsicOperation(IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.ARRAY_ALLOC,
                    List.of(arrayElement),
                    call.arguments.stream().map(Expr::getType).toList(), call.callee));
        } else if ("zeron.collections.clearArraySlot".equals(call.resolvedFunctionName())) {
            final var arrayElement = IntrinsicResolver.arrayElementType(context,
                    call.arguments.getFirst().getType(), call.callee);
            call.setIntrinsicOperation(IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.ARRAY_CLEAR_SLOT,
                    List.of(arrayElement),
                    call.arguments.stream().map(Expr::getType).toList(), call.callee));
        } else if ("zeron.collections.unwrapSome".equals(call.resolvedFunctionName())) {
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
        if (!call.explicitTypeArguments.isEmpty()
                && call.explicitTypeArguments.size() != typeParameters.size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.callee,
                    "Expected " + typeParameters.size() + " type arguments, found "
                            + call.explicitTypeArguments.size() + "."));
        }
        if (genericType.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.callee,
                    "Expected " + genericType.arity() + " arguments, found " + call.arguments.size()));
        }

        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
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
            TypeUnifier.unify(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.callee);
        }

        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (!(argument instanceof Expr.Lambda) && !isFunctionReferenceCandidate(argument)) continue;
            final var expected = TypeSubstitution.substitute(genericType.parameters().get(i), substitutions);
            if (functionType(expected) == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.callee,
                        "A function value argument requires a function parameter type."));
            }
            resolvedArguments[i] = MemberInteropResolver.resolveArgument(context, argument, expected);
            TypeUnifier.unify(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.callee);
        }

        for (final var parameter : typeParameters) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.callee,
                        "Cannot infer type parameter '" + parameter.name()
                                + "'; provide an explicit type argument."));
            }
            if (parameter.bound() != null) {
                final var requiredBound = TypeSubstitution.substitute(parameter.bound(), substitutions);
                Resolver.ensureAssignable(context, requiredBound, substitutions.get(parameter), call.callee);
            }
        }

        final var instantiatedParameters = genericType.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                .toList();
        for (int i = 0; i < resolvedArguments.length; i++) {
            Resolver.ensureAssignable(context, instantiatedParameters.get(i), resolvedArguments[i], call.callee);
        }
        final var instantiatedReturn = TypeSubstitution.substitute(genericType.returnType(), substitutions);
        call.setType(instantiatedReturn);
        return instantiatedReturn;
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
        return expression instanceof Expr.Variable variable
                && variable.explicitFunctionTypeArguments.isEmpty()
                && variable.resolvedFunctionName() == null
                && !context.symbols.containsSymbol(variable.name)
                && MemberInteropResolver.resolveFunctionName(context, variable.name.lexeme(), variable.name) != null;
    }

    private FunctionDescriptor functionType(final TypeDescriptor type) {
        if (type instanceof ReferenceDescriptor reference) return functionType(reference.baseType());
        return type instanceof FunctionDescriptor function ? function : null;
    }
}
