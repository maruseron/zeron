package com.maruseron.zeron.analize;

import com.maruseron.zeron.IntRangeLiteral;
import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.*;

public final class Resolver {
    private record FlowFact(boolean mayBeNull, Set<TypeDescriptor> nonNullAlternatives) {
        FlowFact {
            if (nonNullAlternatives != null) nonNullAlternatives = Set.copyOf(nonNullAlternatives);
        }
    }
    private static final class FlowState {
        private final IdentityHashMap<Token, FlowFact> facts = new IdentityHashMap<>();
        private boolean reachable = true;

        FlowState copy() {
            final var copy = new FlowState();
            copy.facts.putAll(facts);
            copy.reachable = reachable;
            return copy;
        }

        static FlowState unreachable() {
            final var state = new FlowState();
            state.reachable = false;
            return state;
        }

        boolean isReachable() {
            return reachable;
        }

        void markUnreachable() {
            reachable = false;
        }

        FlowFact get(final Token bindingName) {
            return facts.get(bindingName);
        }

        void put(final Token bindingName, final FlowFact fact) {
            facts.put(bindingName, fact);
        }

        void remove(final Token bindingName) {
            facts.remove(bindingName);
        }

        static FlowState join(final FlowState left, final FlowState right) {
            if (!left.reachable) return right.copy();
            if (!right.reachable) return left.copy();
            final var joined = new FlowState();
            for (final var entry : left.facts.entrySet()) {
                final var rightFact = right.facts.get(entry.getKey());
                if (rightFact == null) continue;
                final var leftFact = entry.getValue();
                final Set<TypeDescriptor> alternatives;
                if (leftFact.nonNullAlternatives() == null || rightFact.nonNullAlternatives() == null) {
                    alternatives = null;
                } else {
                    final var union = new LinkedHashSet<>(leftFact.nonNullAlternatives());
                    union.addAll(rightFact.nonNullAlternatives());
                    alternatives = union;
                }
                joined.put(entry.getKey(), new FlowFact(
                        leftFact.mayBeNull() || rightFact.mayBeNull(), alternatives));
            }
            return joined;
        }
    }

    private record ConditionFlows(FlowState whenTrue, FlowState whenFalse) {}

    private static final class LoopFlow {
        private final List<FlowState> breakStates = new ArrayList<>();
    }

    // this table stores every name related to a type to avoid name collisions
    public final SymbolTable symbols = new SymbolTable();
    public final Set<String> types   = new HashSet<>();
    private final Map<String, Stmt.ClassDecl> classes = new LinkedHashMap<>();
    private final Map<String, Stmt.ContractDecl> contracts = new LinkedHashMap<>();
    private final TypeCompatibility typeCompatibility = new TypeCompatibility(classes, contracts);
    private final Deque<Set<Token>> flowWriteScopes = new ArrayDeque<>();
    private final Deque<LoopFlow> loopFlows = new ArrayDeque<>();
    private final Deque<TypeDescriptor> expectedReturnTypes = new ArrayDeque<>();
    private FlowState flowState = new FlowState();
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

        Zeron.debug("resolution finished successfully with symbol table: \n" + symbols);
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
        final var builtin = Set.of("Never", "Any", "Unit", "Int", "Float", "Boolean", "String", "Array");
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

        final var contractNames = new HashSet<String>();
        for (final var contractUse : declaration.contractUses()) {
            final var contractName = contractUse.name();
            if (!contractNames.add(contractName.lexeme())) {
                Zeron.resolutionError(new ResolutionError(contractName, "Duplicate contract conformance."));
            }
            final var contract = contracts.get(contractName.lexeme());
            if (contract == null) {
                Zeron.resolutionError(new ResolutionError(contractName, "Unknown contract."));
            }
            if (contract.typeParameters().size() != contractUse.typeArguments().size()) {
                Zeron.resolutionError(new ResolutionError(contractName,
                        "Expected " + contract.typeParameters().size() + " contract type arguments, found "
                                + contractUse.typeArguments().size() + "."));
            }
            contractUse.typeArguments().forEach(type -> validateType(type, contractName));
        }

        final var previousClass = currentClassName;
        currentClassName = declaration.name().lexeme();
        try {
            for (final var method : declaration.methods()) resolveMethod(declaration, method);
            for (final var constructor : declaration.namedConstructors()) {
                resolveNamedConstructor(constructor);
            }
            for (final var contractUse : declaration.contractUses()) {
                checkConformance(declaration, contractUse, contracts.get(contractUse.name().lexeme()));
            }
        } finally {
            currentClassName = previousClass;
        }
    }

    private void resolveMethod(final Stmt.ClassDecl owner, final Stmt.Method method) {
        beginScope();
    expectedReturnTypes.push(method.typeDescriptor().returnType());
        final var enclosingFlow = flowState;
        flowState = new FlowState();
        final var thisToken = new Token(TokenType.THIS, "this", null, method.name().line());
        final TypeDescriptor ownerType = classType(owner);
        final TypeDescriptor thisType = method.isMutating()
            ? new ReferenceDescriptor(ownerType)
            : ownerType;
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
            expectedReturnTypes.pop();
            endScope();
            flowState = enclosingFlow;
        }
    }

    private void resolveNamedConstructor(final Stmt.NamedConstructor constructor) {
        beginScope();
        final var enclosingFlow = flowState;
        final var enclosingLoopDepth = loopDepth;
        flowState = new FlowState();
        loopDepth = 0;
        try {
            expectedReturnTypes.push(constructor.typeDescriptor().returnType());
            for (int i = 0; i < constructor.parameters().size(); i++) {
                final var parameter = constructor.parameters().get(i);
                declare(SYNTHETIC_VAR, parameter, constructor.typeDescriptor().parameters().get(i),
                        BindingMutability.IMMUTABLE);
                define(parameter);
            }
            resolveStmts(constructor.body());
            if (flowState.isReachable()) {
                Zeron.resolutionError(new ResolutionError(constructor.name(),
                        "Named constructor must return an instance on every normal path."));
            }
        } finally {
            if (!expectedReturnTypes.isEmpty()) expectedReturnTypes.pop();
            endScope();
            loopDepth = enclosingLoopDepth;
            flowState = enclosingFlow;
        }
    }

    private void checkConformance(final Stmt.ClassDecl declaration,
                                  final Stmt.ContractUse contractUse,
                                  final Stmt.ContractDecl contract) {
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < contract.typeParameters().size(); i++) {
            substitutions.put(contract.typeParameters().get(i), contractUse.typeArguments().get(i));
        }
        for (final var required : contract.methods()) {
            final var implementation = declaration.methods().stream()
                    .filter(method -> method.name().lexeme().equals(required.name().lexeme()))
                    .findFirst()
                    .orElse(null);
                final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                    required.typeDescriptor(), substitutions);
                if (implementation == null || !implementation.isPublic()
                    || implementation.isMutating() != required.isMutating()
                    || !implementation.typeDescriptor().equals(requiredType)) {
                Zeron.resolutionError(new ResolutionError(contractUse.name(),
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
                final var classDeclaration = classes.get(nominal.name());
                final var contractDeclaration = contracts.get(nominal.name());
                final var arity = classDeclaration != null ? classDeclaration.typeParameters().size()
                        : contractDeclaration != null ? contractDeclaration.typeParameters().size() : 0;
                if (arity != 0) {
                    Zeron.resolutionError(new ResolutionError(where,
                            "Type '" + nominal.name() + "' requires " + arity + " type arguments."));
                }
            }
            case ArrayDescriptor array -> validateType(array.elementType(), where);
            case NullableDescriptor nullable -> validateType(nullable.baseType(), where);
            case ReferenceDescriptor reference -> validateType(reference.baseType(), where);
            case FunctionDescriptor function -> validateFunctionTypes(function, where);
            case GenericDescriptor generic -> {
                final var name = generic.baseType().name();
                final var classDeclaration = classes.get(name);
                final var contractDeclaration = contracts.get(name);
                if (classDeclaration == null && contractDeclaration == null) {
                    Zeron.resolutionError(new ResolutionError(where, "Unknown generic type '" + name + "'."));
                }
                final var arity = classDeclaration != null ? classDeclaration.typeParameters().size()
                        : contractDeclaration.typeParameters().size();
                if (arity == 0 || arity != generic.typeParameters().size()) {
                    Zeron.resolutionError(new ResolutionError(where,
                            "Type '" + name + "' expects " + arity + " type arguments, found "
                                    + generic.typeParameters().size() + "."));
                }
                generic.typeParameters().forEach(parameter -> validateType(parameter, where));
            }
            default -> {}
        }
    }

    private TypeDescriptor classType(final Stmt.ClassDecl declaration) {
        if (declaration.typeParameters().isEmpty()) return TypeDescriptor.of(declaration.name().lexeme());
        return TypeDescriptor.genericOf(TypeDescriptor.ofName(declaration.name().lexeme()),
                declaration.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
    }

    private Map<TypeParameterDescriptor, TypeDescriptor> substitutionsFor(
            final List<TypeParameterDescriptor> parameters, final TypeDescriptor receiverType) {
        final var baseType = receiverType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : receiverType;
        if (!(baseType instanceof GenericDescriptor generic)
                || parameters.size() != generic.typeParameters().size()) return Map.of();
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < parameters.size(); i++) {
            substitutions.put(parameters.get(i), generic.typeParameters().get(i));
        }
        return substitutions;
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
                if (flowState.isReachable()) loopFlows.peek().breakStates.add(flowState.copy());
                flowState.markUnreachable();
            }
            case Stmt.Continue(Token keyword) -> {
                if (loopDepth == 0) {
                    Zeron.resolutionError(new ResolutionError(keyword,
                            "Can only continue inside of a loop."));
                }
                flowState.markUnreachable();
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
                final var elementType = iterable instanceof Expr.Literal literal
                        && literal.value instanceof IntRangeLiteral
                        ? TypeDescriptor.ofInt()
                        : ensureIterable(iterableType, iterationBind);
                declare(SYNTHETIC_VAR, iterationBind, elementType, BindingMutability.IMMUTABLE);
                define(iterationBind);
                final var incoming = flowState.copy();
                final var loopWriteNames = new HashSet<String>();
                final var shadowedNames = new HashSet<String>();
                shadowedNames.add(iterationBind.lexeme());
                collectLoopWrites(body, loopWriteNames, shadowedNames);
                final var loopWrites = resolveLoopWriteBindings(loopWriteNames);
                final var headerState = incoming.copy();
                discardLoopWriteFacts(headerState, loopWrites);
                final var loopFlow = new LoopFlow();
                loopFlows.push(loopFlow);
                loopDepth++;
                try {
                    flowState = headerState;
                    resolve(body);
                } finally {
                    loopDepth--;
                    loopFlows.pop();
                }
                final var exits = incoming.copy();
                discardLoopWriteFacts(exits, loopWrites);
                exits.remove(iterationBind);
                var exitState = exits;
                for (final var breakState : loopFlow.breakStates) {
                    final var reachableBreak = breakState.copy();
                    discardLoopWriteFacts(reachableBreak, loopWrites);
                    reachableBreak.remove(iterationBind);
                    exitState = FlowState.join(exitState, reachableBreak);
                }
                flowState = exitState;
                endScope();
            }
            case Stmt.If(Token keyword, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                final var incoming = flowState.copy();
                final var conditionFlows = resolveCondition(condition, incoming, keyword);
                flowState = conditionFlows.whenTrue().copy();
                resolve(thenBranch);
                final var thenFlow = flowState.copy();

                final FlowState elseFlow;
                if (elseBranch == null) {
                    elseFlow = conditionFlows.whenFalse();
                } else {
                    flowState = conditionFlows.whenFalse().copy();
                    resolve(elseBranch);
                    elseFlow = flowState.copy();
                }

                flowState = FlowState.join(thenFlow, elseFlow);
            }
            case Stmt.Print(Expr expression) -> {
                resolve(expression);
            }
            case Stmt.Return(Expr value) -> {
                final var expectedReturnType = expectedReturnTypes.peek();
                final var returnType = value == null
                        ? TypeDescriptor.ofUnit()
                        : value instanceof Expr.Lambda lambda
                                && expectedReturnType instanceof FunctionDescriptor functionType
                                ? resolveLambda(lambda, functionType)
                                : resolve(value);
                if (!expectedReturnTypes.isEmpty()) {
                    ensureAssignable(expectedReturnTypes.peek(), returnType, SYNTHETIC_IDENTIFIER);
                }
                flowState.markUnreachable();
            }
            case Stmt.Var var -> {
                Zeron.debug("resolving variable " + var.name().lexeme() + " " + var.type());
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

                if (!(var.type() instanceof InferDescriptor)) {
                    Zeron.debug(" resolved variable " + var.name().lexeme() + " "
                            + var.type() + " from explicit type");
                } else {
                    Zeron.debug(" resolved variable " + var.name().lexeme() + " "
                            + resolvedType + " from initializer");
                }

                define(var.name());
            }
            case Stmt.While(Token keyword, Expr condition, Stmt body) -> {
                final var incoming = flowState.copy();
                final var loopWriteNames = new HashSet<String>();
                collectLoopWrites(condition, loopWriteNames, new HashSet<>());
                collectLoopWrites(body, loopWriteNames, new HashSet<>());
                final var loopWrites = resolveLoopWriteBindings(loopWriteNames);
                final var headerState = incoming.copy();
                discardLoopWriteFacts(headerState, loopWrites);
                final var loopFlow = new LoopFlow();
                loopFlows.push(loopFlow);
                loopDepth++;
                final ConditionFlows conditionFlows;
                try {
                    conditionFlows = resolveCondition(condition, headerState, keyword);
                    flowState = conditionFlows.whenTrue().copy();
                    resolve(body);
                } finally {
                    loopDepth--;
                    loopFlows.pop();
                }
                final var normalExit = conditionFlows.whenFalse().copy();
                discardLoopWriteFacts(normalExit, loopWrites);
                var exitState = normalExit;
                for (final var breakState : loopFlow.breakStates) {
                    final var reachableBreak = breakState.copy();
                    discardLoopWriteFacts(reachableBreak, loopWrites);
                    exitState = FlowState.join(exitState, reachableBreak);
                }
                flowState = exitState;
            }
        }
    }

    public void resolveStmts(final List<Stmt> statements) {
        for (final var statement : statements) {
            resolve(statement);
        }
    }

    private void ensureImmutableCaptures(final Expr.Lambda lambda) {
        new LambdaCaptureValidator(symbols).validate(lambda);
    }

    private TypeDescriptor resolve(Expr expr) {
        return switch (expr) {
            case Expr.MemberCall call -> resolveMemberCall(call);
            case Expr.PropertyAssignment assignment -> {
                resolveProperty(assignment.property);
                final var receiverType = assignment.property.receiver.getType();
                final var ownerName = className(receiverType);
                final var field = findField(ownerName, assignment.property.name);
                if (!(receiverType instanceof ReferenceDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(assignment.property.name,
                            "Field assignment requires a mutable reference."));
                }
                final var owner = classes.get(ownerName);
                final var expectedType = owner == null ? field.type()
                        : TypeSubstitution.substitute(field.type(),
                                substitutionsFor(owner.typeParameters(), receiverType));
                ensureAssignable(expectedType,
                    resolveArgument(assignment.value, expectedType),
                    assignment.property.name);
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

                if (flowState.isReachable()) {
                    flowState.remove(binding.name());
                    for (final var writeScope : flowWriteScopes) writeScope.add(binding.name());
                }
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
                if ((binary.operator.type() == TokenType.EQUAL_EQUAL
                        || binary.operator.type() == TokenType.BANG_EQUAL)
                        && (isNullLiteral(binary.left) || isNullLiteral(binary.right))) {
                    final var leftType = resolve(binary.left);
                    final var rightType = resolve(binary.right);
                    final var comparedType = isNullLiteral(binary.left) ? rightType : leftType;
                    if (!isNullLiteral(binary.left) && !isNullLiteral(binary.right)
                            || isPrimitive(comparedType) && !comparedType.isNullable()) {
                        Zeron.resolutionError(new ResolutionError(binary.operator,
                                "Null comparisons require a nullable or reference value."));
                    }
                    binary.setType(TypeDescriptor.ofBoolean());
                    yield TypeDescriptor.ofBoolean();
                }
                final var leftType = resolve(binary.left);
                final var rightType = resolve(binary.right);
                final var refinedLeftType = refineInferredType(binary.left, leftType, rightType);
                final var refinedRightType = refineInferredType(binary.right, rightType, leftType);
                Zeron.debug("resolving binary   " + refinedLeftType + " "
                    + binary.operator.lexeme() + " " + refinedRightType);
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
                    } else if (symbol instanceof ReferenceDescriptor reference
                            && reference.baseType() instanceof FunctionDescriptor f) {
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
                Zeron.debug("resolving call     " + call.callee.lexeme() + parameters
                    + " -> " + descriptor.returnType());

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
                final var incoming = flowState.copy();
                final var conditionFlows = resolveCondition(iff.condition, incoming, iff.paren);
                flowState = conditionFlows.whenTrue().copy();
                final var thenType = resolve(iff.thenExpr);
                final var thenFlow = flowState.copy();
                flowState = conditionFlows.whenFalse().copy();
                final var elseType = resolve(iff.elseExpr);
                final var elseFlow = flowState.copy();
                final var commonType = ensureCommonParent(iff.paren, thenType, elseType);
                flowState = FlowState.join(thenFlow, elseFlow);
                iff.setType(commonType);
                yield commonType;
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
            case Expr.TypeTest test -> {
                yield resolveTypeTest(test);
            }
            case Expr.Cast cast -> {
                final var sourceType = resolve(cast.value);
                validateRuntimeTestTarget(cast.targetType, cast.operator);
                if (!typeCompatibility.canTypeTest(sourceType, cast.targetType)) {
                    Zeron.resolutionError(new ResolutionError(cast.operator,
                            "Cast is impossible between " + sourceType + " and " + cast.targetType + "."));
                }
                if (!cast.safe && (sourceType.isNullable() || sourceType instanceof NullDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(cast.operator,
                            "A checked cast from a nullable value requires a non-null flow proof."));
                }
                final var resultType = cast.safe ? cast.targetType.toNullable() : cast.targetType;
                cast.setType(resultType);
                yield resultType;
            }
            case Expr.Logical logical -> {
                final var incoming = flowState.copy();
                final Set<Token> writes = Collections.newSetFromMap(new IdentityHashMap<>());
                flowWriteScopes.push(writes);
                try {
                    resolveCondition(logical, incoming, logical.operator);
                } finally {
                    flowWriteScopes.pop();
                }
                flowState = incoming;
                for (final var written : writes) flowState.remove(written);
                logical.setType(TypeDescriptor.ofBoolean());
                yield TypeDescriptor.ofBoolean();
            }
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
                if (unary.operator.type() != TokenType.MINUS
                        && unary.operator.type() != TokenType.PLUS) {
                    Zeron.resolutionError(new ResolutionError(unary.operator,
                            "The 'typeof' operator is not implemented."));
                }
                if (operandType instanceof IntDescriptor || operandType instanceof FloatDescriptor) {
                    unary.setType(operandType);
                    yield operandType;
                }
                Zeron.resolutionError(new ResolutionError(unary.operator,
                        "Unary '+' and '-' require a non-null Int or Float operand."));
                yield TypeDescriptor.ofInfer();
            }
            case Expr.Variable variable -> {
                final var name = variable.name;
                Zeron.debug("resolving lookup   " + name.lexeme());
                if (symbols.containsSymbol(name) && !symbols.getSymbol(name).isInit()) {
                    Zeron.resolutionError(new ResolutionError(name,
                            "Can't read local variable in its own initializer."));
                }

                final var resolvedType = effectiveType(symbols.getSymbol(name));
                variable.setType(resolvedType);
                Zeron.debug(" -> " + resolvedType);
                yield resolvedType;
            }
        };
    }

    private TypeDescriptor resolveTypeTest(final Expr.TypeTest test) {
        final var sourceType = resolve(test.value);
        validateRuntimeTestTarget(test.targetType, test.operator);
        if (!typeCompatibility.canTypeTest(sourceType, test.targetType)) {
            Zeron.resolutionError(new ResolutionError(test.operator,
                    "Type test is impossible between " + sourceType + " and " + test.targetType + "."));
        }
        test.setType(TypeDescriptor.ofBoolean());
        return TypeDescriptor.ofBoolean();
    }

    private void validateRuntimeTestTarget(final TypeDescriptor targetType, final Token where) {
        validateType(targetType, where);
        if (targetType instanceof NullableDescriptor || targetType instanceof ReferenceDescriptor
                || targetType instanceof ArrayDescriptor || targetType instanceof GenericDescriptor
                || targetType instanceof FunctionDescriptor || targetType instanceof TypeParameterDescriptor
                || targetType instanceof NullDescriptor || targetType instanceof NeverDescriptor
                || targetType instanceof InferDescriptor) {
            Zeron.resolutionError(new ResolutionError(where,
                    "This type cannot be used as a runtime type-test target."));
        }
    }

    private ConditionFlows resolveCondition(final Expr expression,
                                           final FlowState incoming,
                                           final Token where) {
        final var enclosingFlow = flowState;
        try {
            if (expression instanceof Expr.Literal literal && literal.value instanceof Boolean value) {
                final var whenTrue = incoming.copy();
                final var whenFalse = incoming.copy();
                if (value) whenFalse.markUnreachable();
                else whenTrue.markUnreachable();
                return new ConditionFlows(whenTrue, whenFalse);
            }
            if (expression instanceof Expr.Grouping grouping) {
                final var result = resolveCondition(grouping.expression, incoming, where);
                grouping.setType(TypeDescriptor.ofBoolean());
                return result;
            }
            if (expression instanceof Expr.Unary unary
                    && unary.operator.type() == TokenType.NOT) {
                final var result = resolveCondition(unary.right, incoming, unary.operator);
                unary.setType(TypeDescriptor.ofBoolean());
                return new ConditionFlows(result.whenFalse(), result.whenTrue());
            }
            if (expression instanceof Expr.Logical logical) {
                final var left = resolveCondition(logical.left, incoming, logical.operator);
                final ConditionFlows result;
                if (logical.operator.type() == TokenType.AND) {
                    final var right = resolveCondition(logical.right, left.whenTrue(), logical.operator);
                    result = new ConditionFlows(right.whenTrue(),
                            FlowState.join(left.whenFalse(), right.whenFalse()));
                } else {
                    final var right = resolveCondition(logical.right, left.whenFalse(), logical.operator);
                    result = new ConditionFlows(FlowState.join(left.whenTrue(), right.whenTrue()),
                            right.whenFalse());
                }
                logical.setType(TypeDescriptor.ofBoolean());
                return result;
            }

            flowState = incoming.copy();
            ensureBoolean(resolve(expression), where);
            final var evaluated = flowState.copy();
            final var whenTrue = evaluated.copy();
            final var whenFalse = evaluated.copy();

            if (expression instanceof Expr.TypeTest test) {
                final var variable = directVariable(test.value);
                if (variable != null && isRefinable(variable.name)) {
                    whenTrue.put(symbols.getSymbol(variable.name).name(),
                            refineTypeFact(symbols.getSymbol(variable.name).type(),
                                    evaluated.get(symbols.getSymbol(variable.name).name()),
                                    test.targetType));
                }
            } else if (expression instanceof Expr.Binary binary
                    && isNullComparison(binary)) {
                final var checked = directVariable(isNullLiteral(binary.left) ? binary.right : binary.left);
                if (checked != null && isRefinable(checked.name)) {
                    final var binding = symbols.getSymbol(checked.name);
                    final var nullFact = new FlowFact(true, Set.of());
                    final var nonNullFact = nonNullFact(binding.type());
                    final var equalsNull = binary.operator.type() == TokenType.EQUAL_EQUAL;
                    whenTrue.put(binding.name(), equalsNull ? nullFact : nonNullFact);
                    whenFalse.put(binding.name(), equalsNull ? nonNullFact : nullFact);
                }
            }
            return new ConditionFlows(whenTrue, whenFalse);
        } finally {
            flowState = enclosingFlow;
        }
    }

    private FlowFact refineTypeFact(final TypeDescriptor declaredType,
                                   final FlowFact existing,
                                   final TypeDescriptor targetType) {
        final Set<TypeDescriptor> currentAlternatives;
        if (existing != null) {
            currentAlternatives = existing.nonNullAlternatives();
        } else {
            currentAlternatives = initialNonNullAlternatives(declaredType);
        }
        if (currentAlternatives == null) return new FlowFact(false, Set.of(targetType));

        final var narrowed = new LinkedHashSet<TypeDescriptor>();
        for (final var alternative : currentAlternatives) {
            if (typeCompatibility.canAssign(targetType, alternative)) {
                narrowed.add(alternative);
            } else if (typeCompatibility.canAssign(alternative, targetType)) {
                narrowed.add(targetType);
            }
        }
        if (narrowed.isEmpty()) narrowed.add(targetType);
        return new FlowFact(false, narrowed);
    }

    private FlowFact nonNullFact(final TypeDescriptor declaredType) {
        final var alternatives = initialNonNullAlternatives(declaredType);
        return new FlowFact(false, alternatives);
    }

    private Set<TypeDescriptor> initialNonNullAlternatives(final TypeDescriptor declaredType) {
        var baseType = declaredType instanceof NullableDescriptor nullable
                ? nullable.baseType()
                : declaredType;
        if (baseType instanceof ReferenceDescriptor reference) baseType = reference.baseType();
        if (baseType instanceof AnyDescriptor) return null;
        return Set.of(baseType);
    }

    private TypeDescriptor effectiveType(final Bind binding) {
        final var fact = flowState.get(binding.name());
        if (fact == null) return binding.type();
        if (fact.nonNullAlternatives() != null && fact.mayBeNull()
            && fact.nonNullAlternatives().isEmpty()) return TypeDescriptor.ofNull();
        if (fact.nonNullAlternatives() == null) {
            if (!fact.mayBeNull() && binding.type() instanceof NullableDescriptor nullable) {
                return nullable.baseType();
            }
            return binding.type();
        }
        if (fact.nonNullAlternatives().isEmpty()) return TypeDescriptor.ofNever();

        final var commonType = typeCompatibility.commonTypeForAlternatives(fact.nonNullAlternatives());
        final var declaredBase = binding.type() instanceof NullableDescriptor nullable
            ? nullable.baseType()
            : binding.type();
        final var effectiveBase = declaredBase instanceof ReferenceDescriptor
            ? new ReferenceDescriptor(commonType)
            : commonType;
        return fact.mayBeNull() ? effectiveBase.toNullable() : effectiveBase;
    }

    private boolean isRefinable(final Token name) {
        return symbols.containsSymbol(name)
                && symbols.getSymbol(name).lvt() != SymbolTable.GLOBAL;
    }

    private Expr.Variable directVariable(final Expr expression) {
        return switch (expression) {
            case Expr.Variable variable -> variable;
            case Expr.Grouping grouping -> directVariable(grouping.expression);
            default -> null;
        };
    }

    private boolean isNullLiteral(final Expr expression) {
        return switch (expression) {
            case Expr.Literal literal -> literal.value == null;
            case Expr.Grouping grouping -> isNullLiteral(grouping.expression);
            default -> false;
        };
    }

    private boolean isNullComparison(final Expr.Binary binary) {
        return (binary.operator.type() == TokenType.EQUAL_EQUAL
                || binary.operator.type() == TokenType.BANG_EQUAL)
                && (isNullLiteral(binary.left) || isNullLiteral(binary.right));
    }

    private boolean isPrimitive(final TypeDescriptor type) {
        final var baseType = type instanceof NullableDescriptor nullable
                ? nullable.baseType()
                : type;
        return baseType instanceof IntDescriptor || baseType instanceof FloatDescriptor
                || baseType instanceof BooleanDescriptor;
    }

    private Set<Token> resolveLoopWriteBindings(final Set<String> writtenNames) {
        final var writtenBindings = Collections.newSetFromMap(new IdentityHashMap<Token, Boolean>());
        for (final var writtenName : writtenNames) {
            final var lookup = new Token(TokenType.IDENTIFIER, writtenName, null, -1);
            if (symbols.containsSymbol(lookup)) writtenBindings.add(symbols.getSymbol(lookup).name());
        }
        return writtenBindings;
    }

    private void discardLoopWriteFacts(final FlowState state, final Set<Token> writtenBindings) {
        for (final var bindingName : new ArrayList<>(state.facts.keySet())) {
            if (writtenBindings.contains(bindingName)) state.remove(bindingName);
        }
    }

    private void collectLoopWrites(final Stmt statement,
                                   final Set<String> writtenNames,
                                   final Set<String> shadowedNames) {
        switch (statement) {
            case Stmt.Block(List<Stmt> statements) -> {
                final var localNames = new HashSet<>(shadowedNames);
                for (final var nested : statements) {
                    if (nested instanceof Stmt.Var variable) {
                        if (variable.initializer() != null) {
                            collectLoopWrites(variable.initializer(), writtenNames, localNames);
                        }
                        localNames.add(variable.name().lexeme());
                    } else {
                        collectLoopWrites(nested, writtenNames, localNames);
                    }
                }
            }
            case Stmt.Expression(Expr expression) -> collectLoopWrites(expression, writtenNames, shadowedNames);
            case Stmt.Print(Expr expression) -> collectLoopWrites(expression, writtenNames, shadowedNames);
            case Stmt.Return(Expr value) -> {
                if (value != null) collectLoopWrites(value, writtenNames, shadowedNames);
            }
            case Stmt.If(Token _, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                collectLoopWrites(condition, writtenNames, shadowedNames);
                collectLoopWrites(thenBranch, writtenNames, shadowedNames);
                if (elseBranch != null) collectLoopWrites(elseBranch, writtenNames, shadowedNames);
            }
            case Stmt.For(Token iterationBind, Token _, Expr iterable, Stmt body) -> {
                collectLoopWrites(iterable, writtenNames, shadowedNames);
                final var nestedNames = new HashSet<>(shadowedNames);
                nestedNames.add(iterationBind.lexeme());
                collectLoopWrites(body, writtenNames, nestedNames);
            }
            case Stmt.While(Token _, Expr condition, Stmt body) -> {
                collectLoopWrites(condition, writtenNames, shadowedNames);
                collectLoopWrites(body, writtenNames, shadowedNames);
            }
            case Stmt.Var(Token _, TypeDescriptor _, Expr initializer, BindingMutability _) -> {
                if (initializer != null) collectLoopWrites(initializer, writtenNames, shadowedNames);
            }
            default -> {}
        }
    }

    private void collectLoopWrites(final Expr expression,
                                   final Set<String> writtenNames,
                                   final Set<String> shadowedNames) {
        switch (expression) {
            case Expr.Assignment assignment -> {
                if (!shadowedNames.contains(assignment.name.lexeme())) {
                    writtenNames.add(assignment.name.lexeme());
                }
                collectLoopWrites(assignment.value, writtenNames, shadowedNames);
            }
            case Expr.Property property ->
                    collectLoopWrites(property.receiver, writtenNames, shadowedNames);
            case Expr.PropertyAssignment assignment -> {
                collectLoopWrites(assignment.property.receiver, writtenNames, shadowedNames);
                collectLoopWrites(assignment.value, writtenNames, shadowedNames);
            }
            case Expr.MemberCall call -> {
                collectLoopWrites(call.receiver, writtenNames, shadowedNames);
                call.arguments.forEach(argument -> collectLoopWrites(argument, writtenNames, shadowedNames));
            }
            case Expr.Call call ->
                    call.arguments.forEach(argument -> collectLoopWrites(argument, writtenNames, shadowedNames));
            case Expr.Binary binary -> {
                collectLoopWrites(binary.left, writtenNames, shadowedNames);
                collectLoopWrites(binary.right, writtenNames, shadowedNames);
            }
            case Expr.Logical logical -> {
                collectLoopWrites(logical.left, writtenNames, shadowedNames);
                collectLoopWrites(logical.right, writtenNames, shadowedNames);
            }
            case Expr.Grouping grouping ->
                    collectLoopWrites(grouping.expression, writtenNames, shadowedNames);
            case Expr.If iff -> {
                collectLoopWrites(iff.condition, writtenNames, shadowedNames);
                collectLoopWrites(iff.thenExpr, writtenNames, shadowedNames);
                collectLoopWrites(iff.elseExpr, writtenNames, shadowedNames);
            }
            case Expr.Unary unary ->
                    collectLoopWrites(unary.right, writtenNames, shadowedNames);
            case Expr.TypeTest test ->
                    collectLoopWrites(test.value, writtenNames, shadowedNames);
            case Expr.Cast cast ->
                    collectLoopWrites(cast.value, writtenNames, shadowedNames);
            case Expr.ArrayLiteral literal ->
                    literal.elements.forEach(element -> collectLoopWrites(element, writtenNames, shadowedNames));
            case Expr.Index index -> {
                collectLoopWrites(index.array, writtenNames, shadowedNames);
                collectLoopWrites(index.index, writtenNames, shadowedNames);
            }
            case Expr.IndexAssignment assignment -> {
                collectLoopWrites(assignment.array, writtenNames, shadowedNames);
                collectLoopWrites(assignment.index, writtenNames, shadowedNames);
                collectLoopWrites(assignment.value, writtenNames, shadowedNames);
            }
            case Expr.Lambda _, Expr.Literal _, Expr.Variable _ -> {}
        }
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
        final var fieldType = TypeSubstitution.substitute(field.type(),
                substitutionsFor(owner.typeParameters(), receiverType));
        property.setType(fieldType);
        return fieldType;
    }

    private TypeDescriptor resolveMemberCall(final Expr.MemberCall call) {
        if (call.receiver instanceof Expr.Variable typeName
                && classes.containsKey(typeName.name.lexeme())) {
            final var declaration = classes.get(typeName.name.lexeme());
            if (declaration.typeParameters().size() != call.explicitTypeArguments.size()) {
                Zeron.resolutionError(new ResolutionError(call.name,
                        "Expected " + declaration.typeParameters().size() + " class type arguments, found "
                                + call.explicitTypeArguments.size() + "."));
            }
            call.explicitTypeArguments.forEach(type -> validateType(type, call.name));
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            for (int i = 0; i < declaration.typeParameters().size(); i++) {
                substitutions.put(declaration.typeParameters().get(i), call.explicitTypeArguments.get(i));
            }
                if (call.name.lexeme().equals("new")) {
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
                    final var expectedType = TypeSubstitution.substitute(
                        declaration.fields().get(i).type(), substitutions);
                    ensureAssignable(expectedType,
                        resolveArgument(call.arguments.get(i), expectedType), call.name);
                }
                final TypeDescriptor constructedType = declaration.typeParameters().isEmpty()
                    ? TypeDescriptor.of(declaration.name().lexeme())
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName(declaration.name().lexeme()),
                        call.explicitTypeArguments);
                final var result = new ReferenceDescriptor(constructedType);
                call.setType(result);
                return result;
            }

                final var namedConstructor = declaration.namedConstructors().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(call.name.lexeme()))
                    .findFirst()
                    .orElse(null);
                if (namedConstructor == null) {
                Zeron.resolutionError(new ResolutionError(call.name, "Unknown named constructor."));
                }
                if (!namedConstructor.isPublic()
                    && !Objects.equals(currentClassName, declaration.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(call.name, "Named constructor is private."));
                }
                final var instantiatedFactory = (FunctionDescriptor) TypeSubstitution.substitute(
                    namedConstructor.typeDescriptor(), substitutions);
                if (instantiatedFactory.arity() != call.arguments.size()) {
                Zeron.resolutionError(new ResolutionError(call.name,
                    "Expected " + instantiatedFactory.arity() + " arguments, found "
                        + call.arguments.size() + "."));
                }
                for (int i = 0; i < call.arguments.size(); i++) {
                final var expectedType = instantiatedFactory.parameters().get(i);
                ensureAssignable(expectedType,
                    resolveArgument(call.arguments.get(i), expectedType), call.name);
                }
                call.setResolvedDescriptor(instantiatedFactory);
                call.setType(instantiatedFactory.returnType());
                return instantiatedFactory.returnType();
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
        final var typeParameters = classMethod != null
            ? owner.typeParameters()
            : contract.typeParameters();
        final var instantiatedDescriptor = (FunctionDescriptor) TypeSubstitution.substitute(
            descriptor, substitutionsFor(typeParameters, receiverType));
        call.setResolvedDescriptor(instantiatedDescriptor);
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
        if (instantiatedDescriptor.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Expected " + instantiatedDescriptor.arity() + " arguments, found "
                            + call.arguments.size() + "."));
        }
        for (int i = 0; i < call.arguments.size(); i++) {
            ensureAssignable(instantiatedDescriptor.parameters().get(i),
                    resolveArgument(call.arguments.get(i), instantiatedDescriptor.parameters().get(i)),
                    call.name);
        }
        call.setType(instantiatedDescriptor.returnType());
        return instantiatedDescriptor.returnType();
    }

    private TypeDescriptor resolveArgument(final Expr argument,
                                           final TypeDescriptor expectedType) {
        if (argument instanceof Expr.Lambda lambda) {
            final var functionType = functionType(expectedType);
            if (functionType != null) return resolveLambda(lambda, functionType);
        }
        return resolve(argument);
    }

    private FunctionDescriptor functionType(final TypeDescriptor type) {
        return switch (type) {
            case FunctionDescriptor function -> function;
            case NullableDescriptor nullable -> functionType(nullable.baseType());
            case ReferenceDescriptor reference -> functionType(reference.baseType());
            default -> null;
        };
    }

    private String className(final TypeDescriptor type) {
        var baseType = type instanceof ReferenceDescriptor reference ? reference.baseType() : type;
        if (baseType instanceof GenericDescriptor generic) baseType = generic.baseType();
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
        final var enclosingFlow = flowState;
        loopDepth = 0;
        flowState = new FlowState();
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
            flowState = enclosingFlow;
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
        final var enclosingFlow = flowState;
        loopDepth = 0;
        flowState = new FlowState();
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
            flowState = enclosingFlow;
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
            TypeUnifier.unify(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.callee);
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
            TypeUnifier.unify(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.callee);
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
        final var enclosingFlow = flowState;
        loopDepth = 0;
        flowState = new FlowState();
        beginScope();
        expectedReturnTypes.push(function.typeDescriptor().returnType());
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
                Zeron.debug(" resolved function " + function.name().lexeme()
                    + " -> " + symbols.getFunction(function.name()).type());
        } finally {
            expectedReturnTypes.pop();
            endScope();
            loopDepth = enclosingLoopDepth;
            flowState = enclosingFlow;
        }
    }

    public TypeDescriptor ensureReturns(final Token where,
                                        final TypeDescriptor expectedType,
                                        final List<Stmt> statements) {
        var currentType = expectedType;
        for (final var statement : statements) {
            if (statement instanceof Stmt.Return(Expr value)) {
            final var returnType = value == null
                ? TypeDescriptor.ofUnit()
                : value instanceof Expr.Lambda lambda
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
        return typeCompatibility.ensureExact(where, typeA, typeB);
    }

    public TypeDescriptor ensureCommonParent(final Token where,
                                             final TypeDescriptor typeA,
                                             final TypeDescriptor typeB) {
        return typeCompatibility.ensureCommonParent(where, typeA, typeB);
    }

    public TypeDescriptor ensureAssignable(TypeDescriptor expectedType, TypeDescriptor resolvedType) {
        return ensureAssignable(expectedType, resolvedType, SYNTHETIC_IDENTIFIER);
    }

    private TypeDescriptor ensureAssignable(TypeDescriptor expectedType,
                                            TypeDescriptor resolvedType,
                                            Token where) {
        return typeCompatibility.ensureAssignable(expectedType, resolvedType, where);
    }

    public boolean isContractProjection(final TypeDescriptor expectedType,
                                        final TypeDescriptor resolvedType) {
        return typeCompatibility.isContractProjection(expectedType, resolvedType);
    }

    public void ensureBoolean(TypeDescriptor type) {
        ensureBoolean(type, SYNTHETIC_IDENTIFIER);
    }

    private void ensureBoolean(TypeDescriptor type, Token where) {
        typeCompatibility.ensureBoolean(type, where);
    }

    public void ensureIterable(final TypeDescriptor type) {
        ensureIterable(type, SYNTHETIC_IDENTIFIER);
    }

    private TypeDescriptor ensureIterable(final TypeDescriptor type, final Token where) {
        final var arrayType = type instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : type;
        if (arrayType instanceof ArrayDescriptor array) return array.elementType();
        Zeron.resolutionError(new ResolutionError(where,
                "For loops support Array<T> values and integer range literals."));
        return TypeDescriptor.ofInfer();
    }
}
