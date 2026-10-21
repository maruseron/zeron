package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.SymbolTable;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LambdaCaptureValidator {
    private final SymbolTable symbols;
    private final String captureDescription;
    private final Map<String, Token> anonymousCaptures = new LinkedHashMap<>();

    LambdaCaptureValidator(final SymbolTable symbols) {
        this(symbols, "a lambda");
    }

    LambdaCaptureValidator(final SymbolTable symbols, final String captureDescription) {
        this.symbols = symbols;
        this.captureDescription = captureDescription;
    }

    void validate(final Expr.Lambda lambda) {
        validate(lambda, Set.of());
    }

    List<Token> validateAnonymousObject(final Stmt.ClassDecl declaration) {
        for (final var method : declaration.methods()) {
            final var locals = new HashSet<String>();
            method.parameters().forEach(parameter -> locals.add(parameter.lexeme()));
            walkCaptureUsage(method.body(), locals);
        }
        for (final var property : declaration.properties()) {
            if (property.getterBody() != null) {
                walkCaptureUsage(property.getterBody(), new HashSet<>());
            }
            if (property.setterBody() != null) {
                walkCaptureUsage(property.setterBody(), new HashSet<>(Set.of(
                        property.setterParameter().lexeme())));
            }
        }
        return List.copyOf(anonymousCaptures.values());
    }

    void validateAnonymousInitializer(final Expr initializer) {
        walkCaptureUsage(initializer, Set.of());
    }

    private void validate(final Expr.Lambda lambda, final Set<String> inheritedNames) {
        final var lambdaLocals = new HashSet<>(inheritedNames);
        for (final var param : lambda.params) {
            lambdaLocals.add(param.lexeme());
        }
        walkCaptureUsage(lambda.body, lambdaLocals);
    }

    private void walkCaptureUsage(final List<Stmt> statements, final Set<String> localNames) {
        for (final var statement : statements) {
            walkCaptureUsage(statement, localNames);
        }
    }

    private void walkCaptureUsage(final Stmt statement, final Set<String> localNames) {
        switch (statement) {
            case Stmt.Block(List<Stmt> block) -> walkCaptureUsage(block, new HashSet<>(localNames));
            case Stmt.Using using -> {
                final var nestedNames = new HashSet<>(localNames);
                for (final var resource : using.resources()) {
                    final var declaration = resource.declaration();
                    if (declaration.initializer() != null) {
                        walkCaptureUsage(declaration.initializer(), nestedNames);
                    }
                    nestedNames.add(declaration.name().lexeme());
                }
                walkCaptureUsage(using.body(), nestedNames);
            }
            case Stmt.If(Token _, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                walkCaptureUsage(condition, localNames);
                if (thenBranch != null) walkCaptureUsage(thenBranch, namesWithConditionBindings(condition, localNames));
                if (elseBranch != null) walkCaptureUsage(elseBranch, localNames);
            }
            case Stmt.Return(Expr value, Token _) -> {
                if (value != null) walkCaptureUsage(value, localNames);
            }
            case Stmt.Expression(Expr expression) -> walkCaptureUsage(expression, localNames);
            case Stmt.Var(Token name, TypeDescriptor _, Expr initializer, BindingMutability _, _) -> {
                if (initializer != null) walkCaptureUsage(initializer, localNames);
                localNames.add(name.lexeme());
            }
            case Stmt.While(Token _, Expr condition, Stmt body) -> {
                walkCaptureUsage(condition, localNames);
                walkCaptureUsage(body, namesWithConditionBindings(condition, localNames));
            }
            case Stmt.For(Token bind, Token _, Expr iterable, Stmt body) -> {
                walkCaptureUsage(iterable, localNames);
                final var loopNames = new HashSet<>(localNames);
                loopNames.add(bind.lexeme());
                walkCaptureUsage(body, loopNames);
            }
            case Stmt.Function(Token _, List<Token> parameters, FunctionDescriptor _, List<Stmt> body,
                               boolean _, List<Expr> _, int _, boolean _) -> {
                final var nestedNames = new HashSet<>(localNames);
                for (final var parameter : parameters) {
                    nestedNames.add(parameter.lexeme());
                }
                walkCaptureUsage(body, nestedNames);
            }
            default -> {}
        }
    }

    private void walkCaptureUsage(final Expr expr, final Set<String> localNames) {
        if (expr == null) return;
        switch (expr) {
            case Expr.MemberCall call -> {
                walkCaptureUsage(call.receiver, localNames);
                for (final var argument : call.arguments) walkCaptureUsage(argument, localNames);
            }
            case Expr.Property property -> walkCaptureUsage(property.receiver, localNames);
            case Expr.PropertyAssignment assignment -> {
                walkCaptureUsage(assignment.property.receiver, localNames);
                walkCaptureUsage(assignment.value, localNames);
            }
            case Expr.PropertyCompoundAssignment assignment -> {
                walkCaptureUsage(assignment.property.receiver, localNames);
                walkCaptureUsage(assignment.value, localNames);
            }
            case Expr.ArrayLiteral literal -> {
                for (final var element : literal.elements) walkCaptureUsage(element, localNames);
            }
            case Expr.Index index -> {
                walkCaptureUsage(index.array, localNames);
                walkCaptureUsage(index.index, localNames);
            }
            case Expr.IndexAssignment assignment -> {
                walkCaptureUsage(assignment.array, localNames);
                walkCaptureUsage(assignment.index, localNames);
                walkCaptureUsage(assignment.value, localNames);
            }
            case Expr.Assignment assignment -> walkCaptureUsage(assignment.value, localNames);
            case Expr.CoalesceAssignment assignment -> walkCaptureUsage(assignment.value, localNames);
            case Expr.Binary binary -> {
                walkCaptureUsage(binary.left, localNames);
                walkCaptureUsage(binary.right, localNames);
            }
            case Expr.Call call -> {
                if (call.implicitMemberCall() != null) {
                    walkCaptureUsage(call.implicitMemberCall().receiver, localNames);
                }
                for (final var argument : call.arguments) {
                    walkCaptureUsage(argument, localNames);
                }
            }
            case Expr.Grouping grouping -> walkCaptureUsage(grouping.expression, localNames);
            case Expr.If iff -> {
                walkCaptureUsage(iff.condition, localNames);
                walkCaptureUsage(iff.thenExpr, namesWithConditionBindings(iff.condition, localNames));
                walkCaptureUsage(iff.elseExpr, localNames);
            }
            case Expr.Match match -> {
                walkCaptureUsage(match.scrutinee, localNames);
                for (final var arm : match.arms) {
                    final var armNames = new HashSet<>(localNames);
                    if (arm.alias() != null) armNames.add(arm.alias().lexeme());
                    if (arm.binding() != null) armNames.add(arm.binding().lexeme());
                    if (arm.guard() != null) walkCaptureUsage(arm.guard(), armNames);
                    walkCaptureUsage(arm.expression(), armNames);
                }
            }

            case Expr.SumCaseTest test -> walkCaptureUsage(test.value, localNames);
            case Expr.Raise raise -> walkCaptureUsage(raise.effect, localNames);
            case Expr.Handle handle -> {
                walkCaptureUsage(handle.expression, localNames);
                if (handle.handler != null) walkCaptureUsage(handle.handler, localNames);
                for (final var arm : handle.arms) {
                    final var armNames = new HashSet<>(localNames);
                    arm.pattern().bindingNames().forEach(name -> armNames.add(name.lexeme()));
                    if (arm.guard() != null) walkCaptureUsage(arm.guard(), armNames);
                    walkCaptureUsage(arm.expression(), armNames);
                }
            }
            case Expr.HandlerLiteral handler -> validate(handler.implementation, localNames);
            case Expr.HandlerDispatch dispatch -> {
                for (final var arm : dispatch.arms) {
                    final var armNames = new HashSet<>(localNames);
                    arm.pattern().bindingNames().forEach(name -> armNames.add(name.lexeme()));
                    if (arm.guard() != null) walkCaptureUsage(arm.guard(), armNames);
                    walkCaptureUsage(arm.expression(), armNames);
                }
            }
            case Expr.Lambda lambda -> validate(lambda, localNames);
            case Expr.SuffixedLiteral literal -> walkCaptureUsage(literal.factoryCall(), localNames);
            case Expr.Literal _ -> {}
            case Expr.Logical logical -> {
                walkCaptureUsage(logical.left, localNames);
                walkCaptureUsage(logical.right, localNames);
            }
            case Expr.Coalesce coalesce -> {
                walkCaptureUsage(coalesce.left, localNames);
                walkCaptureUsage(coalesce.right, localNames);
            }
            case Expr.Unary unary -> walkCaptureUsage(unary.right, localNames);
            case Expr.Variable variable -> {
                if (variable.implicitFieldReceiver() != null) {
                    walkCaptureUsage(variable.implicitFieldReceiver(), localNames);
                    return;
                }
                if (localNames.contains(variable.name.lexeme())) return;
                if (symbols.containsSymbol(variable.name)
                        && symbols.getSymbol(variable.name).mutability().isReassignable()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            variable.name,
                            "Cannot capture mutable binding '" + variable.name.lexeme()
                                    + "' in " + captureDescription + "."));
                }
                if (symbols.containsSymbol(variable.name)
                        && symbols.getSymbol(variable.name).lvt() != SymbolTable.GLOBAL
                        && variable.name.type() != com.maruseron.zeron.scan.TokenType.THIS) {
                    anonymousCaptures.putIfAbsent(variable.name.lexeme(),
                            symbols.getSymbol(variable.name).name());
                }
            }
            default -> {}
        }
    }

    private static Set<String> namesWithConditionBindings(final Expr condition,
                                                           final Set<String> localNames) {
        final var names = new HashSet<>(localNames);
        final var candidate = condition instanceof Expr.Grouping grouping ? grouping.expression : condition;
        if (candidate instanceof Expr.SumCaseTest test) {
            for (final var argument : test.pattern.arguments()) {
                if (argument.binding() != null) names.add(argument.binding().lexeme());
            }
        }
        return names;
    }
}