package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.*;
import java.util.stream.IntStream;

public final class Resolver {
    // this table stores every name related to a type to avoid name collisions
    public final SymbolTable symbols = new SymbolTable();
    public final Set<String> types   = new HashSet<>();

    public static final Token SYNTHETIC_IDENTIFIER = new Token(
            TokenType.IDENTIFIER,"<synthetic>", null, -1);
    public static final Stmt SYNTHETIC_VAR = new Stmt.Var(
            SYNTHETIC_IDENTIFIER,
            TypeDescriptor.ofNever(),
            null,
            true);
    public static final Stmt SYNTHETIC_FUN = new Stmt.Function(
            SYNTHETIC_IDENTIFIER,
            List.of(),
            TypeDescriptor.functionOf("<synthetic>", TypeDescriptor.ofUnit()),
            List.of());

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
                declareFunction(fn, fn.name(), fn.typeDescriptor());
                resolveFunction(fn);
            }
            case Stmt.For(Token iterationBind, Token _, Expr iterable, Stmt body) -> {
                beginScope();
                final var iterableType = resolve(iterable);
                ensureIterable(iterableType);
                // iterable is @ 1 Iterable TYPE. we extract TYPE by doing
                // iterableType.typeParameters() and getting the first (and only)
                final var typeParameter = ((Generic) iterableType).typeParameters().getFirst();
                declare(SYNTHETIC_VAR, iterationBind, typeParameter, true);
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
            case Stmt.Var var -> {
                System.out.println("resolving variable " + var.name().lexeme() + " " + var.type());

                declare(var, var.name(), var.type(), var.isFinal());
                TypeDescriptor resolvedType = var.type();

                // let i: Int;
                if    (!(resolvedType instanceof Infer)
                    && var.initializer() == null
                    && !(resolvedType instanceof Nullable n && n.isNullable())) {
                    Zeron.resolutionError(new ResolutionError(var.name(),
                            "A variable with no initializer must be of a nullable type."));
                }

                // let x = expression; OR let x: T = expression;
                if (var.initializer() != null) {
                    resolvedType = resolve(var.initializer());
                    resolvedType = ensureAssignable(var.type(), resolvedType);
                    // replaces <infer> with resolved type for the symbol
                    if (var.type() instanceof Infer)
                        symbols.setResolvedType(var.name(), resolvedType);
                }

                // if initializer ends up as <infer>, it means expectedType was <infer> as well
                if (resolvedType instanceof Infer) {
                    Zeron.resolutionError(new ResolutionError(var.name(),
                            "Cannot infer type from declaration."));
                }

                System.out.print(" resolved variable " + var.name().lexeme() + " ");
                if (!(var.type() instanceof Infer)) {
                    System.out.println(var.type() + " from explicit type");
                } else {
                    System.out.println(resolvedType + " from initializer");
                }

                define(var.name());
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

    private TypeDescriptor resolve(Expr expr) {
        return switch (expr) {
            // |> a = expr ::= when
            //               | assignable (typeof a, typeof expr) -> typeof expr
            //               | else                               -> ResolutionError
            // suggested type for assignment will always be inferred,
            // resolve the expression, ensure it's assignable
            // return the assigned type (the resolved one)
            case Expr.Assignment assignment -> {
                final var expectedType = getSymbol(assignment.name);
                final var resolvedType = resolve(assignment.value);
                ensureAssignable(expectedType, resolvedType);

                assignment.setType(resolvedType);
                yield resolvedType;
            }
            // |> a + b ::= when predicate x is Infer, TypeParam
            //            | predicate a && not predicate b -> typeof b
            //            | predicate b && not predicate a -> typeof a
            //            | else                           -> ResolutionError
            // suggested type for binary will always be inferred,
            // resolve left and right, ensure types are exact and
            // return the expression tagged with the resolved type
            case Expr.Binary binary -> {
                final var leftType =  resolve(binary.left);
                final var rightType = resolve(binary.right);
                System.out.println("resolving binary   " + leftType + " " + binary.operator.lexeme() + " " + rightType);
                ensureExact(binary.operator, leftType, rightType);
                final var resolvedType = leftType.or(rightType);

                binary.setType(resolvedType);
                yield resolvedType;
            }
            // |> a(b, c) ::= match return_type a is not Infer
            //              | and ensure_args (a b c) -> return_type a
            //              | else -> resolve_with_types (a typeof b typeof c)
            // suggested type for call will always be inferred,
            // resolve the arguments, make sure they align with
            // the parameters and
            // return the expression tagged with the resolved type
            case Expr.Call call -> {
                // must disambiguate call between lambda (variable) and function (global)
                Function descriptor;
                // check locally first, since lambdas shadow functions
                if (symbols.containsSymbol(call.callee)) {
                    final var symbol = getSymbol(call.callee);
                    if (symbol instanceof Function f) {
                        descriptor = f;
                    } else {
                        Zeron.resolutionError(new ResolutionError(call.callee,
                                "Callee is not a function."));
                        yield null;
                    }
                } else {
                    descriptor = getFunction(call.callee);
                }
                var parameters = descriptor.parameters();
                System.out.print("resolving call     " + call.callee.lexeme() + parameters);
                System.out.println(" -> " + descriptor.returnType());

                // if arities differ, there were too many args
                if (descriptor.arity() != call.arguments.size()) {
                    Zeron.resolutionError(new ResolutionError(call.callee,
                            "Expected " + descriptor.arity() + " arguments, found " + call.arguments.size()));
                }

                // if a lambda return type is inferred,
                // parameters are generic
                if (descriptor.returnType() instanceof Infer) {
                    descriptor = resolveCallWithTypes(call.callee, call.arguments);
                } else {
                    for (var i = 0; i < call.arguments.size(); i++) {
                        ensureAssignable(parameters.get(i), resolve(call.arguments.get(i)));
                    }
                }

                yield descriptor.returnType();
            }
            // |> (a) ::= typeof a
            // suggested type for groupings will always be inferred,
            // just unbox and send the expression down the resolution pipeline
            case Expr.Grouping grouping ->
                    resolve(grouping.expression);
            // suggested type for if expressions will always be inferred,
            // resolve the condition, ensure it is a boolean,
            // resolve each branch, ensure they have a common parent, and
            // return the expression tagged with the resolved type
            case Expr.If iff -> {
                // ensure condition is a boolean
                ensureBoolean(resolve(iff.condition));
                final var then = resolve(iff.thenExpr);
                ensureCommonParent(iff.paren, then, resolve(iff.elseExpr));
                yield then;
            }
            // suggested type for lambdas will always be inferred,
            // but they need to be structurally inferred. we can extract
            // arity from the parameter count and infer a return type from
            // the body.
            case Expr.Lambda lambda ->
                    TypeDescriptor.ofInfer();
            case Expr.Literal literal ->
                    literal.getType();
            case Expr.Logical _ ->
                    TypeDescriptor.ofBoolean();
            case Expr.Unary unary ->
                    resolve(unary.right);
            case Expr.Variable variable -> {
                final var name = variable.name;
                System.out.print("resolving lookup   " + name.lexeme());
                if (symbols.containsSymbol(name) && !symbols.getSymbol(name).isInit()) {
                    Zeron.resolutionError(new ResolutionError(name,
                            "Can't read local variable in its own initializer."));
                }

                System.out.println(" -> " + getSymbol(name));
                yield getSymbol(name);
            }
        };
    }

    private void beginScope() {
        symbols.beginScope();
    }

    private void endScope() {
        symbols.endScope();
    }

    private void declareFunction(final Stmt declaration,
                                 final Token name,
                                 final TypeDescriptor type) {
        symbols.declareFunction(declaration, name, type);
    }

    private void declare(final Stmt declaration,
                         final Token name,
                         final TypeDescriptor type,
                         final boolean isFinal) {
        symbols.declareSymbol(declaration, name, type, isFinal);
    }

    private void define(final Token name) {
        symbols.define(name);
    }

    private Function getFunction(final Token name) {
        return (Function) symbols.getFunction(name).type();
    }

    private TypeDescriptor getSymbol(final Token name) {
        return symbols.getSymbol(name).type();
    }

    private Stmt getDeclaration(final Token name) {
        return symbols.getSymbol(name).declaration();
    }

    // TODO: fix
    private Function resolveLambda(final Expr.Lambda lambda) {
        beginScope();
        final var param = lambda.param;
        // lambdas are always of the form a -> ...; so the parameter type starts as infer
        final var paramType = TypeDescriptor.ofInfer();
        final var generified = TypeDescriptor.newTypeParameter(param.lexeme().toUpperCase());
        declare(SYNTHETIC_VAR, param, generified, true);
        define(param);

        final var returns = lambda.body
                .stream()
                .filter(it -> it instanceof Stmt.Return)
                .map(it -> (Stmt.Return)it)
                .toList();

        if (returns.isEmpty()) return TypeDescriptor.lambdaOf(TypeDescriptor.ofUnit(), paramType);

        var currentReturnType = resolve(returns.getFirst().value());
        for (final var stmt : lambda.body) {
            if (stmt instanceof Stmt.Return(Expr value)) {
                currentReturnType = ensureAssignable(currentReturnType, resolve(value));
            } else {
                resolve(stmt);
            }
        }

        endScope();
        return TypeDescriptor.lambdaOf(currentReturnType, paramType);
    }

    private Function resolveCallWithTypes(final Token callee, final List<Expr> arguments) {
        // original e.g (#A, #B) -> infer
        final var lambdaTypeDesc = getSymbol(callee);
        // candidate e.g (Int, Int) -> infer
        final var candidateTypeDesc = TypeDescriptor.functionOf(
                "",
                TypeDescriptor.ofInfer(),
                arguments.stream().map(this::resolve).toArray(TypeDescriptor[]::new));

        // assume lambda, so declaration must be a variable
        final var declaration = (Stmt.Var)getDeclaration(callee);
        // assume lambda is inferred, so initializer is not null
        final var lambda = (Expr.Lambda)declaration.initializer();
        final var candidate = new Expr.Lambda(lambda.arrow, lambda.param, lambda.body,
                candidateTypeDesc);
        return resolveLambda(candidate);
    }

    private void resolveFunction(final Stmt.Function function) {
        beginScope();
        final var paramNames = function.parameters();
        final var params = function.typeDescriptor().parameters();
        for (int i = 0; i < function.parameters().size(); i++) {
            declare(SYNTHETIC_VAR, paramNames.get(i), params.get(i), true);
            define(paramNames.get(i));
        }
        resolveStmts(function.body());
        final var resolvedType = ensureReturns(
                function.name(),
                function.typeDescriptor().returnType(),
                function.body());
        if (function.typeDescriptor().returnType() instanceof Infer)
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
                if (currentType instanceof Infer)
                    currentType = returnType;
                else
                    ensureAssignable(currentType, returnType);
            }
        }
        return currentType instanceof Infer ? TypeDescriptor.ofUnit() : currentType;
    }

    public TypeDescriptor ensureExact(final Token where,
                                      final TypeDescriptor typeA,
                                      final TypeDescriptor typeB) {
        // e.g     Int + Int      ::= Int, excluding
        //     <infer> + <infer>, which should refine to a resolution error
        if ((!(typeA instanceof Infer) && !(typeB instanceof Infer)) && typeA.equals(typeB)) return typeA;
        // e.g T + Int ::= Int
        if (   typeA instanceof Infer  && !(typeB instanceof Infer)) return typeB;
        // e.g Int + T ::= Int
        if ( !(typeA instanceof Infer) &&   typeB instanceof Infer) return typeA;
        // e.g T + R   ::= enforce T and R are the same
        if (typeA instanceof TypeParameter && typeB instanceof TypeParameter)
            // INFERENCE FAILURE
            return TypeDescriptor.ofNever();

        Zeron.resolutionError(new ResolutionError(where, "Types are not exact."));
        return typeA instanceof Infer ? typeB : typeA;
    }

    public void ensureCommonParent(final Token where,
                                   final TypeDescriptor typeA,
                                   final TypeDescriptor typeB) {
        //if (typeA.isBuiltIn() && typeB.isBuiltIn()) {
            ensureExact(where, typeA, typeB);
        //}
    }

    public TypeDescriptor ensureAssignable(TypeDescriptor expectedType, TypeDescriptor resolvedType) {
        /* if (expectedType.isInferred() && resolvedType.isInferred())
            throw new IllegalStateException("Double inferred types"); */
        return expectedType instanceof Infer ? resolvedType : expectedType;
    }

    public void ensureBoolean(TypeDescriptor type) { }

    public void ensureIterable(TypeDescriptor type) { }
}
