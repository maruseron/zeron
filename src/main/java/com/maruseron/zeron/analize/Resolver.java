package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.*;

public final class Resolver {
    // this table stores every name related to a type to avoid name collisions
    public final SymbolTable symbols = new SymbolTable();
    public final Set<String> types   = new HashSet<>();

    public void resolve(final List<Stmt> statements) {

        for (final var statement : statements) {
            resolve(statement);
        }

        System.out.println("resolution finished successfully with symbol table: \n" + symbols);
    }

    private void resolve(final Stmt stmt) {
        switch (stmt) {
            case Stmt.Block(List<Stmt> statements) -> {
                beginScope();
                resolveStmts(statements);
                endScope();
            }
            case Stmt.Break(Token keyword) -> {

            }
            case Stmt.Expression(Expr expression) -> {
                resolve(expression);
            }
            case Stmt.Function fn -> {
                declare(fn.name(), fn.typeDescriptor(), true);
                define(fn.name());

                resolveFunction(fn);
            }
            case Stmt.For(Token iterationBind, Token _, Expr iterable, Stmt body) -> {
                beginScope();
                final var iterableType = resolve(iterable);
                ensureIterable(iterableType);
                // iterable is @ 1 Iterable TYPE. we extract TYPE by doing
                // iterableType.typeParameters() and getting the first (and only)
                final var typeParameter = iterableType.typeParameters().getFirst();
                declare(iterationBind, typeParameter, true);
                define(iterationBind);
                resolve(body);
                endScope();
            }
            case Stmt.If(Token _, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                ensureBoolean(resolve(condition));
                resolve(thenBranch);
                if (elseBranch != null) resolve(elseBranch);
            }
            case Stmt.Print(Expr expression) -> {
                resolve(expression);
            }
            case Stmt.Return(Expr value) -> {
                if (value != null) {
                    resolve(value);
                }
            }
            case Stmt.Var(Token name, TypeDescriptor expectedType, Expr initializer,
                          boolean isFinal) -> {
                System.out.println("resolving variable " + name.lexeme() + " " + expectedType);

                declare(name, expectedType, isFinal);
                TypeDescriptor resolvedType = expectedType;

                // let i: Int;
                if (!expectedType.isInferred() && initializer == null && !expectedType.isNullable()) {
                    Zeron.resolutionError(new ResolutionError(name,
                            "A variable with no initializer must be of a nullable type."));
                }

                // let x = expression; OR let x: T = expression;
                if (initializer != null) {
                    resolvedType = resolve(initializer);
                    resolvedType = ensureAssignable(expectedType, resolvedType);
                    // replaces <infer> with resolved type for the symbol
                    if (expectedType.isInferred())
                        symbols.setResolvedType(name, resolvedType);
                }

                // if initializer ends up as <infer>, it means expectedType was <infer> as well
                if (resolvedType.isInferred()) {
                    Zeron.resolutionError(new ResolutionError(name,
                            "Cannot infer type from declaration."));
                }

                System.out.print(" resolved variable " + name.lexeme() + " ");
                if (!expectedType.isInferred()) {
                    System.out.println(expectedType + " from explicit type");
                } else {
                    System.out.println(resolvedType + " from initializer");
                }

                define(name);
            }
            case Stmt.While(Token _, Expr condition, Stmt body) -> {
                resolve(condition);
                resolve(body);
            }
        }
    }

    public void resolveStmts(final List<Stmt> statements) {
        for (final var statement : statements) {
            resolve(statement);
        }
    }

    private Expr resolve(Expr expr) {
        return switch (expr) {
            // suggested type for assignment will always be inferred,
            // resolve assigned value and
            // return the expression tagged with the resolved type
            case Expr.Assignment(Token name, Expr value, _) -> {
                final var expectedType = resolveSymbol(name);
                final var resolvedType = resolve(value).type();
                ensureAssignable(expectedType, resolvedType);
                yield expr.withType(resolvedType);
            }
            // suggested type for binary will always be inferred,
            // resolve left and right, ensure types are exact and
            // return the expression tagged with the resolved type
            case Expr.Binary(Expr left, Token op, Expr right, _) -> {
                final var leftType =  resolve(left).type();
                final var rightType = resolve(right).type();
                System.out.println("resolving binary   " + leftType + " " + op.lexeme() + " " + rightType);
                ensureExact(op, leftType, rightType);
                yield expr.withType(leftType.or(rightType));
            }
            // suggested type for call will always be inferred,
            // resolve the arguments, make sure they
            case Expr.Call(Token callee, Token _, List<Expr> arguments) -> {
                final var descriptor = resolveSymbol(callee);

                final var parameters = descriptor.functionParams();
                System.out.print("resolving call     " + callee.lexeme() + parameters);
                System.out.println(" -> " + resolveSymbol(callee).returnType());

                for (final var argument : arguments) {
                    resolve(argument);
                }

                yield resolveSymbol(callee);
            }
            case Expr.Grouping(Token _, Expr expression) -> resolve(expression);
            case Expr.If(Token paren, Expr condition, Expr thenExpr, Expr elseExpr) -> {
                // ensure condition is a boolean
                ensureBoolean(resolve(condition));
                final var then = resolve(thenExpr);
                ensureCommonParent(paren, then, resolve(elseExpr));
                yield then;
            }
            case Expr.Lambda lambda-> {
                resolveLambda(lambda);
                yield new TypeDescriptor("<infer>");
            }
            case Expr.Literal(Object _, TypeDescriptor typeDescriptor) ->
                    typeDescriptor;
            case Expr.Logical(Expr left, Token _, Expr right) ->
                    new TypeDescriptor("Boolean");
            case Expr.Unary(Token _, Expr right) ->
                    resolve(right);
            case Expr.Variable(Token name) -> {
                System.out.print("resolving lookup   " + name.lexeme());
                if (symbols.contains(name) && !symbols.getSymbol(name).isInit()) {
                    Zeron.error(name,
                            "Can't read local variable in its own initializer.");
                }

                System.out.println(" -> " + resolveSymbol(name));
                yield resolveSymbol(name);
            }
        };
    }

    private void beginScope() {
        symbols.beginScope();
    }

    private void endScope() {
        symbols.endScope();
    }

    private void declare(final Token name, final TypeDescriptor type, final boolean isFinal) {
        symbols.declare(name, type, isFinal);
    }

    private void define(final Token name) {
        symbols.define(name);
    }

    private TypeDescriptor resolveSymbol(final Token name) {
        return symbols.getSymbol(name).type();
    }

    private void resolveLambda(final Expr.Lambda lambda) {
        beginScope();
        for (final var param : lambda.params()) {
            declare(param, new TypeDescriptor("<infer>"), true);
            define(param);
        }
        resolveStmts(lambda.body());
        endScope();
    }

    private void resolveFunction(final Stmt.Function function) {
        beginScope();
        System.out.println("resolving function " + function.name().lexeme() + ": " + function.typeDescriptor());
        final var paramNames = function.parameters();
        final var params = function.typeDescriptor().functionParams();
        for (int i = 0; i < function.parameters().size(); i++) {
            declare(paramNames.get(i), params.get(i), true);
            define(paramNames.get(i));
        }
        resolveStmts(function.body());
        final var resolvedType = ensureReturns(
                function.name(),
                function.typeDescriptor().returnType(),
                function.body());
        if (function.typeDescriptor().returnType().isInferred())
            symbols.setResolvedReturnType(function.name(), resolvedType);
        System.out.println(" resolved function " + function.name().lexeme() + " -> " + function.typeDescriptor().withReturnType(resolvedType));
        endScope();
    }

    public TypeDescriptor ensureReturns(final Token where,
                                        final TypeDescriptor expectedType,
                                        final List<Stmt> statements) {
        var currentType = expectedType;
        for (final var statement : statements) {
            if (statement instanceof Stmt.Return(Expr value)) {
                var returnType = resolve(value);
                if (currentType.isInferred())
                    currentType = returnType;
                else
                    ensureAssignable(currentType, returnType);
            }
        }
        return currentType.isInferred() ? new TypeDescriptor(":Unit") : currentType;
    }

    public TypeDescriptor ensureExact(final Token where,
                                      final TypeDescriptor typeA,
                                      final TypeDescriptor typeB) {
        if (typeA.equals(typeB)) return typeA;

        Zeron.resolutionError(new ResolutionError(where, "Types are not exact."));
        return typeA.isInferred() ? typeB : typeA;
    }

    public void ensureCommonParent(final Token where,
                                   final TypeDescriptor typeA,
                                   final TypeDescriptor typeB) {
        if (typeA.isBuiltIn() && typeB.isBuiltIn()) {
            ensureExact(where, typeA, typeB);
        }
    }

    public TypeDescriptor ensureAssignable(TypeDescriptor expectedType, TypeDescriptor resolvedType) {
        if (expectedType.isInferred() && resolvedType.isInferred())
            throw new IllegalStateException("Double inferred types");
        return expectedType.isInferred() ? resolvedType : expectedType;
    }

    public void ensureBoolean(TypeDescriptor type) { }

    public void ensureIterable(TypeDescriptor type) { }
}
