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
            final var selected = selectFunctionOverload(call, forcedFunctionName);
            call.setResolvedFunctionDeclaration(selected);
            descriptor = selected.typeDescriptor();
        } else if (callableSymbol != null && context.symbols.containsSymbol(callableSymbol)) {
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
            final var selected = selectFunctionOverload(call, functionName);
            call.setResolvedFunctionDeclaration(selected);
            descriptor = selected.typeDescriptor();
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

    private Stmt.FunctionDeclaration selectFunctionOverload(final Expr.Call call, final String functionName) {
            final var packageName = context.declarationPackages.getOrDefault(functionName, "");
            final var candidates = context.functionOverloads.getOrDefault(functionName, List.of()).stream()
                    .filter(candidate -> !(candidate instanceof Stmt.ExtensionMethod))
                    .filter(candidate -> packageName.equals(context.packageName) || candidate.isPublic())
                    .toList();
            if (candidates.isEmpty()) throw new IllegalStateException("Resolved function disappeared.");
            if (candidates.size() == 1) return candidates.getFirst();
            final var actualTypes = new ArrayList<TypeDescriptor>(call.arguments.size());
            for (final var argument : call.arguments) {
                actualTypes.add(argument instanceof Expr.Lambda || isFunctionReferenceCandidate(argument)
                        ? null : ExpressionFlowResolver.resolveExpression(context, argument));
            }
            final var applicable = new ArrayList<OverloadCandidate>();
            for (final var declaration : candidates) {
                final var signature = declaration.typeDescriptor();
                final var variadic = declaration.variadic();
                final var fixedArity = Stmt.fixedArity(declaration.parameters(), variadic);
                if ((!call.explicitTypeArguments.isEmpty()
                        && call.explicitTypeArguments.size() != signature.typeParameters().size())
                        || call.arguments.size() < declaration.minimumArity()
                        || !variadic && call.arguments.size() > signature.arity()) continue;
                final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
                try {
                    for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
                        TypeResolver.validateType(context, call.explicitTypeArguments.get(i), call.callee);
                        substitutions.put(signature.typeParameters().get(i), call.explicitTypeArguments.get(i));
                    }
                    for (int i = 0; i < actualTypes.size(); i++) {
                        final var actual = actualTypes.get(i);
                        if (actual == null) continue;
                        final var pattern = parameterType(signature.parameters(), i, fixedArity, variadic);
                        if (TypeSubstitution.containsTypeParameter(pattern)) {
                            TypeUnifier.unify(pattern, actual, substitutions, call.callee);
                        }
                    }
                } catch (final ResolutionError _) {
                    continue;
                }
                if (signature.typeParameters().stream().anyMatch(parameter -> !substitutions.containsKey(parameter))) {
                    continue;
                }
                final var parameters = signature.parameters().stream()
                        .map(parameter -> TypeSubstitution.substitute(parameter, substitutions)).toList();
                var matches = true;
                for (int i = 0; i < actualTypes.size(); i++) {
                    final var actual = actualTypes.get(i);
                    final var expected = parameterType(parameters, i, fixedArity, variadic);
                    if (actual != null && !context.typeCompatibility.canAssign(expected, actual)) {
                        matches = false;
                        break;
                    }
                    if (actual == null && functionType(expected) == null) {
                        matches = false;
                        break;
                    }
                }
                if (matches) applicable.add(new OverloadCandidate(declaration, parameters,
                        declaration.minimumArity(), variadic, signature.isGeneric()));
            }
            if (applicable.isEmpty()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        call.callee, "No overload of '" + functionName + "' matches these arguments."));
            }
            var best = applicable.stream().filter(candidate -> applicable.stream().noneMatch(other ->
                    other != candidate && moreSpecific(other, candidate))).toList();
            if (best.size() > 1) {
                final var minOmitted = best.stream().mapToInt(candidate ->
                        Math.max(0, Stmt.fixedArity(candidate.declaration().parameters(), candidate.variadic())
                                - call.arguments.size())).min().orElse(0);
                best = best.stream().filter(candidate ->
                        Math.max(0, Stmt.fixedArity(candidate.declaration().parameters(), candidate.variadic())
                                - call.arguments.size()) == minOmitted).toList();
                if (best.stream().anyMatch(OverloadCandidate::variadic)
                        && best.stream().anyMatch(candidate -> !candidate.variadic())) {
                    best = best.stream().filter(candidate -> !candidate.variadic()).toList();
                }
                if (best.stream().anyMatch(candidate -> !candidate.generic())
                        && best.stream().anyMatch(OverloadCandidate::generic)) {
                    best = best.stream().filter(candidate -> !candidate.generic()).toList();
                }
            }
            if (best.size() != 1) {
                final var signatures = best.stream().map(candidate ->
                        TypeFormatter.format(candidate.declaration().typeDescriptor())).sorted().toList();
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        call.callee, "Ambiguous call to '" + functionName + "': " + String.join(", ", signatures) + "."));
            }
            return best.getFirst().declaration();
        }

    private boolean moreSpecific(final OverloadCandidate left, final OverloadCandidate right) {
            if (left.parameters().size() != right.parameters().size()) return false;
            var strict = false;
            for (int i = 0; i < left.parameters().size(); i++) {
                final var leftType = left.parameters().get(i);
                final var rightType = right.parameters().get(i);
                if (!context.typeCompatibility.canAssign(rightType, leftType)) return false;
                strict |= !context.typeCompatibility.canAssign(leftType, rightType);
            }
            return strict;
        }

    private record OverloadCandidate(Stmt.FunctionDeclaration declaration, List<TypeDescriptor> parameters,
                                     int minimumArity, boolean variadic, boolean generic) {}

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
            TypeUnifier.unify(parameterType(genericType.parameters(), i, fixedArity, variadic),
                    resolvedArguments[i], substitutions, call.callee);
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
            TypeUnifier.unify(parameterType(genericType.parameters(), i, fixedArity, variadic),
                    resolvedArguments[i], substitutions, call.callee);
        }

        for (final var parameter : typeParameters) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.callee,
                        "Cannot infer type parameter '" + parameter.name()
                                + "'; provide an explicit type argument."));
            }
            for (final var bound : parameter.bounds()) {
                final var requiredBound = TypeSubstitution.substitute(bound, substitutions);
                Resolver.ensureAssignable(context, requiredBound, substitutions.get(parameter), call.callee);
            }
        }

        final var instantiatedParameters = genericType.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                .toList();
        for (int i = 0; i < resolvedArguments.length; i++) {
            Resolver.ensureAssignable(context,
                    parameterType(instantiatedParameters, i, fixedArity, variadic),
                    resolvedArguments[i], call.callee);
        }
        final var instantiatedReturn = TypeSubstitution.substitute(genericType.returnType(), substitutions);
        call.setType(instantiatedReturn);
        return instantiatedReturn;
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
