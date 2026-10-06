package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.DiagnosticHelp;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.*;

final class StatementResolver {
    record ConditionFlows(FlowState whenTrue, FlowState whenFalse) {}

    private final ResolutionContext context;

    StatementResolver(final ResolutionContext context) {
        this.context = context;
    }

    void resolveStatements(final List<Stmt> statements) {
        for (final var statement : statements) resolve(statement);
    }

    void resolve(final Stmt stmt) {
        switch (stmt) {
            case Stmt.Namespace _ ->
                    throw new IllegalStateException("Namespace members must be flattened before resolution.");
            case Stmt.ClassDecl declaration -> DeclarationResolver.resolveClass(context, declaration);
            case Stmt.ContractDecl declaration -> DeclarationResolver.resolveContract(context, declaration);
            case Stmt.ExternalClass _ -> {}
            case Stmt.Block(List<Stmt> statements) -> {
                context.symbols.beginScope();
                resolveStatements(statements);
                context.symbols.endScope();
            }
            case Stmt.Break(Token keyword) -> {
                if (context.frame.loopDepth == 0) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_CONTROL_FLOW_OR_INITIALIZATION_FLOW,
                            keyword,
                            "Can only break inside of a loop."));
                }
                if (context.frame.flowState.isReachable()) {
                    context.frame.loopFlows.peek().breakStates.add(context.frame.flowState.copy());
                }
                context.frame.flowState.markUnreachable();
            }
            case Stmt.Continue(Token keyword) -> {
                if (context.frame.loopDepth == 0) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_CONTROL_FLOW_OR_INITIALIZATION_FLOW,
                            keyword,
                            "Can only continue inside of a loop."));
                }
                if (context.frame.flowState.isReachable()) {
                    context.frame.loopFlows.peek().continueStates.add(context.frame.flowState.copy());
                }
                context.frame.flowState.markUnreachable();
            }
            case Stmt.Expression(Expr expression) -> ExpressionFlowResolver.resolveExpression(context, expression);
            case Stmt.Function fn -> {
                TypeResolver.validateFunctionTypes(context, fn.typeDescriptor(), fn.name());
                TypeResolver.validateTypeParameterBounds(context, fn.typeDescriptor(), fn.name());
                if (!context.functionNamesByDeclaration.containsKey(fn.name())) {
                    context.declarationRegistrar.registerFunction(context.packageName, fn);
                }
                LambdaResolver.resolveFunction(context, fn);
            }
            case Stmt.ExternalFunction externalFunction -> {
                TypeResolver.validateFunctionTypes(context, externalFunction.typeDescriptor(), externalFunction.name());
                if (!context.functionNamesByDeclaration.containsKey(externalFunction.name())) {
                    context.declarationRegistrar.registerFunction(context.packageName, externalFunction);
                }
            }
            case Stmt.For(Token iterationBind, Token _, Expr iterable, Stmt body) -> {
                context.symbols.beginScope();
                final var iterableType = resolve(iterable);
                final var elementType = Resolver.ensureIterable(context, iterableType, iterationBind);
                context.iterationElementTypes.put(iterationBind, elementType);
                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, iterationBind,
                        elementType, BindingMutability.IMMUTABLE);
                context.symbols.define(iterationBind);
                final var incoming = context.frame.flowState.copy();
                var headerState = incoming.copy();
                LoopFlow stableLoopFlow;
                while (true) {
                    final var loopFlow = new LoopFlow();
                    context.frame.loopFlows.push(loopFlow);
                    context.frame.loopDepth++;
                    try {
                        context.frame.flowState = headerState.copy();
                        resolve(body);
                    } finally {
                        context.frame.loopDepth--;
                        context.frame.loopFlows.pop();
                    }
                    final var backEdges = new ArrayList<>(loopFlow.continueStates);
                    if (context.frame.flowState.isReachable()) backEdges.add(context.frame.flowState.copy());
                    var nextHeader = incoming.copy();
                    for (final var backEdge : backEdges) {
                        nextHeader = FlowState.join(nextHeader, backEdge);
                    }
                    if (nextHeader.sameAs(headerState)) {
                        stableLoopFlow = loopFlow;
                        break;
                    }
                    headerState = nextHeader;
                }
                final var exits = incoming.copy();
                exits.remove(iterationBind);
                var exitState = exits;
                for (final var breakState : stableLoopFlow.breakStates) {
                    final var reachableBreak = breakState.copy();
                    reachableBreak.remove(iterationBind);
                    exitState = FlowState.join(exitState, reachableBreak);
                }
                context.frame.flowState = exitState;
                context.symbols.endScope();
            }
            case Stmt.If(Token keyword, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                final var incoming = context.frame.flowState.copy();
                final var conditionFlows = ExpressionFlowResolver.resolveCondition(context, condition, incoming, keyword);
                context.frame.flowState = conditionFlows.whenTrue().copy();
                resolve(thenBranch);
                final var thenFlow = context.frame.flowState.copy();

                final FlowState elseFlow;
                if (elseBranch == null) {
                    elseFlow = conditionFlows.whenFalse();
                } else {
                    context.frame.flowState = conditionFlows.whenFalse().copy();
                    resolve(elseBranch);
                    elseFlow = context.frame.flowState.copy();
                }
                context.frame.flowState = FlowState.join(thenFlow, elseFlow);
            }
            case Stmt.Return(Expr value, Token location) -> {
                final var expectedReturnType = context.frame.expectedReturnTypes.peek();
                final var returnType = value == null
                        ? TypeDescriptor.ofUnit()
                        : MemberInteropResolver.resolveArgument(context, value, expectedReturnType);
                if (!context.frame.expectedReturnTypes.isEmpty()) {
                    Resolver.ensureAssignable(context, context.frame.expectedReturnTypes.peek(),
                            returnType, location);
                }
                context.frame.flowState.markUnreachable();
            }
            case Stmt.Var var -> resolveVariable(var);
            case Stmt.While(Token keyword, Expr condition, Stmt body) -> resolveWhile(keyword, condition, body);
        }
    }

    private void resolveVariable(final Stmt.Var var) {
        Zeron.debug("resolving variable       " + var.name().lexeme() + " " + var.type());
        if (!(var.type() instanceof InferDescriptor)) TypeResolver.validateType(context, var.type(), var.name());

        final var globalToken = context.topLevelTokensByDeclaration.get(var);
        final var symbolName = globalToken == null ? var.name() : globalToken;
        if (globalToken == null || !context.symbols.containsSymbol(symbolName)) {
            context.symbols.declareSymbol(var, symbolName, var.type(), var.mutability());
        }
        TypeDescriptor resolvedType = var.type();

        if (!(resolvedType instanceof InferDescriptor)
                && var.initializer() == null
                && !resolvedType.isNullable()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NULLABLE_VALUE_REQUIRES_HANDLING, var.name(),
                    "A variable with no initializer must be of a nullable type."));
        }

        if (var.initializer() != null) {
            resolvedType = var.type() instanceof InferDescriptor
                    ? resolve(var.initializer())
                    : MemberInteropResolver.resolveArgument(context, var.initializer(), var.type());
            if (var.type() instanceof InferDescriptor
                    && var.mutability() == BindingMutability.IMMUTABLE
                    && var.initializer() instanceof Expr.Lambda lambda
                    && resolvedType instanceof FunctionDescriptor functionType) {
                final var generalized = LambdaResolver.generalizeLambda(context, lambda, functionType);
                if (generalized != null) resolvedType = generalized;
            }
            if (var.mutability().isReassignable()
                    && resolvedType instanceof FunctionDescriptor functionType
                    && functionType.isGeneric()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, var.name(),
                        "Polymorphic function values require an immutable binding."));
            }
            resolvedType = Resolver.ensureAssignable(context, var.type(), resolvedType, var.name());
            if (var.type() instanceof InferDescriptor && resolvedType instanceof NullDescriptor) {
                Zeron.resolutionError(ResolutionError.withHelp(
                        DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE,
                        var.name(),
                        "Cannot infer a type from a null value.",
                        new DiagnosticHelp("Add an explicit nullable type annotation.")));
            }
            if (var.type() instanceof InferDescriptor) context.symbols.setResolvedType(symbolName, resolvedType);
        }

        if (resolvedType instanceof InferDescriptor) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE,
                    var.name(),
                    "Cannot infer type from declaration."));
        }

        if (!(var.type() instanceof InferDescriptor)) {
            Zeron.debug(" resolved variable " + var.name().lexeme() + " "
                    + var.type() + " from explicit type");
        } else {
            Zeron.debug(" resolved variable " + var.name().lexeme() + " "
                    + resolvedType + " from initializer");
        }
        context.symbols.define(symbolName);
    }

    private void resolveWhile(final Token keyword, final Expr condition, final Stmt body) {
        final var incoming = context.frame.flowState.copy();
        var headerState = incoming.copy();
        ConditionFlows conditionFlows;
        LoopFlow stableLoopFlow;
        while (true) {
            final var loopFlow = new LoopFlow();
            context.frame.loopFlows.push(loopFlow);
            context.frame.loopDepth++;
            try {
                conditionFlows = ExpressionFlowResolver.resolveCondition(context, condition, headerState, keyword);
                context.frame.flowState = conditionFlows.whenTrue().copy();
                resolve(body);
            } finally {
                context.frame.loopDepth--;
                context.frame.loopFlows.pop();
            }
            final var backEdges = new ArrayList<>(loopFlow.continueStates);
            if (context.frame.flowState.isReachable()) backEdges.add(context.frame.flowState.copy());
            var nextHeader = incoming.copy();
            for (final var backEdge : backEdges) {
                nextHeader = FlowState.join(nextHeader, backEdge);
            }
            if (nextHeader.sameAs(headerState)) {
                stableLoopFlow = loopFlow;
                break;
            }
            headerState = nextHeader;
        }
        var exitState = conditionFlows.whenFalse().copy();
        for (final var breakState : stableLoopFlow.breakStates) {
            exitState = FlowState.join(exitState, breakState.copy());
        }
        context.frame.flowState = exitState;
    }

    private TypeDescriptor resolve(final Expr expression) {
        return ExpressionFlowResolver.resolveExpression(context, expression);
    }
}
