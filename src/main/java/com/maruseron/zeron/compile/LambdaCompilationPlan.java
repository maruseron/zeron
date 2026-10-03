package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.Bind;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.*;

final class LambdaCompilationPlan {
    record FunctionAdapter(FunctionDescriptor source, FunctionDescriptor target, String name) {}
    record NullableFunctionAdapter(FunctionDescriptor source, FunctionDescriptor target, String name) {}
    record FunctionReference(String functionName, FunctionDescriptor sourceType,
                             FunctionDescriptor targetType, String helperName) {}

    private record FunctionAdapterKey(FunctionDescriptor source, FunctionDescriptor target) {}
    private record FunctionReferenceKey(String functionName, FunctionDescriptor targetType) {}

    private final SymbolTable symbols;
    private final Map<String, Stmt.ClassDecl> classes = new LinkedHashMap<>();
    private final Map<String, Stmt.ContractDecl> contracts = new LinkedHashMap<>();
    private final Map<FunctionShapeKey, FunctionDescriptor> functionShapes = new LinkedHashMap<>();
    private final Map<String, FunctionShapeKey> functionShapeNames = new HashMap<>();
    private final List<Expr.Lambda> lambdaImplementations = new ArrayList<>();
    private final Map<Expr.Lambda, String> lambdaMethodNames = new IdentityHashMap<>();
    private final Map<Expr.Lambda, List<Token>> lambdaCaptures = new IdentityHashMap<>();
    private final Map<Expr.Lambda, List<TypeDescriptor>> lambdaCaptureTypes = new IdentityHashMap<>();
    private final Map<FunctionAdapterKey, String> functionAdapterNames = new LinkedHashMap<>();
    private final Map<FunctionAdapterKey, String> nullableFunctionAdapterNames = new LinkedHashMap<>();
    private final Map<FunctionReferenceKey, FunctionReference> functionReferences = new LinkedHashMap<>();
    private final Map<Expr.Variable, FunctionReference> referencesByExpression = new IdentityHashMap<>();
    private Map<String, TypeDescriptor> activeCaptureTypes;

    LambdaCompilationPlan(final List<Stmt> declarations, final SymbolTable symbols) {
        this.symbols = symbols;
        for (final var declaration : declarations) {
            if (declaration instanceof Stmt.ClassDecl classDeclaration) {
                classes.put(classDeclaration.name().lexeme(), classDeclaration);
            } else if (declaration instanceof Stmt.ContractDecl contractDeclaration) {
                contracts.put(contractDeclaration.name().lexeme(), contractDeclaration);
            }
        }
        collectLambdaShapes(declarations);
        collectLambdaCaptures();
    }

    Map<FunctionShapeKey, FunctionDescriptor> functionShapes() {
        return Collections.unmodifiableMap(functionShapes);
    }

    List<Expr.Lambda> lambdaImplementations() {
        return List.copyOf(lambdaImplementations);
    }

    String lambdaMethodName(final Expr.Lambda lambda) {
        return lambdaMethodNames.get(lambda);
    }

    List<Token> captures(final Expr.Lambda lambda) {
        return lambdaCaptures.getOrDefault(lambda, List.of());
    }

    List<TypeDescriptor> captureTypes(final Expr.Lambda lambda) {
        return lambdaCaptureTypes.getOrDefault(lambda, List.of());
    }

    List<FunctionAdapter> adapters() {
        return functionAdapterNames.entrySet().stream()
                .map(entry -> new FunctionAdapter(entry.getKey().source(), entry.getKey().target(), entry.getValue()))
                .toList();
    }

        List<NullableFunctionAdapter> nullableAdapters() {
        return nullableFunctionAdapterNames.entrySet().stream()
            .map(entry -> new NullableFunctionAdapter(
                entry.getKey().source(), entry.getKey().target(), entry.getValue()))
            .toList();
        }

    String adapterName(final FunctionDescriptor source, final FunctionDescriptor target) {
        return functionAdapterNames.get(new FunctionAdapterKey(source, target));
    }

    String nullableAdapterName(final FunctionDescriptor source, final FunctionDescriptor target) {
        return nullableFunctionAdapterNames.get(new FunctionAdapterKey(source, target));
    }

    List<FunctionReference> functionReferences() {
        return List.copyOf(functionReferences.values());
    }

    FunctionReference functionReference(final Expr.Variable expression) {
        return referencesByExpression.get(expression);
    }

    private void collectLambdaShapes(final List<Stmt> statements) {
        for (final var statement : statements) {
            collectLambdaShapes(statement);
        }
    }

    private void collectLambdaShapes(final Stmt statement) {
        switch (statement) {
            case Stmt.ClassDecl declaration -> {
                for (final var field : declaration.fields()) collectFunctionShapes(field.type());
                for (final var method : declaration.methods()) {
                    for (final var parameter : method.typeDescriptor().parameters()) collectFunctionShapes(parameter);
                    collectFunctionShapes(method.typeDescriptor().returnType());
                    collectLambdaShapes(method.body());
                }
                for (final var constructor : declaration.namedConstructors()) {
                    for (final var parameter : constructor.typeDescriptor().parameters()) {
                        collectFunctionShapes(parameter);
                    }
                    collectFunctionShapes(constructor.typeDescriptor().returnType());
                    collectLambdaShapes(constructor.body());
                }
                collectContractBridgeAdapters(declaration);
            }
            case Stmt.ContractDecl declaration -> {
                for (final var method : declaration.methods()) {
                    for (final var parameter : method.typeDescriptor().parameters()) collectFunctionShapes(parameter);
                    collectFunctionShapes(method.typeDescriptor().returnType());
                }
            }
            case Stmt.Function(Token _, List<Token> _, FunctionDescriptor type, List<Stmt> body, boolean _) -> {
                for (final var parameter : type.parameters()) collectFunctionShapes(parameter);
                collectFunctionShapes(type.returnType());
                collectLambdaShapes(body);
            }
            case Stmt.ExternalFunction external -> {
                for (final var parameter : external.typeDescriptor().parameters()) {
                    collectFunctionShapes(parameter);
                }
                collectFunctionShapes(external.typeDescriptor().returnType());
            }
            case Stmt.Block(List<Stmt> statements) -> collectLambdaShapes(statements);
            case Stmt.If(Token _, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                collectLambdaShapes(condition);
                if (thenBranch != null) collectLambdaShapes(thenBranch);
                if (elseBranch != null) collectLambdaShapes(elseBranch);
            }
            case Stmt.Return(Expr value) -> { if (value != null) collectLambdaShapes(value); }
            case Stmt.Expression(Expr expression) -> collectLambdaShapes(expression);
            case Stmt.Var(Token _, TypeDescriptor type, Expr initializer, BindingMutability _) -> {
                collectFunctionShapes(type);
                if (initializer != null) collectLambdaShapes(initializer);
            }
            case Stmt.While(Token _, Expr condition, Stmt body) -> {
                collectLambdaShapes(condition);
                collectLambdaShapes(body);
            }
            case Stmt.For(Token _, Token _, Expr iterable, Stmt body) -> {
                collectLambdaShapes(iterable);
                collectLambdaShapes(body);
            }
            default -> {}
        }
    }

    private void collectFunctionShapes(final TypeDescriptor type) {
        switch (type) {
            case FunctionDescriptor function -> {
                final var erasedFunction = (FunctionDescriptor) TypeSubstitution.erase(function);
                final var shapeKey = FunctionShapeKey.of(erasedFunction);
                final var interfaceName = FunctionShapeNames.interfaceName(shapeKey);
                final var previousKey = functionShapeNames.putIfAbsent(interfaceName, shapeKey);
                if (previousKey != null && !previousKey.equals(shapeKey)) {
                    throw new IllegalStateException("Function shape digest collision for " + interfaceName);
                }
                functionShapes.putIfAbsent(shapeKey, erasedFunction);
                for (final var parameter : erasedFunction.parameters()) collectFunctionShapes(parameter);
                collectFunctionShapes(erasedFunction.returnType());
            }
            case ArrayDescriptor array -> collectFunctionShapes(array.elementType());
            case NullableDescriptor nullable -> collectFunctionShapes(nullable.baseType());
            case ReferenceDescriptor reference -> collectFunctionShapes(reference.baseType());
            case GenericDescriptor generic -> {
                for (final var parameter : generic.typeParameters()) collectFunctionShapes(parameter);
            }
            default -> {}
        }
    }

    private void collectLambdaShapes(final Expr expr) {
        if (expr != null) collectFunctionShapes(expr.getType());
        switch (expr) {
            case Expr.MemberCall call -> {
                collectMemberCallAdapters(call);
                collectLambdaShapes(call.receiver);
                for (final var argument : call.arguments) collectLambdaShapes(argument);
            }
            case Expr.Property property -> {
                collectFieldReadAdapter(property);
                collectLambdaShapes(property.receiver);
            }
            case Expr.PropertyAssignment assignment -> {
                collectFieldWriteAdapter(assignment);
                collectLambdaShapes(assignment.property.receiver);
                collectLambdaShapes(assignment.value);
            }
            case Expr.ArrayLiteral literal -> {
                for (final var element : literal.elements) collectLambdaShapes(element);
            }
            case Expr.Index index -> {
                collectLambdaShapes(index.array);
                collectLambdaShapes(index.index);
            }
            case Expr.IndexAssignment assignment -> {
                collectLambdaShapes(assignment.array);
                collectLambdaShapes(assignment.index);
                collectLambdaShapes(assignment.value);
            }
            case Expr.Lambda lambda -> {
                if (lambda.getType() instanceof FunctionDescriptor functionType) {
                    collectFunctionShapes(functionType);
                    if (!lambdaMethodNames.containsKey(lambda)) {
                        lambdaMethodNames.put(lambda, "$lambda$" + lambdaMethodNames.size());
                        lambdaImplementations.add(lambda);
                    }
                    collectLambdaShapes(lambda.body);
                }
            }
            case Expr.Binary binary -> {
                collectLambdaShapes(binary.left);
                collectLambdaShapes(binary.right);
            }
            case Expr.Call call -> {
                collectFunctionAdapters(call);
                for (final var argument : call.arguments) collectLambdaShapes(argument);
            }
            case Expr.Grouping grouping -> collectLambdaShapes(grouping.expression);
            case Expr.If iff -> {
                collectLambdaShapes(iff.condition);
                collectLambdaShapes(iff.thenExpr);
                collectLambdaShapes(iff.elseExpr);
            }
            case Expr.Assignment assignment -> collectLambdaShapes(assignment.value);
            case Expr.Unary unary -> collectLambdaShapes(unary.right);
            case Expr.Variable variable -> {
                if (variable.resolvedFunctionName() != null) {
                    collectFunctionReference(variable);
                } else if (variable.storedFunctionType() != null) {
                    collectFunctionAdapters(variable.storedFunctionType(), variable.getType());
                }
            }
            case Expr.Literal _ -> {}
            case null -> {}
            default -> {}
        }
    }

    private void collectFunctionReference(final Expr.Variable expression) {
        final var sourceType = (FunctionDescriptor) TypeSubstitution.erase(expression.sourceFunctionType());
        final var targetType = (FunctionDescriptor) TypeSubstitution.erase(expression.specializedFunctionType());
        final var key = new FunctionReferenceKey(expression.resolvedFunctionName(), targetType);
        final var reference = functionReferences.computeIfAbsent(key,
                _ -> new FunctionReference(expression.resolvedFunctionName(), sourceType, targetType,
                        "$functionRef$" + functionReferences.size()));
        referencesByExpression.put(expression, reference);
        for (int i = 0; i < targetType.arity(); i++) {
            collectFunctionAdapters(targetType.parameters().get(i), sourceType.parameters().get(i));
        }
        collectFunctionAdapters(sourceType.returnType(), targetType.returnType());
    }

    private void collectLambdaCaptures() {
        for (final var lambda : lambdaImplementations) {
            final var captured = collectCapturedVariables(lambda);
            lambdaCaptures.put(lambda, captured);
        }
    }

    private List<Token> collectCapturedVariables(final Expr.Lambda lambda) {
        final var captured = new ArrayList<Token>();
        final var seen = new HashSet<String>();
        final var locals = new HashSet<String>();
        for (final var param : lambda.params) {
            locals.add(param.lexeme());
        }
        final var previousCaptureTypes = activeCaptureTypes;
        final var captureTypes = new LinkedHashMap<String, TypeDescriptor>();
        activeCaptureTypes = captureTypes;
        try {
            collectCapturedVariables(lambda.body, locals, captured, seen);
        } finally {
            activeCaptureTypes = previousCaptureTypes;
        }
        lambdaCaptureTypes.put(lambda, captured.stream()
                .map(token -> captureTypes.get(token.lexeme()))
                .toList());
        return captured;
    }

    private void collectCapturedVariables(final List<Stmt> statements,
                                         final Set<String> locals,
                                         final List<Token> captured,
                                         final Set<String> seen) {
        for (final var statement : statements) {
            collectCapturedVariables(statement, locals, captured, seen);
        }
    }

    private void collectCapturedVariables(final Stmt statement,
                                         final Set<String> localNames,
                                         final List<Token> captured,
                                         final Set<String> seen) {
        switch (statement) {
            case Stmt.Block(List<Stmt> block) -> {
                final var nestedNames = new HashSet<>(localNames);
                collectCapturedVariables(block, nestedNames, captured, seen);
            }
            case Stmt.If(Token _, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                collectCapturedVariables(condition, localNames, captured, seen);
                if (thenBranch != null) collectCapturedVariables(thenBranch, new HashSet<>(localNames), captured, seen);
                if (elseBranch != null) collectCapturedVariables(elseBranch, new HashSet<>(localNames), captured, seen);
            }
            case Stmt.For(Token iterationBind, Token _, Expr iterable, Stmt body) -> {
                final var nestedNames = new HashSet<>(localNames);
                nestedNames.add(iterationBind.lexeme());
                collectCapturedVariables(iterable, nestedNames, captured, seen);
                collectCapturedVariables(body, nestedNames, captured, seen);
            }
            case Stmt.Return(Expr value) -> {
                if (value != null) collectCapturedVariables(value, localNames, captured, seen);
            }
            case Stmt.Expression(Expr expression) -> collectCapturedVariables(expression, localNames, captured, seen);
            case Stmt.Var(Token name, TypeDescriptor _, Expr initializer, BindingMutability _) -> {
                final var nestedNames = new HashSet<>(localNames);
                nestedNames.add(name.lexeme());
                if (initializer != null) collectCapturedVariables(initializer, nestedNames, captured, seen);
            }
            case Stmt.While(Token _, Expr condition, Stmt body) -> {
                collectCapturedVariables(condition, localNames, captured, seen);
                collectCapturedVariables(body, new HashSet<>(localNames), captured, seen);
            }
            case Stmt.Function(Token _, List<Token> parameters, FunctionDescriptor _, List<Stmt> body, boolean _) -> {
                final var nestedNames = new HashSet<>(localNames);
                for (final var param : parameters) nestedNames.add(param.lexeme());
                collectCapturedVariables(body, nestedNames, captured, seen);
            }
            default -> {}
        }
    }

    private void collectCapturedVariables(final Expr expr,
                                         final Set<String> localNames,
                                         final List<Token> captured,
                                         final Set<String> seen) {
        if (expr == null) return;
        switch (expr) {
            case Expr.MemberCall call -> {
                collectCapturedVariables(call.receiver, localNames, captured, seen);
                for (final var argument : call.arguments) {
                    collectCapturedVariables(argument, localNames, captured, seen);
                }
            }
            case Expr.Property property ->
                    collectCapturedVariables(property.receiver, localNames, captured, seen);
            case Expr.PropertyAssignment assignment -> {
                collectCapturedVariables(assignment.property.receiver, localNames, captured, seen);
                collectCapturedVariables(assignment.value, localNames, captured, seen);
            }
            case Expr.ArrayLiteral literal -> {
                for (final var element : literal.elements) collectCapturedVariables(element, localNames, captured, seen);
            }
            case Expr.Index index -> {
                collectCapturedVariables(index.array, localNames, captured, seen);
                collectCapturedVariables(index.index, localNames, captured, seen);
            }
            case Expr.IndexAssignment assignment -> {
                collectCapturedVariables(assignment.array, localNames, captured, seen);
                collectCapturedVariables(assignment.index, localNames, captured, seen);
                collectCapturedVariables(assignment.value, localNames, captured, seen);
            }
            case Expr.Assignment assignment -> collectCapturedVariables(assignment.value, localNames, captured, seen);
            case Expr.Binary binary -> {
                collectCapturedVariables(binary.left, localNames, captured, seen);
                collectCapturedVariables(binary.right, localNames, captured, seen);
            }
            case Expr.Call call -> {
                for (final var argument : call.arguments) {
                    collectCapturedVariables(argument, localNames, captured, seen);
                }
            }
            case Expr.Grouping grouping -> collectCapturedVariables(grouping.expression, localNames, captured, seen);
            case Expr.If iff -> {
                collectCapturedVariables(iff.condition, localNames, captured, seen);
                collectCapturedVariables(iff.thenExpr, localNames, captured, seen);
                collectCapturedVariables(iff.elseExpr, localNames, captured, seen);
            }
            case Expr.Lambda lambda -> {
                final var nestedNames = new HashSet<>(localNames);
                for (final var param : lambda.params) nestedNames.add(param.lexeme());
                collectCapturedVariables(lambda.body, nestedNames, captured, seen);
            }
            case Expr.Literal _ -> {}
            case Expr.Logical logical -> {
                collectCapturedVariables(logical.left, localNames, captured, seen);
                collectCapturedVariables(logical.right, localNames, captured, seen);
            }
            case Expr.Unary unary -> collectCapturedVariables(unary.right, localNames, captured, seen);
            case Expr.Variable variable -> {
                final var name = variable.name.lexeme();
                if (localNames.contains(name)) return;
                if ((symbols.containsSymbol(variable.name) || symbols.containsAnySymbol(variable.name))
                        && lookupCapture(variable.name).lvt() != SymbolTable.GLOBAL
                        && !seen.contains(name)) {
                    seen.add(name);
                    captured.add(variable.name);
                    activeCaptureTypes.putIfAbsent(name, variable.getType());
                }
            }
            default -> {}
        }
    }

    private void collectFunctionAdapters(final Expr.Call call) {
        final var genericType = call.genericFunctionType();
        if (genericType == null) return;

        for (int i = 0; i < call.arguments.size(); i++) {
            final var expected = genericType.parameters().get(i);
            final var actual = call.arguments.get(i).getType();
            if (TypeSubstitution.containsTypeParameter(expected)) {
                collectFunctionAdapters(actual, TypeSubstitution.erase(expected));
            }
        }

        if (TypeSubstitution.containsTypeParameter(genericType.returnType())) {
            collectFunctionAdapters(TypeSubstitution.erase(genericType.returnType()), call.getType());
        }
    }

    private void collectMemberCallAdapters(final Expr.MemberCall call) {
        if (call.name.lexeme().equals("new") && call.receiver instanceof Expr.Variable typeName) {
            final var declaration = classes.get(typeName.name.lexeme());
            if (declaration == null) return;
            for (int i = 0; i < Math.min(call.arguments.size(), declaration.fields().size()); i++) {
                collectFunctionAdapters(call.arguments.get(i).getType(),
                        TypeSubstitution.erase(declaration.fields().get(i).type()));
            }
            return;
        }

        final var ownerName = nominalName(call.receiver.getType());
        final var classDeclaration = classes.get(ownerName);
        final var contractDeclaration = contracts.get(ownerName);
        final var classMethod = classDeclaration == null
            ? null
            : classMethod(classDeclaration, call.name.lexeme());
        final var contractMethod = classDeclaration == null
            ? contractMethod(contractDeclaration, call.name.lexeme())
            : null;
        if (classMethod == null && contractMethod == null) return;
        final var methodParameters = classMethod != null
            ? classMethod.typeDescriptor().parameters()
            : contractMethod.typeDescriptor().parameters();
        final var methodReturnType = classMethod != null
            ? classMethod.typeDescriptor().returnType()
            : contractMethod.typeDescriptor().returnType();

        for (int i = 0; i < Math.min(call.arguments.size(), methodParameters.size()); i++) {
            collectFunctionAdapters(call.arguments.get(i).getType(),
                TypeSubstitution.erase(methodParameters.get(i)));
        }
        if (call.resolvedDescriptor() != null) {
            collectFunctionAdapters(TypeSubstitution.erase(methodReturnType), call.resolvedDescriptor().returnType());
        }
    }

    private void collectFieldReadAdapter(final Expr.Property property) {
        final var declaration = classes.get(nominalName(property.receiver.getType()));
        if (declaration == null) return;
        final var field = field(declaration, property.name.lexeme());
        if (field != null) {
            collectFunctionAdapters(TypeSubstitution.erase(field.type()), property.getType());
        }
    }

    private void collectFieldWriteAdapter(final Expr.PropertyAssignment assignment) {
        final var property = assignment.property;
        final var declaration = classes.get(nominalName(property.receiver.getType()));
        if (declaration == null) return;
        final var field = field(declaration, property.name.lexeme());
        if (field != null) {
            collectFunctionAdapters(assignment.value.getType(), TypeSubstitution.erase(field.type()));
        }
    }

    private void collectContractBridgeAdapters(final Stmt.ClassDecl declaration) {
        for (final var contractUse : declaration.contractUses()) {
            final var contract = contracts.get(contractUse.name().lexeme());
            if (contract == null) continue;
            for (final var required : contract.methods()) {
                final var implementation = declaration.methods().stream()
                        .filter(method -> method.name().lexeme().equals(required.name().lexeme()))
                        .findFirst()
                        .orElse(null);
                if (implementation == null) continue;
                for (int i = 0; i < Math.min(required.typeDescriptor().arity(),
                        implementation.typeDescriptor().arity()); i++) {
                    collectFunctionAdapters(
                            TypeSubstitution.erase(required.typeDescriptor().parameters().get(i)),
                            implementation.typeDescriptor().parameters().get(i));
                }
                collectFunctionAdapters(implementation.typeDescriptor().returnType(),
                        TypeSubstitution.erase(required.typeDescriptor().returnType()));
            }
        }
    }

    private Stmt.Method classMethod(final Stmt.ClassDecl declaration, final String name) {
        return declaration.methods().stream()
                .filter(method -> method.name().lexeme().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Stmt.ContractMethod contractMethod(final Stmt.ContractDecl declaration, final String name) {
        if (declaration == null) return null;
        return declaration.methods().stream()
                .filter(method -> method.name().lexeme().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Stmt.Field field(final Stmt.ClassDecl declaration, final String name) {
        return declaration.fields().stream()
                .filter(candidate -> candidate.name().lexeme().equals(name))
                .findFirst()
                .orElse(null);
    }

    private String nominalName(final TypeDescriptor type) {
        var baseType = type instanceof ReferenceDescriptor reference ? reference.baseType() : type;
        if (baseType instanceof GenericDescriptor generic) baseType = generic.baseType();
        return baseType instanceof NominalDescriptor nominal ? nominal.name() : "";
    }

    private void collectFunctionAdapters(final TypeDescriptor sourceType,
                                         final TypeDescriptor targetType) {
        final var source = functionView(sourceType);
        final var target = functionView(targetType);
        if (source == null || target == null || !registerFunctionAdapter(source, target)) return;

        if (isNullableFunctionView(sourceType) && isNullableFunctionView(targetType)) {
            final var erasedSource = (FunctionDescriptor) TypeSubstitution.erase(source);
            final var erasedTarget = (FunctionDescriptor) TypeSubstitution.erase(target);
            nullableFunctionAdapterNames.computeIfAbsent(
                    new FunctionAdapterKey(erasedSource, erasedTarget),
                    _ -> "$nullableAdapter$" + nullableFunctionAdapterNames.size());
        }

        for (int i = 0; i < target.arity(); i++) {
            collectFunctionAdapters(target.parameters().get(i), source.parameters().get(i));
        }
        collectFunctionAdapters(source.returnType(), target.returnType());
    }

    private FunctionDescriptor functionView(final TypeDescriptor type) {
        return switch (type) {
            case FunctionDescriptor function -> function;
            case NullableDescriptor nullable -> functionView(nullable.baseType());
            case ReferenceDescriptor reference -> functionView(reference.baseType());
            default -> null;
        };
    }

    private boolean isNullableFunctionView(final TypeDescriptor type) {
        return switch (type) {
            case NullableDescriptor nullable -> functionView(nullable.baseType()) != null
                    || isNullableFunctionView(nullable.baseType());
            case ReferenceDescriptor reference -> isNullableFunctionView(reference.baseType());
            default -> false;
        };
    }

    private boolean registerFunctionAdapter(final FunctionDescriptor source,
                                            final FunctionDescriptor target) {
        final var erasedSource = (FunctionDescriptor) TypeSubstitution.erase(source);
        final var erasedTarget = (FunctionDescriptor) TypeSubstitution.erase(target);
        if (TypeDescriptor.toJavaClassDesc(erasedSource).equals(TypeDescriptor.toJavaClassDesc(erasedTarget))) {
            return false;
        }
        functionAdapterNames.computeIfAbsent(new FunctionAdapterKey(erasedSource, erasedTarget),
                _ -> "$adapter$" + functionAdapterNames.size());
        return true;
    }

    private Bind lookupCapture(final Token name) {
        return symbols.containsSymbol(name) ? symbols.getSymbol(name) : symbols.getAnySymbol(name);
    }
}