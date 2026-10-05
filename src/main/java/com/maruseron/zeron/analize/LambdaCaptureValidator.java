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
import java.util.List;
import java.util.Set;

final class LambdaCaptureValidator {
    private final SymbolTable symbols;

    LambdaCaptureValidator(final SymbolTable symbols) {
        this.symbols = symbols;
    }

    void validate(final Expr.Lambda lambda) {
        validate(lambda, Set.of());
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
            case Stmt.Block(List<Stmt> block) -> walkCaptureUsage(block, localNames);
            case Stmt.If(Token _, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                walkCaptureUsage(condition, localNames);
                if (thenBranch != null) walkCaptureUsage(thenBranch, localNames);
                if (elseBranch != null) walkCaptureUsage(elseBranch, localNames);
            }
            case Stmt.Return(Expr value, Token _) -> {
                if (value != null) walkCaptureUsage(value, localNames);
            }
            case Stmt.Expression(Expr expression) -> walkCaptureUsage(expression, localNames);
            case Stmt.Var(Token _, TypeDescriptor _, Expr initializer, BindingMutability _, _) -> {
                if (initializer != null) walkCaptureUsage(initializer, localNames);
            }
            case Stmt.While(Token _, Expr condition, Stmt body) -> {
                walkCaptureUsage(condition, localNames);
                walkCaptureUsage(body, localNames);
            }
            case Stmt.For(Token _, Token _, Expr iterable, Stmt body) -> {
                walkCaptureUsage(iterable, localNames);
                walkCaptureUsage(body, localNames);
            }
            case Stmt.Function(Token _, List<Token> parameters, FunctionDescriptor _, List<Stmt> body, boolean _) -> {
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
                walkCaptureUsage(iff.thenExpr, localNames);
                walkCaptureUsage(iff.elseExpr, localNames);
            }
            case Expr.Match match -> {
                walkCaptureUsage(match.scrutinee, localNames);
                for (final var arm : match.arms) {
                    final var armNames = new HashSet<>(localNames);
                    if (arm.alias() != null) armNames.add(arm.alias().lexeme());
                    walkCaptureUsage(arm.expression(), armNames);
                }
            }
            case Expr.Lambda lambda -> validate(lambda, localNames);
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
                            "Cannot capture mutable binding '" + variable.name.lexeme() + "' in a lambda."));
                }
            }
            default -> {}
        }
    }
}