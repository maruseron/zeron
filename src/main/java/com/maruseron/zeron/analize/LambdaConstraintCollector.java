package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

final class LambdaConstraintCollector {
    record Result(List<TypeDescriptor> parameterTypes,
                  List<TypeParameterDescriptor> typeParameters) {}

    private static final AtomicInteger SCOPES = new AtomicInteger();

    private final ResolutionContext context;
    private final Expr.Lambda lambda;
    private final List<TypeParameterDescriptor> provisionalParameters;
    private final Map<String, Integer> parameterIndices = new HashMap<>();
    private final Map<Integer, List<TypeDescriptor>> bounds = new HashMap<>();
    private final Set<Integer> generalized = new HashSet<>();
    private final int[] parents;

    private LambdaConstraintCollector(final ResolutionContext context, final Expr.Lambda lambda) {
        this.context = context;
        this.lambda = lambda;
        final int scope = SCOPES.incrementAndGet();
        final var parameters = new ArrayList<TypeParameterDescriptor>(lambda.params.size());
        parents = new int[lambda.params.size()];
        for (int index = 0; index < lambda.params.size(); index++) {
            final var parameter = new TypeParameterDescriptor(scope, "LambdaT" + index);
            parameters.add(parameter);
            parameterIndices.put(lambda.params.get(index).lexeme(), index);
            parents[index] = index;
        }
        provisionalParameters = List.copyOf(parameters);
    }

    static Result collect(final ResolutionContext context, final Expr.Lambda lambda) {
        final var collector = new LambdaConstraintCollector(context, lambda);
        collector.scan(lambda.body);
        if (collector.generalized.isEmpty()) return null;

        final var scope = collector.provisionalParameters.getFirst().scopeId();
        final var rootIndices = new LinkedHashSet<Integer>();
        for (final var index : collector.generalized) rootIndices.add(collector.root(index));
        final var initialRootCount = rootIndices.size();

        final var provisionalToCanonical = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < collector.parents.length; index++) {
            if (rootIndices.contains(collector.root(index))) {
                provisionalToCanonical.put(collector.provisionalParameters.get(index),
                        collector.provisionalParameters.get(collector.root(index)));
            }
        }

        final var canonicalBounds = new LinkedHashMap<Integer, LinkedHashSet<TypeDescriptor>>();
        for (int index = 0; index < collector.parents.length; index++) {
            final var root = collector.root(index);
            if (!rootIndices.contains(root)) continue;
            final var destination = canonicalBounds.computeIfAbsent(root, _ -> new LinkedHashSet<>());
            for (final var bound : collector.bounds.getOrDefault(index, List.of())) {
                destination.add(TypeSubstitution.substitute(bound, provisionalToCanonical));
            }
        }
        for (final var boundsForParameter : canonicalBounds.values()) {
            for (final var bound : boundsForParameter) {
                for (int index = 0; index < collector.provisionalParameters.size(); index++) {
                    if (collector.containsTypeParameter(
                            bound, collector.provisionalParameters.get(index))) {
                        rootIndices.add(collector.root(index));
                    }
                }
            }
        }
        if (rootIndices.size() != initialRootCount) {
            return collectWithReferencedParameters(collector, rootIndices, canonicalBounds);
        }

        final var typeParameters = new ArrayList<TypeParameterDescriptor>(rootIndices.size());
        final var canonicalToFinal = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (final var root : rootIndices) {
            final var canonical = collector.provisionalParameters.get(root);
            final var parameter = new TypeParameterDescriptor(scope, canonical.name(),
                    List.copyOf(canonicalBounds.getOrDefault(root, new LinkedHashSet<>())));
            typeParameters.add(parameter);
            canonicalToFinal.put(canonical, parameter);
        }

        final var parameters = new ArrayList<TypeDescriptor>(collector.parents.length);
        for (int index = 0; index < collector.parents.length; index++) {
            final var root = collector.root(index);
            final var canonical = collector.provisionalParameters.get(root);
            parameters.add(rootIndices.contains(root)
                    ? canonicalToFinal.get(canonical)
                    : TypeDescriptor.ofInfer());
        }
        return new Result(List.copyOf(parameters), List.copyOf(typeParameters));
    }

    private static Result collectWithReferencedParameters(
            final LambdaConstraintCollector collector,
            final LinkedHashSet<Integer> rootIndices,
            final LinkedHashMap<Integer, LinkedHashSet<TypeDescriptor>> canonicalBounds) {
        final var scope = collector.provisionalParameters.getFirst().scopeId();
        final var provisionalToCanonical = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < collector.parents.length; index++) {
            final var root = collector.root(index);
            if (rootIndices.contains(root)) {
                provisionalToCanonical.put(collector.provisionalParameters.get(index),
                        collector.provisionalParameters.get(root));
            }
        }
        final var expandedBounds = new LinkedHashMap<Integer, LinkedHashSet<TypeDescriptor>>();
        for (final var root : rootIndices) {
            final var destination = expandedBounds.computeIfAbsent(root, _ -> new LinkedHashSet<>());
            for (final var bound : canonicalBounds.getOrDefault(root, new LinkedHashSet<>())) {
                destination.add(TypeSubstitution.substitute(bound, provisionalToCanonical));
            }
        }
        final var typeParameters = new ArrayList<TypeParameterDescriptor>(rootIndices.size());
        final var canonicalToFinal = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (final var root : rootIndices) {
            final var canonical = collector.provisionalParameters.get(root);
            final var parameter = new TypeParameterDescriptor(scope, canonical.name(),
                    List.copyOf(expandedBounds.getOrDefault(root, new LinkedHashSet<>())));
            typeParameters.add(parameter);
            canonicalToFinal.put(canonical, parameter);
        }
        final var parameters = new ArrayList<TypeDescriptor>(collector.parents.length);
        for (int index = 0; index < collector.parents.length; index++) {
            final var root = collector.root(index);
            parameters.add(rootIndices.contains(root)
                    ? canonicalToFinal.get(collector.provisionalParameters.get(root))
                    : TypeDescriptor.ofInfer());
        }
        return new Result(List.copyOf(parameters), List.copyOf(typeParameters));
    }

    private void scan(final List<Stmt> statements) {
        for (final var statement : statements) scan(statement);
    }

    private void scan(final Stmt statement) {
        switch (statement) {
            case Stmt.Block block -> scan(block.statements());
            case Stmt.Return returned -> scan(returned.value());
            case Stmt.Expression expression -> scan(expression.expression());
            case Stmt.Var variable -> scan(variable.initializer());
            case Stmt.If conditional -> {
                scan(conditional.condition());
                scan(conditional.thenBranch());
                scan(conditional.elseBranch());
            }
            case Stmt.While loop -> {
                scan(loop.condition());
                scan(loop.body());
            }
            case Stmt.For loop -> {
                scan(loop.iterable());
                scan(loop.body());
            }
            case Stmt.Using using -> {
                for (final var resource : using.resources()) scan(resource.declaration().initializer());
                scan(using.body());
            }
            default -> {}
        }
    }

    private void scan(final Expr expression) {
        if (expression == null) return;
        switch (expression) {
            case Expr.MemberCall call -> {
                final var receiverIndex = lambdaParameter(call.receiver);
                if (receiverIndex >= 0) inferMemberBound(receiverIndex, call);
                scan(call.receiver);
                call.arguments.forEach(this::scan);
            }
            case Expr.Property property -> {
                final var receiverIndex = lambdaParameter(property.receiver);
                if (receiverIndex >= 0) inferPropertyBound(receiverIndex, property);
                scan(property.receiver);
            }
            case Expr.Call call -> {
                inferCalleeBounds(call);
                call.arguments.forEach(this::scan);
            }
            case Expr.Binary binary -> {
                scan(binary.left);
                scan(binary.right);
            }
            case Expr.Logical logical -> {
                scan(logical.left);
                scan(logical.right);
            }
            case Expr.Coalesce coalesce -> {
                scan(coalesce.left);
                scan(coalesce.right);
            }
            case Expr.CoalesceAssignment assignment -> scan(assignment.value);
            case Expr.Assignment assignment -> scan(assignment.value);
            case Expr.PropertyAssignment assignment -> {
                scan(assignment.property);
                scan(assignment.value);
            }
            case Expr.PropertyCompoundAssignment assignment -> {
                scan(assignment.property);
                scan(assignment.value);
            }
            case Expr.ArrayLiteral literal -> literal.elements.forEach(this::scan);
            case Expr.Index index -> {
                scan(index.array);
                scan(index.index);
            }
            case Expr.IndexAssignment assignment -> {
                scan(assignment.array);
                scan(assignment.index);
                scan(assignment.value);
            }
            case Expr.Grouping grouping -> scan(grouping.expression);
            case Expr.Unary unary -> scan(unary.right);
            case Expr.If conditional -> {
                scan(conditional.condition);
                scan(conditional.thenExpr);
                scan(conditional.elseExpr);
            }
            case Expr.Match match -> {
                scan(match.scrutinee);
                for (final var arm : match.arms) {
                    scan(arm.guard());
                    scan(arm.expression());
                }
            }
            case Expr.Pipeline pipeline -> {
                if (pipeline.isComprehension()) {
                    for (final var clause : pipeline.clauses()) {
                        switch (clause) {
                            case Expr.PipelineClause.Generator generator -> scan(generator.source());
                            case Expr.PipelineClause.Let let -> scan(let.expression());
                            case Expr.PipelineClause.Guard guard -> scan(guard.condition());
                        }
                    }
                    switch (pipeline.result()) {
                        case Expr.PipelineResult.Yield yielded -> scan(yielded.expression());
                        case Expr.PipelineResult.Collect collect -> {
                            scan(collect.expression());
                            scan(collect.sink());
                        }
                    }
                    break;
                }
                scan(pipeline.source);
                for (final var stage : pipeline.stages) {
                    if (!(stage instanceof Expr.PipelineStage.Map)
                            && !(stage instanceof Expr.PipelineStage.FlatMap)
                            && !(stage instanceof Expr.PipelineStage.Filter)
                            && !(stage instanceof Expr.PipelineStage.DropWhile)
                            && !(stage instanceof Expr.PipelineStage.TakeWhile)) {
                        scan(stage.expression());
                    }
                }
            }
            case Expr.Raise raise -> scan(raise.effect);
            case Expr.Handle handle -> {
                scan(handle.expression);
                for (final var arm : handle.arms) {
                    scan(arm.guard());
                    scan(arm.expression());
                }
            }
            case Expr.HandlerDispatch dispatch -> {
                for (final var arm : dispatch.arms) {
                    scan(arm.guard());
                    scan(arm.expression());
                }
            }
            case Expr.HandlerLiteral handler -> scan(handler.implementation);
            case Expr.TypeTest test -> scan(test.value);
            case Expr.SumCaseTest test -> scan(test.value);
            case Expr.Cast cast -> scan(cast.value);
            case Expr.AnonymousObject object -> object.initializers().forEach(this::scan);
            case Expr.SuffixedLiteral literal -> scan(literal.factoryCall());
            case Expr.Lambda _, Expr.Variable _, Expr.Literal _ -> {}
        }
    }

    private void inferMemberBound(final int parameterIndex, final Expr.MemberCall call) {
        final var candidates = new ArrayList<TypeDescriptor>();
        for (final var contract : context.contracts.values()) {
            for (final var method : contract.methods()) {
                if (!method.name().lexeme().equals(call.name.lexeme()) || method.isMutating()
                        || method.typeDescriptor().isGeneric()
                        || !acceptsArity(method.minimumArity(), method.typeDescriptor().arity(),
                                method.variadic(), call.arguments.size())) {
                    continue;
                }
                final var substitutions = inferContractArguments(contract, method.typeDescriptor(),
                        call.arguments, parameterIndex);
                if (substitutions == null) continue;
                candidates.add(appliedContractType(contract, substitutions));
            }
        }
        addUniqueMemberBound(parameterIndex, call.name, candidates);
    }

    private void inferPropertyBound(final int parameterIndex, final Expr.Property property) {
        final var candidates = new ArrayList<TypeDescriptor>();
        for (final var contract : context.contracts.values()) {
            for (final var member : contract.properties()) {
                if (member.name().lexeme().equals(property.name.lexeme())) {
                    candidates.add(defaultContractType(contract, parameterIndex));
                }
            }
        }
        addUniqueMemberBound(parameterIndex, property.name, candidates);
    }

    private Map<TypeParameterDescriptor, TypeDescriptor> inferContractArguments(
            final Stmt.ContractDecl contract, final FunctionDescriptor signature,
            final List<Expr> arguments, final int receiverIndex) {
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        final var contractParameters = new HashSet<>(contract.typeParameters());
        for (int index = 0; index < arguments.size(); index++) {
            final int parameterIndex = Math.min(index, signature.arity() - 1);
            var expected = signature.parameters().get(parameterIndex);
            if (signature.parameters().get(parameterIndex) instanceof ArrayDescriptor array
                    && index >= signature.arity() - 1) {
                expected = array.elementType();
            }
            final var actual = inferKnownType(arguments.get(index));
            if (actual != null && !constrainContractArguments(
                    expected, actual, contractParameters, substitutions)) {
                return null;
            }
        }
        if (contract.typeParameters().size() == 1) {
            substitutions.putIfAbsent(contract.typeParameters().getFirst(),
                    provisionalParameters.get(receiverIndex));
        } else if (contract.typeParameters().stream().anyMatch(parameter ->
                !substitutions.containsKey(parameter))) {
            return null;
        }
        return substitutions;
    }

    private TypeDescriptor inferKnownType(final Expr expression) {
        if (expression instanceof Expr.Variable variable) {
            final int parameterIndex = lambdaParameter(variable);
            if (parameterIndex >= 0) return provisionalParameters.get(parameterIndex);
            if (context.symbols.containsSymbol(variable.name)) {
                return context.symbols.getSymbol(variable.name).type();
            }
        }
        if (expression instanceof Expr.Literal literal) {
            return switch (literal.value) {
                case String _ -> TypeDescriptor.ofString();
                case Integer _ -> TypeDescriptor.ofInt();
                case Long _ -> TypeDescriptor.ofLong();
                case Byte _ -> TypeDescriptor.ofByte();
                case Double _ -> TypeDescriptor.ofFloat();
                case Boolean _ -> TypeDescriptor.ofBoolean();
                case null -> TypeDescriptor.ofNull();
                default -> null;
            };
        }
        if (expression instanceof Expr.Grouping grouping) return inferKnownType(grouping.expression);
        return null;
    }

    private boolean constrainContractArguments(
            final TypeDescriptor pattern, final TypeDescriptor actual,
            final Set<TypeParameterDescriptor> contractParameters,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (pattern instanceof TypeParameterDescriptor parameter && contractParameters.contains(parameter)) {
            final var previous = substitutions.putIfAbsent(parameter, actual);
            return previous == null || previous.equals(actual);
        }
        if (pattern instanceof GenericDescriptor expected && actual instanceof GenericDescriptor found
                && expected.baseType().equals(found.baseType())
                && expected.typeParameters().size() == found.typeParameters().size()) {
            for (int index = 0; index < expected.typeParameters().size(); index++) {
                if (!constrainContractArguments(expected.typeParameters().get(index),
                        found.typeParameters().get(index), contractParameters, substitutions)) return false;
            }
            return true;
        }
        if (pattern instanceof ArrayDescriptor expected && actual instanceof ArrayDescriptor found) {
            return constrainContractArguments(expected.elementType(), found.elementType(),
                    contractParameters, substitutions);
        }
        return pattern instanceof TypeParameterDescriptor || pattern.equals(actual)
                || actual instanceof TypeParameterDescriptor;
    }

    private TypeDescriptor defaultContractType(final Stmt.ContractDecl contract, final int receiverIndex) {
        final var arguments = contract.typeParameters().stream()
                .map(_ -> (TypeDescriptor) provisionalParameters.get(receiverIndex)).toList();
        return appliedContractType(contract, mapContractArguments(contract, arguments));
    }

    private TypeDescriptor appliedContractType(
            final Stmt.ContractDecl contract, final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        final var arguments = contract.typeParameters().stream()
                .map(parameter -> substitutions.get(parameter)).toList();
        return arguments.isEmpty()
                ? TypeDescriptor.ofName(contract.name().lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(contract.name().lexeme()), arguments);
    }

    private Map<TypeParameterDescriptor, TypeDescriptor> mapContractArguments(
            final Stmt.ContractDecl contract, final List<TypeDescriptor> arguments) {
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < contract.typeParameters().size(); index++) {
            substitutions.put(contract.typeParameters().get(index), arguments.get(index));
        }
        return substitutions;
    }

    private void addUniqueMemberBound(final int parameterIndex, final Token member,
                                      final List<TypeDescriptor> candidates) {
        final var distinct = new LinkedHashSet<>(candidates);
        if (distinct.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, member,
                    "Cannot infer a unique contract bound for member '" + member.lexeme()
                            + "' in this polymorphic lambda."));
        }
        if (distinct.isEmpty()) return;
        generalized.add(parameterIndex);
        final var bound = distinct.getFirst();
        bounds.computeIfAbsent(parameterIndex, _ -> new ArrayList<>()).add(bound);
        for (int index = 0; index < provisionalParameters.size(); index++) {
            if (containsTypeParameter(bound, provisionalParameters.get(index))) generalized.add(index);
        }
    }

    private void inferCalleeBounds(final Expr.Call call) {
        if (!call.explicitTypeArguments.isEmpty()) return;
        final FunctionDescriptor functionType;
        if (context.symbols.containsSymbol(call.callee)) {
            final var calleeType = context.symbols.getSymbol(call.callee).type();
            if (!(calleeType instanceof FunctionDescriptor function)) return;
            functionType = function;
        } else {
            final var functionName = MemberInteropResolver.resolveFunctionName(
                    context, call.callee.lexeme(), call.callee);
            final var declaration = functionName == null ? null : context.functions.get(functionName);
            if (declaration == null || declaration instanceof Stmt.ExtensionMethod) return;
            functionType = declaration.typeDescriptor();
        }
        if (!functionType.isGeneric()) return;

        final var mappedParameters = new LinkedHashMap<TypeParameterDescriptor, List<Integer>>();
        final var fixedParameters = new HashSet<TypeParameterDescriptor>();
        for (int index = 0; index < call.arguments.size() && index < functionType.arity(); index++) {
            final var lambdaIndex = lambdaParameter(call.arguments.get(index));
            final var parameterType = functionType.parameters().get(index);
            if (!(parameterType instanceof TypeParameterDescriptor typeParameter)
                    || !functionType.typeParameters().contains(typeParameter)) continue;
            if (lambdaIndex < 0) {
                fixedParameters.add(typeParameter);
            } else {
                mappedParameters.computeIfAbsent(typeParameter, _ -> new ArrayList<>()).add(lambdaIndex);
            }
        }
        fixedParameters.forEach(mappedParameters::remove);
        if (mappedParameters.isEmpty()) return;

        for (final var indexes : mappedParameters.values()) {
            for (int index = 1; index < indexes.size(); index++) union(indexes.getFirst(), indexes.get(index));
        }
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        mappedParameters.forEach((parameter, indexes) ->
                substitutions.put(parameter, provisionalParameters.get(indexes.getFirst())));
        for (final var entry : mappedParameters.entrySet()) {
            final var lambdaIndex = entry.getValue().getFirst();
            for (final var bound : entry.getKey().bounds()) {
                final var requiredBound = TypeSubstitution.substitute(bound, substitutions);
                final boolean containsUnmappedFunctionParameter = functionType.typeParameters().stream()
                        .filter(parameter -> !substitutions.containsKey(parameter))
                        .anyMatch(parameter -> containsTypeParameter(requiredBound, parameter));
                if (!containsUnmappedFunctionParameter) {
                    bounds.computeIfAbsent(lambdaIndex, _ -> new ArrayList<>()).add(requiredBound);
                    generalized.addAll(entry.getValue());
                    for (int index = 0; index < provisionalParameters.size(); index++) {
                        if (containsTypeParameter(requiredBound, provisionalParameters.get(index))) {
                            generalized.add(index);
                        }
                    }
                }
            }
        }
    }

    private boolean containsTypeParameter(final TypeDescriptor type,
                                         final TypeParameterDescriptor parameter) {
        if (type.equals(parameter)) return true;
        return switch (type) {
            case NullableDescriptor nullable -> containsTypeParameter(nullable.baseType(), parameter);
            case ReferenceDescriptor reference -> containsTypeParameter(reference.baseType(), parameter);
            case ArrayDescriptor array -> containsTypeParameter(array.elementType(), parameter);
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(argument -> containsTypeParameter(argument, parameter));
            case FunctionDescriptor function -> containsTypeParameter(function.returnType(), parameter)
                    || function.parameters().stream().anyMatch(value -> containsTypeParameter(value, parameter));
            default -> false;
        };
    }

    private int lambdaParameter(final Expr expression) {
        if (expression instanceof Expr.Grouping grouping) return lambdaParameter(grouping.expression);
        if (expression instanceof Expr.Variable variable) {
            return parameterIndices.getOrDefault(variable.name.lexeme(), -1);
        }
        return -1;
    }

    private boolean acceptsArity(final int minimum, final int maximum, final boolean variadic,
                                 final int actual) {
        return actual >= minimum && (variadic || actual <= maximum);
    }

    private void union(final int left, final int right) {
        final var leftRoot = root(left);
        final var rightRoot = root(right);
        if (leftRoot != rightRoot) parents[rightRoot] = leftRoot;
    }

    private int root(final int index) {
        if (parents[index] != index) parents[index] = root(parents[index]);
        return parents[index];
    }
}
