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
    private final Map<String, Stmt.ClassDecl> classes = new LinkedHashMap<>();
    private final Map<String, Stmt.ContractDecl> contracts = new LinkedHashMap<>();
    private int loopDepth;
    private String currentClassName;

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
        registerTypes(statements);
        for (final var statement : statements) {
            resolve(statement);
        }

        System.out.println("resolution finished successfully with symbol table: \n" + symbols);
    }

    public Map<String, Stmt.ClassDecl> classes() {
        return Collections.unmodifiableMap(classes);
    }

    public Map<String, Stmt.ContractDecl> contracts() {
        return Collections.unmodifiableMap(contracts);
    }

    private void registerTypes(final List<Stmt> statements) {
        for (final var statement : statements) {
            if (statement instanceof Stmt.ClassDecl declaration) {
                registerType(declaration.name());
                classes.put(declaration.name().lexeme(), declaration);
            } else if (statement instanceof Stmt.ContractDecl declaration) {
                registerType(declaration.name());
                contracts.put(declaration.name().lexeme(), declaration);
            }
        }
    }

    private void registerType(final Token name) {
        final var builtin = Set.of("Never", "Unit", "Int", "Float", "Boolean", "String", "Array");
        if (builtin.contains(name.lexeme()) || !types.add(name.lexeme())) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Type name '" + name.lexeme() + "' is already declared."));
        }
    }

    private void resolveContract(final Stmt.ContractDecl contract) {
        final var methodNames = new HashSet<String>();
        for (final var method : contract.methods()) {
            if (!methodNames.add(method.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(method.name(), "Duplicate contract method."));
            }
            validateFunctionTypes(method.typeDescriptor(), method.name());
        }
    }

    private void resolveClass(final Stmt.ClassDecl declaration) {
        final var fieldNames = new HashSet<String>();
        for (final var field : declaration.fields()) {
            if (!fieldNames.add(field.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(field.name(), "Duplicate class member."));
            }
            validateType(field.type(), field.name());
        }

        final var methodNames = new HashSet<String>();
        for (final var method : declaration.methods()) {
            if (!fieldNames.add(method.name().lexeme()) || !methodNames.add(method.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(method.name(), "Duplicate class member."));
            }
            validateFunctionTypes(method.typeDescriptor(), method.name());
        }

        if (declaration.contractName() != null
                && !contracts.containsKey(declaration.contractName().lexeme())) {
            Zeron.resolutionError(new ResolutionError(declaration.contractName(), "Unknown contract."));
        }

        final var previousClass = currentClassName;
        currentClassName = declaration.name().lexeme();
        try {
            for (final var method : declaration.methods()) resolveMethod(declaration, method);
            if (declaration.contractName() != null) {
                checkConformance(declaration, contracts.get(declaration.contractName().lexeme()));
            }
        } finally {
            currentClassName = previousClass;
        }
    }

    private void resolveMethod(final Stmt.ClassDecl owner, final Stmt.Method method) {
        beginScope();
        final var thisToken = new Token(TokenType.THIS, "this", null, method.name().line());
        final TypeDescriptor thisType = method.isMutating()
                ? new ReferenceDescriptor(TypeDescriptor.of(owner.name().lexeme()))
                : TypeDescriptor.of(owner.name().lexeme());
        declare(SYNTHETIC_VAR, thisToken, thisType, BindingMutability.IMMUTABLE);
        define(thisToken);
        for (int i = 0; i < method.parameters().size(); i++) {
            final var parameter = method.parameters().get(i);
            declare(SYNTHETIC_VAR, parameter, method.typeDescriptor().parameters().get(i), BindingMutability.IMMUTABLE);
            define(parameter);
        }
        try {
            resolveStmts(method.body());
            ensureReturns(method.name(), method.typeDescriptor().returnType(), method.body());
        } finally {
            endScope();
        }
    }

    private void checkConformance(final Stmt.ClassDecl declaration, final Stmt.ContractDecl contract) {
        for (final var required : contract.methods()) {
            final var implementation = declaration.methods().stream()
                    .filter(method -> method.name().lexeme().equals(required.name().lexeme()))
                    .findFirst()
                    .orElse(null);
            if (implementation == null || !implementation.isPublic()
                    || implementation.isMutating() != required.isMutating()
                    || !implementation.typeDescriptor().equals(required.typeDescriptor())) {
                Zeron.resolutionError(new ResolutionError(required.name(),
                        "Class does not provide a compatible public contract method '"
                                + required.name().lexeme() + "'."));
            }
        }
    }

    private void validateFunctionTypes(final FunctionDescriptor function, final Token where) {
        for (final var parameter : function.parameters()) validateType(parameter, where);
        validateType(function.returnType(), where);
    }

    private void validateType(final TypeDescriptor type, final Token where) {
        switch (type) {
            case NominalDescriptor nominal -> {
                if (!types.contains(nominal.name())) {
                    Zeron.resolutionError(new ResolutionError(where, "Unknown type '" + nominal.name() + "'."));
                }
            }
            case ArrayDescriptor array -> validateType(array.elementType(), where);
            case NullableDescriptor nullable -> validateType(nullable.baseType(), where);
            case ReferenceDescriptor reference -> validateType(reference.baseType(), where);
            case FunctionDescriptor function -> validateFunctionTypes(function, where);
            case GenericDescriptor generic -> {
                for (final var parameter : generic.typeParameters()) validateType(parameter, where);
            }
            default -> {}
        }
    }

    private void resolve(final Stmt stmt) {
        switch (stmt) {
            case Stmt.ClassDecl declaration -> resolveClass(declaration);
            case Stmt.ContractDecl declaration -> resolveContract(declaration);
            case Stmt.Block(List<Stmt> statements) -> {
                beginScope();
                resolveStmts(statements);
                endScope();
            }
            case Stmt.Break(Token keyword) -> {
                if (loopDepth == 0) {
                    Zeron.resolutionError(new ResolutionError(keyword,
                            "Can only break inside of a loop."));
                }
            }
            case Stmt.Expression(Expr expression) -> {
                resolve(expression);
            }
            case Stmt.Function fn -> {
                validateFunctionTypes(fn.typeDescriptor(), fn.name());
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
                loopDepth++;
                try {
                    resolve(body);
                } finally {
                    loopDepth--;
                }
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
                if (!(var.type() instanceof InferDescriptor)) validateType(var.type(), var.name());

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
                loopDepth++;
                try {
                    resolve(body);
                } finally {
                    loopDepth--;
                }
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
            case Expr.MemberCall call -> {
                walkCaptureUsage(call.receiver, localNames);
                for (final var argument : call.arguments) walkCaptureUsage(argument, localNames);
            }
            case Expr.Property property -> walkCaptureUsage(property.receiver, localNames);
            case Expr.PropertyAssignment assignment -> {
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
            case Expr.MemberCall call -> resolveMemberCall(call);
            case Expr.PropertyAssignment assignment -> {
                resolveProperty(assignment.property);
                final var receiverType = assignment.property.receiver.getType();
                final var field = findField(className(receiverType), assignment.property.name);
                if (!(receiverType instanceof ReferenceDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(assignment.property.name,
                            "Field assignment requires a mutable reference."));
                }
                ensureAssignable(field.type(), resolve(assignment.value), assignment.property.name);
                assignment.setType(TypeDescriptor.ofUnit());
                yield TypeDescriptor.ofUnit();
            }
            case Expr.Property property -> resolveProperty(property);
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
                if (TypeSubstitution.containsTypeParameter(refinedLeftType)
                    || TypeSubstitution.containsTypeParameter(refinedRightType)) {
                    Zeron.resolutionError(new ResolutionError(binary.operator,
                        "Operators on generic type parameters require constraints, which are not supported."));
                }
                if (refinedLeftType.isNullable() || refinedRightType.isNullable()
                    || refinedLeftType instanceof NullDescriptor || refinedRightType instanceof NullDescriptor) {
                    Zeron.resolutionError(new ResolutionError(binary.operator,
                        "Nullable operands require a null check before using this operator."));
                }
                ensureExact(binary.operator, refinedLeftType, refinedRightType);
                final var isComparison = switch (binary.operator.type()) {
                    case EQUAL_EQUAL, BANG_EQUAL, GREATER, GREATER_EQUAL, LESS, LESS_EQUAL -> true;
                    default -> false;
                };
                if ((binary.operator.type() == TokenType.GREATER || binary.operator.type() == TokenType.GREATER_EQUAL
                    || binary.operator.type() == TokenType.LESS || binary.operator.type() == TokenType.LESS_EQUAL)
                    && !(refinedLeftType instanceof IntDescriptor
                    || refinedLeftType instanceof FloatDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(binary.operator,
                        "Relational comparisons require numeric operands."));
                }
                final var resolvedType = isComparison
                    ? TypeDescriptor.ofBoolean()
                    : refinedLeftType.orElse(refinedRightType);

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
                if (descriptor.isGeneric()) {
                    yield resolveGenericCall(call, descriptor);
                }

                if (!call.explicitTypeArguments.isEmpty()) {
                    Zeron.resolutionError(new ResolutionError(call.callee,
                            "This function does not declare type parameters."));
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
            case Expr.Unary unary -> {
                final var operandType = resolve(unary.right);
                if (TypeSubstitution.containsTypeParameter(operandType)) {
                    Zeron.resolutionError(new ResolutionError(unary.operator,
                            "Unary operators on generic type parameters require constraints, which are not supported."));
                }
                if (unary.operator.type() == TokenType.NOT) {
                    ensureBoolean(operandType, unary.operator);
                    unary.setType(TypeDescriptor.ofBoolean());
                    yield TypeDescriptor.ofBoolean();
                }
                unary.setType(operandType);
                yield operandType;
            }
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

    private TypeDescriptor resolveProperty(final Expr.Property property) {
        final var receiverType = resolve(property.receiver);
        final var baseType = receiverType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : receiverType;
        if (property.name.lexeme().equals("length") && baseType instanceof ArrayDescriptor) {
            property.setType(TypeDescriptor.ofInt());
            return TypeDescriptor.ofInt();
        }
        final var ownerName = className(baseType);
        final var owner = classes.get(ownerName);
        if (owner == null) {
            Zeron.resolutionError(new ResolutionError(property.name, "Unknown property."));
        }
        final var field = owner.fields().stream()
                .filter(candidate -> candidate.name().lexeme().equals(property.name.lexeme()))
                .findFirst()
                .orElse(null);
        if (field == null) {
            Zeron.resolutionError(new ResolutionError(property.name, "Unknown field."));
        }
        ensureFieldAccessible(property.name, ownerName);
        property.setType(field.type());
        return field.type();
    }

    private TypeDescriptor resolveMemberCall(final Expr.MemberCall call) {
        if (call.name.lexeme().equals("new") && call.receiver instanceof Expr.Variable typeName
                && classes.containsKey(typeName.name.lexeme())) {
            final var declaration = classes.get(typeName.name.lexeme());
            if (!declaration.constructor().isPublic()
                    && !Objects.equals(currentClassName, declaration.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(call.name, "Constructor is private."));
            }
            if (call.arguments.size() != declaration.fields().size()) {
                Zeron.resolutionError(new ResolutionError(call.name,
                        "Expected " + declaration.fields().size() + " constructor arguments, found "
                                + call.arguments.size() + "."));
            }
            for (int i = 0; i < call.arguments.size(); i++) {
                ensureAssignable(declaration.fields().get(i).type(), resolve(call.arguments.get(i)), call.name);
            }
            final var result = new ReferenceDescriptor(TypeDescriptor.of(declaration.name().lexeme()));
            call.setType(result);
            return result;
        }

        final var receiverType = resolve(call.receiver);
        final var ownerName = className(receiverType);
        final var owner = classes.get(ownerName);
        final var classMethod = owner == null ? null : owner.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .findFirst()
                .orElse(null);
        final var contract = contracts.get(ownerName);
        final var contractMethod = contract == null ? null : contract.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .findFirst()
                .orElse(null);
        if (classMethod == null && contractMethod == null) {
            Zeron.resolutionError(new ResolutionError(call.name, "Unknown method."));
        }

        final var descriptor = classMethod != null
                ? classMethod.typeDescriptor()
                : contractMethod.typeDescriptor();
        final var isMutating = classMethod != null
                ? classMethod.isMutating()
                : contractMethod.isMutating();
        if (classMethod != null && !classMethod.isPublic()
                && !Objects.equals(currentClassName, ownerName)) {
            Zeron.resolutionError(new ResolutionError(call.name, "Method is private."));
        }
        if (isMutating && !(receiverType instanceof ReferenceDescriptor)) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Mutating method requires a mutable reference."));
        }
        if (descriptor.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Expected " + descriptor.arity() + " arguments, found " + call.arguments.size() + "."));
        }
        for (int i = 0; i < call.arguments.size(); i++) {
            ensureAssignable(descriptor.parameters().get(i), resolve(call.arguments.get(i)), call.name);
        }
        call.setType(descriptor.returnType());
        return descriptor.returnType();
    }

    private String className(final TypeDescriptor type) {
        final var baseType = type instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : type;
        return baseType instanceof NominalDescriptor nominal ? nominal.name() : "";
    }

    private Stmt.Field findField(final String className, final Token name) {
        final var owner = classes.get(className);
        if (owner == null) {
            Zeron.resolutionError(new ResolutionError(name, "Field receiver is not a class."));
        }
        return owner.fields().stream()
                .filter(field -> field.name().lexeme().equals(name.lexeme()))
                .findFirst()
                .orElseThrow(() -> new ResolutionError(name, "Unknown field."));
    }

    private void ensureFieldAccessible(final Token where, final String owner) {
        if (!Objects.equals(currentClassName, owner)) {
            Zeron.resolutionError(new ResolutionError(where, "Field is private."));
        }
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
        final var enclosingLoopDepth = loopDepth;
        loopDepth = 0;
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
            loopDepth = enclosingLoopDepth;
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

        final var enclosingLoopDepth = loopDepth;
        loopDepth = 0;
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

            final var unresolvedReturn = TypeSubstitution.containsTypeParameter(expectedType.returnType());
            if (!(expectedType.returnType() instanceof InferDescriptor) && !unresolvedReturn) {
                ensureAssignable(expectedType.returnType(), returnType, lambda.arrow);
            }

            final var resolvedType = expectedType.returnType() instanceof InferDescriptor || unresolvedReturn
                    ? expectedType.toReturnType(returnType)
                    : expectedType;
            lambda.setType(resolvedType);
            return resolvedType;
        } finally {
            endScope();
            loopDepth = enclosingLoopDepth;
        }
    }

    private TypeDescriptor resolveGenericCall(final Expr.Call call,
                                              final FunctionDescriptor genericType) {
        call.setGenericFunctionType(genericType);
        final var typeParameters = genericType.typeParameters();
        if (!call.explicitTypeArguments.isEmpty()
                && call.explicitTypeArguments.size() != typeParameters.size()) {
            Zeron.resolutionError(new ResolutionError(call.callee,
                    "Expected " + typeParameters.size() + " type arguments, found "
                            + call.explicitTypeArguments.size() + "."));
        }
        if (genericType.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(call.callee,
                    "Expected " + genericType.arity() + " arguments, found " + call.arguments.size()));
        }

        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
            final var explicitType = call.explicitTypeArguments.get(i);
            validateType(explicitType, call.callee);
            substitutions.put(typeParameters.get(i), explicitType);
        }

        final var resolvedArguments = new TypeDescriptor[call.arguments.size()];
        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (argument instanceof Expr.Lambda) continue;
            resolvedArguments[i] = resolve(argument);
            unifyType(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.callee);
        }

        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (!(argument instanceof Expr.Lambda lambda)) continue;
            final var expected = TypeSubstitution.substitute(genericType.parameters().get(i), substitutions);
            if (!(expected instanceof FunctionDescriptor functionType)) {
                Zeron.resolutionError(new ResolutionError(call.callee,
                        "A lambda argument requires a function parameter type."));
            }
            resolvedArguments[i] = resolveLambda(lambda, (FunctionDescriptor) expected);
            unifyType(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.callee);
        }

        for (final var parameter : typeParameters) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(call.callee,
                        "Cannot infer type parameter '" + parameter.name()
                                + "'; provide an explicit type argument."));
            }
        }

        final var instantiatedParameters = genericType.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                .toList();
        for (int i = 0; i < resolvedArguments.length; i++) {
            ensureAssignable(instantiatedParameters.get(i), resolvedArguments[i], call.callee);
        }
        final var instantiatedReturn = TypeSubstitution.substitute(genericType.returnType(), substitutions);
        call.setType(instantiatedReturn);
        return instantiatedReturn;
    }

    private void unifyType(final TypeDescriptor pattern,
                           TypeDescriptor actual,
                           final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                           final Token where) {
        if (pattern.equals(actual)) return;
        if (pattern instanceof TypeParameterDescriptor parameter) {
            final var previous = substitutions.putIfAbsent(parameter, actual);
            if (previous != null && !previous.equals(actual)) {
                Zeron.resolutionError(new ResolutionError(where,
                        "Conflicting type inferences for '" + parameter.name() + "': "
                                + previous + " and " + actual + "."));
            }
            return;
        }
        if (pattern instanceof NullableDescriptor nullablePattern) {
            final var actualBase = actual instanceof NullableDescriptor nullableActual
                    ? nullableActual.baseType()
                    : actual;
            unifyType(nullablePattern.baseType(), actualBase, substitutions, where);
            return;
        }
        if (pattern instanceof ReferenceDescriptor referencePattern) {
            final var actualBase = actual instanceof ReferenceDescriptor referenceActual
                    ? referenceActual.baseType()
                    : actual;
            unifyType(referencePattern.baseType(), actualBase, substitutions, where);
            return;
        }
        if (pattern instanceof ArrayDescriptor && actual instanceof ReferenceDescriptor reference
            && reference.baseType() instanceof ArrayDescriptor) {
            actual = reference.baseType();
        }
        if (pattern instanceof ArrayDescriptor arrayPattern
                && actual instanceof ArrayDescriptor arrayActual) {
            unifyType(arrayPattern.elementType(), arrayActual.elementType(), substitutions, where);
            return;
        }
        if (pattern instanceof GenericDescriptor genericPattern
                && actual instanceof GenericDescriptor genericActual
                && genericPattern.baseType().equals(genericActual.baseType())
                && genericPattern.typeParameters().size() == genericActual.typeParameters().size()) {
            for (int i = 0; i < genericPattern.typeParameters().size(); i++) {
                unifyType(genericPattern.typeParameters().get(i),
                        genericActual.typeParameters().get(i), substitutions, where);
            }
            return;
        }
        if (pattern instanceof FunctionDescriptor functionPattern
                && actual instanceof FunctionDescriptor functionActual
                && functionPattern.arity() == functionActual.arity()) {
            for (int i = 0; i < functionPattern.arity(); i++) {
                unifyType(functionPattern.parameters().get(i), functionActual.parameters().get(i),
                        substitutions, where);
            }
            unifyType(functionPattern.returnType(), functionActual.returnType(), substitutions, where);
            return;
        }
        if (!pattern.equals(actual)
                && !(actual instanceof ReferenceDescriptor reference && pattern.equals(reference.baseType()))) {
            Zeron.resolutionError(new ResolutionError(where,
                    "Expected " + pattern + ", found " + actual + "."));
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
        final var enclosingLoopDepth = loopDepth;
        loopDepth = 0;
        beginScope();
        try {
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
        } finally {
            endScope();
            loopDepth = enclosingLoopDepth;
        }
    }

    public TypeDescriptor ensureReturns(final Token where,
                                        final TypeDescriptor expectedType,
                                        final List<Stmt> statements) {
        var currentType = expectedType;
        for (final var statement : statements) {
            if (statement instanceof Stmt.Return(Expr value)) {
                final var returnType = value instanceof Expr.Lambda lambda
                        && expectedType instanceof FunctionDescriptor functionType
                        ? resolveLambda(lambda, functionType)
                        : resolve(value);
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
        if (isContractProjection(expectedType, resolvedType)) return expectedType;
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

        public boolean isContractProjection(final TypeDescriptor expectedType,
                        final TypeDescriptor resolvedType) {
        final var expectedMutable = expectedType instanceof ReferenceDescriptor;
        final var contractType = expectedMutable
            ? ((ReferenceDescriptor) expectedType).baseType()
            : expectedType;
        final var resolvedMutable = resolvedType instanceof ReferenceDescriptor;
        if (expectedMutable && !resolvedMutable) return false;
        final var classType = resolvedMutable
            ? ((ReferenceDescriptor) resolvedType).baseType()
            : resolvedType;
        if (!(contractType instanceof NominalDescriptor contract)
            || !(classType instanceof NominalDescriptor concrete)
            || !contracts.containsKey(contract.name())) return false;
        final var declaration = classes.get(concrete.name());
        return declaration != null && declaration.contractName() != null
            && declaration.contractName().lexeme().equals(contract.name());
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
