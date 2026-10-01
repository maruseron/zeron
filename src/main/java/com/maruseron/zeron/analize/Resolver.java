package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.*;

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
            BindingMutability.IMMUTABLE);
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
                assert iterableType != null;
                final var typeParameter = ((GenericDescriptor) iterableType).typeParameters().getFirst();
                declare(SYNTHETIC_VAR, iterationBind, typeParameter, BindingMutability.IMMUTABLE);
                define(iterationBind);
                resolve(body);
                endScope();
            }
            case Stmt.If(Token keyword, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                ensureBoolean(resolve(condition), keyword);
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

                declare(var, var.name(), var.type(), var.mutability());
                TypeDescriptor resolvedType = var.type();

                // let i: Int;
                if    (!(resolvedType instanceof InferDescriptor)
                    && var.initializer() == null
                    && !(resolvedType.isNullable())) {
                    Zeron.resolutionError(new ResolutionError(var.name(),
                            "A variable with no initializer must be of a nullable type."));
                }

                // let x = expression; OR let x: T = expression;
                if (var.initializer() != null) {
                    resolvedType = resolve(var.initializer());
                    resolvedType = ensureAssignable(var.type(), resolvedType, var.name());
                    if (var.type() instanceof InferDescriptor
                            && resolvedType instanceof NullDescriptor) {
                        Zeron.resolutionError(new ResolutionError(var.name(),
                                "Cannot infer a type from a null value; add an explicit nullable type annotation."));
                    }
                    // replaces <infer> with resolved type for the symbol
                    if (var.type() instanceof InferDescriptor)
                        symbols.setResolvedType(var.name(), resolvedType);
                }

                // if initializer ends up as <infer>, it means expectedType was <infer> as well
                if (resolvedType instanceof InferDescriptor) {
                    Zeron.resolutionError(new ResolutionError(var.name(),
                            "Cannot infer type from declaration."));
                }

                System.out.print(" resolved variable " + var.name().lexeme() + " ");
                if (!(var.type() instanceof InferDescriptor)) {
                    System.out.println(var.type() + " from explicit type");
                } else {
                    System.out.println(resolvedType + " from initializer");
                }

                define(var.name());
            }
            case Stmt.While(Token keyword, Expr condition, Stmt body) -> {
                ensureBoolean(resolve(condition), keyword);
                resolve(body);
            }
        }
    }

    public void resolveStmts(final List<Stmt> statements) {
        for (final var statement : statements) {
            resolve(statement);
        }
    }

    private void ensureImmutableCaptures(final Expr.Lambda lambda) {
        final var lambdaLocals = new HashSet<String>();
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
            case Stmt.Print(Expr expression) -> walkCaptureUsage(expression, localNames);
            case Stmt.Return(Expr value) -> {
                if (value != null) walkCaptureUsage(value, localNames);
            }
            case Stmt.Expression(Expr expression) -> walkCaptureUsage(expression, localNames);
            case Stmt.Var(Token _, TypeDescriptor _, Expr initializer, BindingMutability _) -> {
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
            case Stmt.Function(Token _, List<Token> parameters, FunctionDescriptor _, List<Stmt> body) -> {
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
            case Expr.ArrayLiteral literal -> {
                for (final var element : literal.elements) walkCaptureUsage(element, localNames);
            }
            case Expr.ArrayLength length -> walkCaptureUsage(length.array, localNames);
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
            case Expr.Binary binary -> {
                walkCaptureUsage(binary.left, localNames);
                walkCaptureUsage(binary.right, localNames);
            }
            case Expr.Call call -> {
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
            case Expr.Lambda lambda -> {
                final var nestedNames = new HashSet<>(localNames);
                for (final var param : lambda.params) {
                    nestedNames.add(param.lexeme());
                }
                ensureImmutableCaptures(lambda, nestedNames);
            }
            case Expr.Literal _ -> {}
            case Expr.Logical logical -> {
                walkCaptureUsage(logical.left, localNames);
                walkCaptureUsage(logical.right, localNames);
            }
            case Expr.Unary unary -> walkCaptureUsage(unary.right, localNames);
            case Expr.Variable variable -> {
                if (localNames.contains(variable.name.lexeme())) return;
                if (symbols.containsSymbol(variable.name)
                        && symbols.getSymbol(variable.name).mutability().isReassignable()) {
                    Zeron.resolutionError(new ResolutionError(variable.name,
                            "Cannot capture mutable binding '" + variable.name.lexeme() + "' in a lambda."));
                }
            }
            default -> {}
        }
    }

    private void ensureImmutableCaptures(final Expr.Lambda lambda, final Set<String> inheritedNames) {
        final var lambdaLocals = new HashSet<>(inheritedNames);
        for (final var param : lambda.params) {
            lambdaLocals.add(param.lexeme());
        }
        walkCaptureUsage(lambda.body, lambdaLocals);
    }

    private TypeDescriptor resolve(Expr expr) {
        return switch (expr) {
            case Expr.ArrayLiteral literal -> {
                final var elementTypes = new ArrayList<TypeDescriptor>();
                var elementType = (TypeDescriptor) null;
                var hasNullElement = false;
                for (final var element : literal.elements) {
                    final var resolvedElement = resolve(element);
                    elementTypes.add(resolvedElement);
                    if (resolvedElement instanceof NullDescriptor) {
                        hasNullElement = true;
                    } else if (elementType == null) {
                        elementType = resolvedElement;
                    } else if (elementType instanceof NullableDescriptor nullable
                            && nullable.baseType().equals(resolvedElement)) {
                        continue;
                    } else if (resolvedElement instanceof NullableDescriptor nullable
                            && nullable.baseType().equals(elementType)) {
                        elementType = resolvedElement;
                    } else {
                        ensureExact(SYNTHETIC_IDENTIFIER, elementType, resolvedElement);
                    }
                }
                if (elementType == null) {
                    Zeron.resolutionError(new ResolutionError(SYNTHETIC_IDENTIFIER,
                            "Cannot infer an array element type from null values."));
                }
                if (hasNullElement) elementType = elementType.toNullable();
                for (final var resolvedElement : elementTypes) {
                    ensureAssignable(elementType, resolvedElement);
                }
                final var arrayType = new ReferenceDescriptor(TypeDescriptor.arrayOf(elementType));
                literal.setType(arrayType);
                yield arrayType;
            }
            case Expr.ArrayLength length -> {
                resolveArrayType(resolve(length.array), SYNTHETIC_IDENTIFIER);
                length.setType(TypeDescriptor.ofInt());
                yield TypeDescriptor.ofInt();
            }
            case Expr.Index index -> {
                final var arrayType = resolveArrayType(resolve(index.array), SYNTHETIC_IDENTIFIER);
                ensureExact(SYNTHETIC_IDENTIFIER, TypeDescriptor.ofInt(), resolve(index.index));
                index.setType(arrayType.elementType());
                yield arrayType.elementType();
            }
            case Expr.IndexAssignment assignment -> {
                final var receiverType = resolve(assignment.array);
                if (!(receiverType instanceof ReferenceDescriptor reference)
                        || !(reference.baseType() instanceof ArrayDescriptor arrayType)) {
                    Zeron.resolutionError(new ResolutionError(SYNTHETIC_IDENTIFIER,
                            "Array slot assignment requires a mutable &Array<T> view."));
                    yield TypeDescriptor.ofUnit();
                }
                ensureExact(SYNTHETIC_IDENTIFIER, TypeDescriptor.ofInt(), resolve(assignment.index));
                ensureAssignable(arrayType.elementType(), resolve(assignment.value));
                assignment.setType(TypeDescriptor.ofUnit());
                yield TypeDescriptor.ofUnit();
            }
            // |> a = expr ::= when
            //               | assignable (typeof a, typeof expr) -> typeof expr
            //               | else                               -> ResolutionError
            // suggested type for assignment will always be inferred,
            // resolve the expression, ensure it's assignable
            // return the assigned type (the resolved one)
            case Expr.Assignment assignment -> {
                final var binding = symbols.getSymbol(assignment.name);
                if (!binding.mutability().isReassignable()) {
                    Zeron.resolutionError(new ResolutionError(assignment.name,
                            "Cannot reassign immutable binding '" + assignment.name.lexeme() + "'."));
                }
                final var expectedType = binding.type();
                final var resolvedType = resolve(assignment.value);
                ensureAssignable(expectedType, resolvedType, assignment.name);

                symbols.define(assignment.name);
                assignment.setType(expectedType);
                yield expectedType;
            }
            // |> a + b ::= when predicate x is Infer, TypeParam
            //            | predicate a && not predicate b -> typeof b
            //            | predicate b && not predicate a -> typeof a
            //            | else                           -> ResolutionError
            // suggested type for binary will always be inferred,
            // resolve left and right, ensure types are exact and
            // return the expression tagged with the resolved type
            case Expr.Binary binary -> {
                final var leftType = resolve(binary.left);
                final var rightType = resolve(binary.right);
                final var refinedLeftType = refineInferredType(binary.left, leftType, rightType);
                final var refinedRightType = refineInferredType(binary.right, rightType, leftType);
                System.out.println("resolving binary   " + refinedLeftType + " " + binary.operator.lexeme() + " " + refinedRightType);
                if (refinedLeftType.isNullable() || refinedRightType.isNullable()
                    || refinedLeftType instanceof NullDescriptor || refinedRightType instanceof NullDescriptor) {
                    Zeron.resolutionError(new ResolutionError(binary.operator,
                        "Nullable operands require a null check before using this operator."));
                }
                ensureExact(binary.operator, refinedLeftType, refinedRightType);
                final var resolvedType = refinedLeftType.orElse(refinedRightType);

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
                FunctionDescriptor descriptor;
                // check locally first, since lambdas shadow functions
                if (symbols.containsSymbol(call.callee)) {
                    final var symbol = getSymbol(call.callee);
                    if (symbol instanceof FunctionDescriptor f) {
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
                if (descriptor.returnType() instanceof InferDescriptor) {
                    descriptor = resolveCallWithTypes(call.callee, call.arguments);
                } else {
                    for (var i = 0; i < call.arguments.size(); i++) {
                        final var argument = call.arguments.get(i);
                        final var expected = parameters.get(i);
                        final var resolved = argument instanceof Expr.Lambda lambda
                                && expected instanceof FunctionDescriptor functionType
                                ? resolveLambda(lambda, functionType)
                                : resolve(argument);
                        ensureAssignable(expected, resolved, call.callee);
                    }
                }

                call.setType(descriptor.returnType());
                yield descriptor.returnType();
            }
            // |> (a) ::= typeof a
            // suggested type for groupings will always be inferred,
            // just unbox and send the expression down the resolution pipeline
            case Expr.Grouping grouping -> {
                final var resolvedType = resolve(grouping.expression);
                grouping.setType(resolvedType);
                yield resolvedType;
            }
            // suggested type for if expressions will always be inferred,
            // resolve the condition, ensure it is a boolean,
            // resolve each branch, ensure they have a common parent, and
            // return the expression tagged with the resolved type
            case Expr.If iff -> {
                // ensure condition is a boolean
                ensureBoolean(resolve(iff.condition), iff.paren);
                final var then = resolve(iff.thenExpr);
                ensureCommonParent(iff.paren, then, resolve(iff.elseExpr));
                yield then;
            }
            // suggested type for lambdas will always be inferred,
            // but they need to be structurally inferred. we can extract
            // arity from the parameter count and infer a return type from
            // the body.
            case Expr.Lambda lambda -> {
                ensureImmutableCaptures(lambda);
                final var resolvedType = inferLambdaType(lambda);
                lambda.setType(resolvedType);
                yield resolvedType;
            }
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

                final var resolvedType = getSymbol(name);
                variable.setType(resolvedType);
                System.out.println(" -> " + resolvedType);
                yield resolvedType;
            }
        };
    }

    private ArrayDescriptor resolveArrayType(TypeDescriptor type, Token where) {
        if (type instanceof ReferenceDescriptor reference) type = reference.baseType();
        if (type instanceof ArrayDescriptor arrayType) return arrayType;
        Zeron.resolutionError(new ResolutionError(where, "Expected a non-null Array<T> value."));
        throw new IllegalStateException("unreachable");
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
                         final BindingMutability mutability) {
        symbols.declareSymbol(declaration, name, type, mutability);
    }

    private void define(final Token name) {
        symbols.define(name);
    }

    private FunctionDescriptor getFunction(final Token name) {
        return (FunctionDescriptor) symbols.getFunction(name).type();
    }

    private TypeDescriptor getSymbol(final Token name) {
        return symbols.getSymbol(name).type();
    }

    private Stmt getDeclaration(final Token name) {
        return symbols.getSymbol(name).declaration();
    }

    // TODO: fix
    private FunctionDescriptor inferLambdaType(final Expr.Lambda lambda) {
        beginScope();
        try {
            for (final var param : lambda.params) {
                declare(SYNTHETIC_VAR, param, TypeDescriptor.ofInfer(), BindingMutability.IMMUTABLE);
                define(param);
            }

            TypeDescriptor returnType = TypeDescriptor.ofUnit();
            for (final var stmt : lambda.body) {
                if (stmt instanceof Stmt.Return(Expr value)) {
                    returnType = value == null ? TypeDescriptor.ofUnit() : resolve(value);
                } else {
                    resolve(stmt);
                }
            }

            final var parameterTypes = new ArrayList<TypeDescriptor>();
            for (final var param : lambda.params) {
                final var binding = symbols.getSymbol(param);
                parameterTypes.add(binding.type() instanceof InferDescriptor
                        ? TypeDescriptor.ofInfer()
                        : binding.type());
            }

            return parameterTypes.isEmpty()
                    ? TypeDescriptor.functionOf("", returnType)
                    : TypeDescriptor.functionOf("", returnType,
                            parameterTypes.toArray(TypeDescriptor[]::new));
        } finally {
            endScope();
        }
    }

    private FunctionDescriptor resolveLambda(final Expr.Lambda lambda,
                                             final FunctionDescriptor expectedType) {
        final var lambdaArity = lambda.params.size();
        if (lambdaArity != expectedType.arity()) {
            Zeron.resolutionError(new ResolutionError(lambda.arrow,
                    "Expected " + expectedType.arity() + " lambda parameters, found " + lambdaArity));
            return expectedType;
        }

        beginScope();
        try {
            for (int i = 0; i < lambda.params.size(); i++) {
                final var parameter = lambda.params.get(i);
                final var parameterType = expectedType.parameters().get(i);
                declare(SYNTHETIC_VAR, parameter, parameterType, BindingMutability.IMMUTABLE);
                define(parameter);
            }

            TypeDescriptor returnType = TypeDescriptor.ofUnit();
            for (final var stmt : lambda.body) {
                if (stmt instanceof Stmt.Return(Expr value)) {
                    returnType = value == null ? TypeDescriptor.ofUnit() : resolve(value);
                } else {
                    resolve(stmt);
                }
            }

            if (!(expectedType.returnType() instanceof InferDescriptor)) {
                ensureAssignable(expectedType.returnType(), returnType, lambda.arrow);
            }

            final var resolvedType = expectedType.returnType() instanceof InferDescriptor
                    ? expectedType.toReturnType(returnType)
                    : expectedType;
            lambda.setType(resolvedType);
            return resolvedType;
        } finally {
            endScope();
        }
    }

    private FunctionDescriptor resolveCallWithTypes(final Token callee, final List<Expr> arguments) {
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
        final var candidate = new Expr.Lambda(lambda.arrow, lambda.params, lambda.body,
                candidateTypeDesc);
        final var resolvedType = resolveLambda(candidate, candidateTypeDesc);
        lambda.setType(resolvedType);
        if (declaration instanceof Stmt.Var variable) {
            symbols.setResolvedType(variable.name(), resolvedType);
        }
        return resolvedType;
    }

    private TypeDescriptor refineInferredType(final Expr expr,
                                             final TypeDescriptor type,
                                             final TypeDescriptor otherType) {
        if (!(expr instanceof Expr.Variable variable)
                || !(type instanceof InferDescriptor)
                || otherType instanceof InferDescriptor
                || !symbols.containsSymbol(variable.name)
                || !(symbols.getSymbol(variable.name).type() instanceof InferDescriptor)) {
            return type;
        }

        symbols.setResolvedType(variable.name, otherType);
        return otherType;
    }

    private void resolveFunction(final Stmt.Function function) {
        beginScope();
        final var paramNames = function.parameters();
        final var params = function.typeDescriptor().parameters();
        for (int i = 0; i < function.parameters().size(); i++) {
            declare(SYNTHETIC_VAR, paramNames.get(i), params.get(i), BindingMutability.IMMUTABLE);
            define(paramNames.get(i));
        }
        resolveStmts(function.body());
        final var resolvedType = ensureReturns(
                function.name(),
                function.typeDescriptor().returnType(),
                function.body());
        if (function.typeDescriptor().returnType() instanceof InferDescriptor) {
            symbols.setResolvedReturnType(function.name(), resolvedType);
        }
        System.out.println(" resolved function " + function.name().lexeme() + " -> " + symbols.getFunction(function.name()).type());
        endScope();
    }

    public TypeDescriptor ensureReturns(final Token where,
                                        final TypeDescriptor expectedType,
                                        final List<Stmt> statements) {
        var currentType = expectedType;
        for (final var statement : statements) {
            if (statement instanceof Stmt.Return(Expr value)) {
                var returnType = resolve(value);
                if (currentType instanceof InferDescriptor)
                    currentType = returnType;
                else
                    ensureAssignable(currentType, returnType, where);
            }
        }
        return currentType instanceof InferDescriptor ? TypeDescriptor.ofUnit() : currentType;
    }

    public TypeDescriptor ensureExact(final Token where,
                                      final TypeDescriptor typeA,
                                      final TypeDescriptor typeB) {
        if (typeA instanceof InferDescriptor && typeB instanceof InferDescriptor) {
            return TypeDescriptor.ofInfer();
        }
        // e.g     Int + Int      ::= Int, excluding
        //     <infer> + <infer>, which should refine to a resolution error
        if (typeA.isWellFormed() && typeB.isWellFormed() && typeA.equals(typeB)) return typeA;
        // e.g T + Int ::= Int
        // if (typeA instanceof TypeParameter ta && ta.isTypeParameter() && typeB.isWellFormed()) return typeB;
        // e.g Int + T ::= Int
        // if (typeA.isWellFormed() && typeB instanceof TypeParameter tb && tb.isTypeParameter()) return typeA;
        // e.g T + R   ::= enforce T and R are the same
        /*if (   typeA instanceof TypeParameter ta && ta.isTypeParameter()
            && typeB instanceof TypeParameter tb && tb.isTypeParameter())
            // INFERENCE FAILURE
            return TypeDescriptor.ofNever();*/

        Zeron.resolutionError(new ResolutionError(where, "Types are not exact."));
        return typeA instanceof InferDescriptor ? typeB : typeA;
    }

    public void ensureCommonParent(final Token where,
                                   final TypeDescriptor typeA,
                                   final TypeDescriptor typeB) {
        //if (typeA.isBuiltIn() && typeB.isBuiltIn()) {
            ensureExact(where, typeA, typeB);
        //}
    }

    public TypeDescriptor ensureAssignable(TypeDescriptor expectedType, TypeDescriptor resolvedType) {
        return ensureAssignable(expectedType, resolvedType, SYNTHETIC_IDENTIFIER);
    }

    private TypeDescriptor ensureAssignable(TypeDescriptor expectedType,
                                            TypeDescriptor resolvedType,
                                            Token where) {
        if (expectedType instanceof InferDescriptor) return resolvedType;
        if (expectedType.equals(resolvedType)) return expectedType;
        if (resolvedType instanceof ReferenceDescriptor reference
            && expectedType.equals(reference.baseType())) return expectedType;
        if (resolvedType instanceof NullDescriptor && expectedType.isNullable()) return expectedType;
        if (expectedType instanceof NullableDescriptor nullable
            && (nullable.baseType().equals(resolvedType)
            || resolvedType instanceof ReferenceDescriptor reference
            && nullable.baseType().equals(reference.baseType()))) {
            return expectedType;
        }

        Zeron.resolutionError(new ResolutionError(where,
                "Expected " + expectedType + ", found " + resolvedType + "."));
        return expectedType;
    }

    public void ensureBoolean(TypeDescriptor type) {
        ensureBoolean(type, SYNTHETIC_IDENTIFIER);
    }

    private void ensureBoolean(TypeDescriptor type, Token where) {
        if (type instanceof BooleanDescriptor) return;
        Zeron.resolutionError(new ResolutionError(where,
                "Condition must have non-null Boolean type."));
    }

    public void ensureIterable(TypeDescriptor type) { }
}
