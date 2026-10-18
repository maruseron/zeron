package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.*;

import static com.maruseron.zeron.analize.Resolver.*;

final class ExpressionFlowResolver {
    private static void ensureImmutableCaptures(final ResolutionContext context, final Expr.Lambda lambda) {
        new LambdaCaptureValidator(context.symbols).validate(lambda);
    }

    private static TypeDescriptor resolve(final ResolutionContext context, Expr expr) {
        return switch (expr) {
            case Expr.MemberCall call -> {
                final var resolved = MemberInteropResolver.resolveMemberCall(context, call);
                if (call.resolvedDescriptor() != null) {
                    RaisedEffectFlow.add(context, new LinkedHashSet<>(call.resolvedDescriptor().raisedEffects()),
                            call.name);
                } else if (call.namespaceCall() != null
                        && call.namespaceCall().genericFunctionType() != null) {
                    RaisedEffectFlow.add(context,
                            new LinkedHashSet<>(call.namespaceCall().genericFunctionType().raisedEffects()),
                            call.name);
                }
                yield resolved;
            }
            case Expr.PropertyAssignment assignment -> {
                if (assignment.property.safeNavigation()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                            assignment.property.name,
                            "Safe navigation cannot be used for property assignment."));
                }
                MemberInteropResolver.resolveProperty(context, assignment.property);
                if (assignment.property.sumPayloadIndex() >= 0) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            assignment.property.name, "Sum payloads are immutable."));
                }
                final var javaFieldTarget = assignment.property.javaFieldTarget();
                if (javaFieldTarget != null) {
                    if (javaFieldTarget.isStatic()) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                                assignment.property.name, "Static Java field writes are not supported."));
                    }
                    if (javaFieldTarget.isFinal()) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                                assignment.property.name, "Final Java fields are read-only."));
                    }
                    final var receiverType = assignment.property.receiver.getType();
                    if (!(receiverType instanceof ReferenceDescriptor)) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                                assignment.property.name,
                                "Java field assignment requires a mutable reference."));
                    }
                    ensureAssignable(context, assignment.property.getType(),
                            MemberInteropResolver.resolveArgument(context, assignment.value,
                                    assignment.property.getType()), assignment.property.name);
                    assignment.setType(TypeDescriptor.ofUnit());
                    yield TypeDescriptor.ofUnit();
                }
                if (assignment.property.extensionCall() != null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            assignment.property.name, "Extension properties are read-only."));
                }
                final var receiverType = assignment.property.receiver.getType();
                final var ownerName = assignment.property.resolvedOwnerName() == null
                        ? className(context, receiverType)
                        : assignment.property.resolvedOwnerName();
                if (!(receiverType instanceof ReferenceDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            assignment.property.name,
                            "Property assignment requires a mutable reference."));
                }
                final var owner = context.classes.get(ownerName);
                final var propertyDeclaration = MemberInteropResolver.findProperty(
                        context, ownerName, assignment.property.name);
                final TypeDescriptor expectedType;
                if (propertyDeclaration != null) {
                    if (!propertyDeclaration.isMutating()) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                                assignment.property.name,
                                "Property is read-only."));
                    }
                    expectedType = assignment.property.getType();
                } else {
                    final var field = MemberInteropResolver.findField(
                            context, ownerName, assignment.property.name);
                    expectedType = owner == null ? field.type()
                            : TypeSubstitution.substitute(field.type(),
                                    TypeResolver.substitutionsFor(context, owner.typeParameters(), receiverType));
                }
                ensureAssignable(context, expectedType,
                    MemberInteropResolver.resolveArgument(context, assignment.value, expectedType),
                    assignment.property.name);
                assignment.setType(TypeDescriptor.ofUnit());
                yield TypeDescriptor.ofUnit();
            }
            case Expr.PropertyCompoundAssignment assignment -> {
                MemberInteropResolver.resolveProperty(context, assignment.property);
                if (assignment.property.sumPayloadIndex() >= 0) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            assignment.property.name, "Sum payloads are immutable."));
                }
                final var javaFieldTarget = assignment.property.javaFieldTarget();
                if (javaFieldTarget != null) {
                    if (javaFieldTarget.isStatic() || javaFieldTarget.isFinal()) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                                assignment.property.name,
                                javaFieldTarget.isStatic() ? "Static Java field writes are not supported."
                                        : "Final Java fields are read-only."));
                    }
                    final var receiverType = assignment.property.receiver.getType();
                    if (!(receiverType instanceof ReferenceDescriptor)) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                                assignment.property.name,
                                "Java field assignment requires a mutable reference."));
                    }
                    final var operation = new Expr.Binary(assignment.property,
                            assignment.operator, assignment.value, TypeDescriptor.ofInfer());
                    final var valueType = resolve(context, operation);
                    assignment.setResolvedOperation(operation);
                    ensureAssignable(context, assignment.property.getType(), valueType,
                            assignment.property.name);
                    assignment.setType(TypeDescriptor.ofUnit());
                    yield TypeDescriptor.ofUnit();
                }
                if (assignment.property.extensionCall() != null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            assignment.property.name, "Extension properties are read-only."));
                }
                final var receiverType = assignment.property.receiver.getType();
                if (!(receiverType instanceof ReferenceDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            assignment.property.name,
                            "Property compound assignment requires a mutable reference."));
                }
                final var ownerName = assignment.property.resolvedOwnerName() == null
                        ? className(context, receiverType)
                        : assignment.property.resolvedOwnerName();
                final var property = MemberInteropResolver.findProperty(
                        context, ownerName, assignment.property.name);
                if (property == null || !property.isMutating()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            assignment.property.name,
                            "Compound assignment requires a writable property."));
                }
                final var operation = new Expr.Binary(assignment.property,
                        assignment.operator, assignment.value, TypeDescriptor.ofInfer());
                final var valueType = resolve(context, operation);
                assignment.setResolvedOperation(operation);
                ensureAssignable(context, assignment.property.getType(), valueType, assignment.property.name);
                assignment.setType(TypeDescriptor.ofUnit());
                yield TypeDescriptor.ofUnit();
            }
            case Expr.Property property -> MemberInteropResolver.resolveProperty(context, property);
            case Expr.ArrayLiteral literal -> {
                if (literal.elements.isEmpty()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_INFERENCE_FAILURE,
                            SYNTHETIC_IDENTIFIER,
                            "Cannot infer an empty array's element type without an expected Array<T> type."));
                }
                final var elementTypes = new ArrayList<TypeDescriptor>();
                var elementType = (TypeDescriptor) null;
                var hasNullElement = false;
                for (final var element : literal.elements) {
                    final var resolvedElement = resolve(context, element);
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
                        ensureExact(context, SYNTHETIC_IDENTIFIER, elementType, resolvedElement);
                    }
                }
                if (elementType == null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_INFERENCE_FAILURE,
                            SYNTHETIC_IDENTIFIER,
                            "Cannot infer an array element type from null values."));
                }
                if (hasNullElement) elementType = elementType.toNullable();
                final var operation = IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.ARRAY_LITERAL,
                        List.of(elementType), elementTypes, SYNTHETIC_IDENTIFIER);
                literal.setIntrinsicOperation(operation);
                literal.setType(operation.resultType());
                yield operation.resultType();
            }
            case Expr.Index index -> {
                final var receiverType = resolve(context, index.array);
                final var arrayType = IntrinsicResolver.resolveArrayType(
                        context, receiverType, SYNTHETIC_IDENTIFIER);
                final var indexType = resolve(context, index.index);
                final var operation = IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.ARRAY_READ,
                        List.of(arrayType.elementType()), List.of(receiverType, indexType), SYNTHETIC_IDENTIFIER);
                index.setIntrinsicOperation(operation);
                index.setType(operation.resultType());
                yield operation.resultType();
            }
            case Expr.IndexAssignment assignment -> {
                final var receiverType = resolve(context, assignment.array);
                if (!(receiverType instanceof ReferenceDescriptor reference)
                        || !(reference.baseType() instanceof ArrayDescriptor arrayType)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            SYNTHETIC_IDENTIFIER,
                            "Array slot assignment requires a mutable &Array<T> view."));
                    yield TypeDescriptor.ofUnit();
                }
                    final var indexType = resolve(context, assignment.index);
                    final var valueType = resolve(context, assignment.value);
                    final var operation = IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.ARRAY_WRITE,
                        List.of(arrayType.elementType()), List.of(receiverType, indexType, valueType),
                        SYNTHETIC_IDENTIFIER);
                    assignment.setIntrinsicOperation(operation);
                    assignment.setType(operation.resultType());
                    yield operation.resultType();
            }
            case Expr.Assignment assignment -> {
                final var symbolName = context.symbols.containsSymbol(assignment.name)
                        ? assignment.name : MemberInteropResolver.resolveTopLevelValueSymbol(context, assignment.name);
                if (symbolName == null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            assignment.name,
                            "Unknown value '" + assignment.name.lexeme() + "'."));
                    yield TypeDescriptor.ofInfer();
                }
                assignment.setResolvedSymbolToken(symbolName);
                final var binding = context.symbols.getSymbol(symbolName);
                if (!binding.mutability().isReassignable()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            assignment.name,
                            "Cannot reassign immutable binding '" + assignment.name.lexeme() + "'."));
                }
                final var expectedType = binding.type();
                final var resolvedType = MemberInteropResolver.resolveArgument(context, assignment.value, expectedType);
                ensureAssignable(context, expectedType, resolvedType, assignment.name);

                if (context.frame.flowState.isReachable()) {
                    context.frame.flowState.remove(binding.name());
                    if (context.frame.resolvingPattern
                            && context.frame.patternOutputNames.contains(binding.name().lexeme())) {
                        context.frame.flowState.recordWrite(binding.name());
                    }
                    for (final var writeScope : context.frame.flowWriteScopes) writeScope.add(binding.name());
                }
                context.symbols.define(symbolName);
                assignment.setType(expectedType);
                yield expectedType;
            }
            case Expr.CoalesceAssignment assignment -> resolveCoalesceAssignment(context, assignment);
            case Expr.Binary binary -> {
                if (binary.operator.type() == TokenType.EQUAL_EQUAL_EQUAL) {
                    final var leftType = resolve(context, binary.left);
                    final var rightType = resolve(context, binary.right);
                    final var leftIsNull = leftType instanceof NullDescriptor;
                    final var rightIsNull = rightType instanceof NullDescriptor;
                    if (leftIsNull && rightIsNull
                            || leftIsNull && !isIdentityComparable(context, rightType)
                            || rightIsNull && !isIdentityComparable(context, leftType)) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                                binary.operator,
                                "'===' requires reference-valued operands; nullable primitives and Unit "
                                        + "are not supported."));
                    }
                    if (!leftIsNull && !rightIsNull) {
                        if (!isIdentityComparable(context, leftType) || !isIdentityComparable(context, rightType)) {
                            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                                    binary.operator,
                                    "'===' requires reference-valued operands; nullable primitives and Unit "
                                            + "are not supported."));
                        }
                        final var leftView = identityViewType(context, leftType);
                        final var rightView = identityViewType(context, rightType);
                        if (!context.typeCompatibility.canAssign(leftView, rightView)
                                && !context.typeCompatibility.canAssign(rightView, leftView)) {
                            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                                    binary.operator,
                                    "'===' operands must have compatible reference types."));
                        }
                    }
                    binary.setType(TypeDescriptor.ofBoolean());
                    yield TypeDescriptor.ofBoolean();
                }
                if ((binary.operator.type() == TokenType.EQUAL_EQUAL
                        || binary.operator.type() == TokenType.BANG_EQUAL)
                        && (isNullLiteral(context, binary.left) || isNullLiteral(context, binary.right))) {
                    final var leftType = resolve(context, binary.left);
                    final var rightType = resolve(context, binary.right);
                    final var comparedType = isNullLiteral(context, binary.left) ? rightType : leftType;
                    if (!isNullLiteral(context, binary.left) && !isNullLiteral(context, binary.right)
                            || isPrimitive(context, comparedType) && !comparedType.isNullable()) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NULLABLE_VALUE_REQUIRES_HANDLING,
                                binary.operator,
                                "Null comparisons require a nullable or reference value."));
                    }
                    binary.setType(TypeDescriptor.ofBoolean());
                    yield TypeDescriptor.ofBoolean();
                }
                final var leftType = resolve(context, binary.left);
                final var rightType = resolve(context, binary.right);
                final var refinedLeftType = LambdaResolver.refineInferredType(
                        context, binary.left, leftType, rightType);
                final var refinedRightType = LambdaResolver.refineInferredType(
                        context, binary.right, rightType, leftType);
                Zeron.debug("resolving binary   " + refinedLeftType + " "
                    + binary.operator.lexeme() + " " + refinedRightType);
                if (binary.operator.type() == TokenType.PLUS
                        && refinedLeftType instanceof StringDescriptor) {
                    final var stringType = resolveStringConcatenation(context, binary, refinedRightType);
                    binary.setType(stringType);
                    yield stringType;
                }
                final var equalityOperator = binary.operator.type() == TokenType.EQUAL_EQUAL
                        || binary.operator.type() == TokenType.BANG_EQUAL;
                if (!equalityOperator && !isRelationalOperator(binary.operator.type())
                        && (TypeSubstitution.containsTypeParameter(refinedLeftType)
                        || TypeSubstitution.containsTypeParameter(refinedRightType))) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                            binary.operator,
                        "Operators on generic type parameters require constraints, which are not supported."));
                }
                if ((!equalityOperator && (refinedLeftType.isNullable() || refinedRightType.isNullable()))
                        || refinedLeftType instanceof NullDescriptor || refinedRightType instanceof NullDescriptor) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NULLABLE_VALUE_REQUIRES_HANDLING,
                            binary.operator,
                        "Nullable operands require a null check before using this operator."));
                }
                ensureExact(context, binary.operator, refinedLeftType, refinedRightType);
                if (equalityOperator) {
                    resolveEquality(context, binary, refinedLeftType);
                    binary.setType(TypeDescriptor.ofBoolean());
                    yield TypeDescriptor.ofBoolean();
                }
                if (isRelationalOperator(binary.operator.type())) {
                    if (refinedLeftType.isNullable() || refinedRightType.isNullable()
                            || refinedLeftType instanceof NullDescriptor
                            || refinedRightType instanceof NullDescriptor) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NULLABLE_VALUE_REQUIRES_HANDLING,
                                binary.operator,
                                "Nullable operands require a null check before using this operator."));
                    }
                    ensureExact(context, binary.operator, refinedLeftType, refinedRightType);
                    if (!(refinedLeftType instanceof IntDescriptor)
                            && !(refinedLeftType instanceof FloatDescriptor)) {
                        resolveComparableComparison(context, binary, refinedLeftType);
                    }
                    binary.setType(TypeDescriptor.ofBoolean());
                    yield TypeDescriptor.ofBoolean();
                }
                if (!(refinedLeftType instanceof InferDescriptor)
                    && !(refinedRightType instanceof InferDescriptor)) {
                    final var validOperands = switch (binary.operator.type()) {
                    case PLUS -> refinedLeftType instanceof IntDescriptor
                        || refinedLeftType instanceof FloatDescriptor
                        || refinedLeftType instanceof StringDescriptor;
                    case MINUS, STAR, SLASH, PERCENT -> refinedLeftType instanceof IntDescriptor
                        || refinedLeftType instanceof FloatDescriptor;
                    case AMPERSAND, PIPE, CARET, SHIFT_LEFT, SHIFT_RIGHT, UNSIGNED_SHIFT_RIGHT ->
                        refinedLeftType instanceof IntDescriptor;
                    default -> false;
                    };
                    if (!validOperands) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                            binary.operator,
                        switch (binary.operator.type()) {
                            case AMPERSAND, PIPE, CARET, SHIFT_LEFT, SHIFT_RIGHT, UNSIGNED_SHIFT_RIGHT ->
                                "Bitwise operators require Int operands.";
                            default -> "Arithmetic operators require numeric operands, except String concatenation with '+'.";
                        }));
                    }
                }
                final var resolvedType = refinedLeftType.orElse(refinedRightType);

                binary.setType(resolvedType);
                yield resolvedType;
            }
            case Expr.Call call -> {
                final var resolved = context.callResolver.resolve(call);
                if (call.genericFunctionType() != null) {
                    RaisedEffectFlow.add(context,
                            new LinkedHashSet<>(call.genericFunctionType().raisedEffects()), call.callee);
                }
                if (call.implicitMemberCall() != null
                        && call.implicitMemberCall().resolvedDescriptor() != null) {
                    RaisedEffectFlow.add(context, new LinkedHashSet<>(
                            call.implicitMemberCall().resolvedDescriptor().raisedEffects()), call.callee);
                }
                yield resolved;
            }
            case Expr.Grouping grouping -> {
                final var resolvedType = resolve(context, grouping.expression);
                grouping.setType(resolvedType);
                yield resolvedType;
            }
            case Expr.If iff -> {
                final var incoming = context.frame.flowState.copy();
                final var conditionFlows = resolveCondition(context, iff.condition, incoming, iff.paren);
                context.frame.flowState = conditionFlows.whenTrue().copy();
                final var matchBindings = beginSuccessfulSumCaseTestScope(context, iff.condition);
                final TypeDescriptor thenType;
                final FlowState thenFlow;
                try {
                    thenType = resolve(context, iff.thenExpr);
                } finally {
                    endSuccessfulSumCaseTestScope(context, matchBindings);
                }
                thenFlow = context.frame.flowState.copy();
                context.frame.flowState = conditionFlows.whenFalse().copy();
                final var elseType = resolve(context, iff.elseExpr);
                final var elseFlow = context.frame.flowState.copy();
                final var commonType = ensureCommonParent(context, iff.paren, thenType, elseType);
                context.frame.flowState = FlowState.join(thenFlow, elseFlow);
                iff.setType(commonType);
                yield commonType;
            }
            case Expr.Pipeline pipeline -> resolvePipeline(context, pipeline);
            case Expr.Match match -> resolveMatch(context, match);
            case Expr.SumCaseTest test -> resolveSumCaseTest(context, test);
            case Expr.Raise raise -> {
                final var effectType = resolve(context, raise.effect);
                final var nominalType = effectType instanceof ReferenceDescriptor reference
                        ? reference.baseType() : effectType;
                TypeResolver.requireEffectType(context, nominalType, raise.keyword);
                RaisedEffectFlow.add(context, nominalType, raise.keyword);
                raise.setType(TypeDescriptor.ofNever());
                yield TypeDescriptor.ofNever();
            }
            case Expr.Handle handle -> resolveHandle(context, handle);
            case Expr.Coalesce coalesce -> {
                final var leftType = resolve(context, coalesce.left);
                final var afterLeft = context.frame.flowState.copy();
                if (!(leftType instanceof NullableDescriptor) && !(leftType instanceof NullDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NULLABLE_VALUE_REQUIRES_HANDLING,
                            coalesce.operator,
                            "The left operand of '??' must be nullable."));
                }

                final var nonNullType = leftType instanceof NullableDescriptor nullable
                        ? nullable.baseType()
                        : TypeDescriptor.ofInfer();
                coalesce.setLeftNonNullType(nonNullType);
                final var nonNullFlow = leftType instanceof NullDescriptor
                        ? FlowState.unreachable()
                        : afterLeft.copy();
                final var nullFlow = afterLeft.copy();
                if (!(leftType instanceof NullDescriptor)) {
                    final var variable = directVariable(context, coalesce.left);
                    if (variable != null && isRefinable(context, variable.name)) {
                        final var binding = context.symbols.getSymbol(variable.name);
                        final var existingFact = afterLeft.get(binding.name());
                        final var nonNullFact = existingFact == null
                                ? nonNullFact(context, binding.type())
                                : new FlowFact(false, existingFact.nonNullAlternatives());
                        nonNullFlow.put(binding.name(), nonNullFact);
                        nullFlow.put(binding.name(), new FlowFact(true, Set.of()));
                    }
                }

                context.frame.flowState = nullFlow;
                final var fallbackType = resolve(context, coalesce.right);
                final var fallbackFlow = context.frame.flowState.copy();
                final TypeDescriptor resultType;
                if (leftType instanceof NullDescriptor) {
                    if (fallbackType instanceof NullDescriptor) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_INFERENCE_FAILURE,
                                coalesce.operator,
                                "The result type of 'null ?? null' cannot be inferred."));
                    }
                    resultType = fallbackType;
                } else {
                    resultType = ensureCommonParent(context, coalesce.operator, nonNullType, fallbackType);
                }

                context.frame.flowState = FlowState.join(nonNullFlow, fallbackFlow);
                coalesce.setType(resultType);
                yield resultType;
            }
            // suggested type for lambdas will always be inferred,
            // but they need to be structurally inferred. we can extract
            // arity from the parameter count and infer a return type from
            // the body.
            case Expr.Lambda lambda -> {
                context.trackLambda(lambda);
                ensureImmutableCaptures(context, lambda);
                final var resolvedType = LambdaResolver.inferLambdaType(context, lambda);
                lambda.setType(resolvedType);
                yield resolvedType;
            }
            case Expr.Literal literal ->
                    literal.getType();
            case Expr.TypeTest test -> {
                yield resolveTypeTest(context, test);
            }
            case Expr.Cast cast -> {
                final var sourceType = resolve(context, cast.value);
                validateRuntimeTestTarget(context, cast.targetType, cast.operator);
                if (!context.typeCompatibility.canTypeTest(sourceType, cast.targetType)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                            cast.operator,
                            "Cast is impossible between " + sourceType + " and " + cast.targetType + "."));
                }
                if (!cast.safe && (sourceType.isNullable() || sourceType instanceof NullDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NULLABLE_VALUE_REQUIRES_HANDLING,
                            cast.operator,
                            "A checked cast from a nullable value requires a non-null flow proof."));
                }
                final var resultType = cast.safe ? cast.targetType.toNullable() : cast.targetType;
                cast.setType(resultType);
                yield resultType;
            }
            case Expr.Logical logical -> {
                final var incoming = context.frame.flowState.copy();
                final Set<Token> writes = Collections.newSetFromMap(new IdentityHashMap<>());
                context.frame.flowWriteScopes.push(writes);
                try {
                    resolveCondition(context, logical, incoming, logical.operator);
                } finally {
                    context.frame.flowWriteScopes.pop();
                }
                context.frame.flowState = incoming;
                for (final var written : writes) context.frame.flowState.remove(written);
                logical.setType(TypeDescriptor.ofBoolean());
                yield TypeDescriptor.ofBoolean();
            }
            case Expr.Unary unary -> {
                final var operandType = resolve(context, unary.right);
                if (TypeSubstitution.containsTypeParameter(operandType)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                            unary.operator,
                            "Unary operators on generic type parameters require constraints, which are not supported."));
                }
                if (unary.operator.type() == TokenType.NOT) {
                    ensureBoolean(context, operandType, unary.operator);
                    unary.setType(TypeDescriptor.ofBoolean());
                    yield TypeDescriptor.ofBoolean();
                }
                if (unary.operator.type() == TokenType.TILDE) {
                    if (!(operandType instanceof IntDescriptor)) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                                unary.operator,
                                "Bitwise complement requires an Int operand."));
                    }
                    unary.setType(TypeDescriptor.ofInt());
                    yield TypeDescriptor.ofInt();
                }
                if (unary.operator.type() != TokenType.MINUS
                        && unary.operator.type() != TokenType.PLUS) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                            unary.operator,
                            "The 'typeof' operator is not implemented."));
                }
                if (operandType instanceof IntDescriptor || operandType instanceof FloatDescriptor) {
                    unary.setType(operandType);
                    yield operandType;
                }
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                        unary.operator,
                        "Unary '+' and '-' require a non-null Int or Float operand."));
                yield TypeDescriptor.ofInfer();
            }
            case Expr.Variable variable -> {
                if (variable.getType() instanceof TypeParameterDescriptor parameter) {
                    yield parameter;
                }
                if (variable.resolvedFunctionName() != null) {
                    yield variable.specializedFunctionType();
                }
                if (!variable.explicitFunctionTypeArguments.isEmpty()) {
                    yield LambdaResolver.resolveFunctionReference(context, variable, null);
                }
                final var name = variable.name;
                if (context.frame.initializerVisibleFields != null && name.type() != TokenType.THIS) {
                    final var fieldType = MemberInteropResolver.resolveImplicitFieldRead(context, variable);
                    if (fieldType == null) {
                        Zeron.resolutionError(new ResolutionError(
                                DiagnosticCatalog.INVALID_INITIALIZER, name,
                                "A field initializer may read only fields declared earlier."));
                    }
                    yield fieldType;
                }
                final var valueSymbol = MemberInteropResolver.resolveTopLevelValueSymbol(context, name);
                final var resolvedSymbol = context.symbols.containsSymbol(name) ? name : valueSymbol;
                if (resolvedSymbol == null) {
                    final var functionName = MemberInteropResolver.resolveFunctionName(context, name.lexeme(), name);
                    if (functionName != null) {
                        final var functionType = (FunctionDescriptor) context.symbols
                                .getFunction(context.functionSymbolTokens.get(functionName)).type();
                        if (functionType.isGeneric()) {
                            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                                    name,
                                    "A generic function value needs explicit type arguments or an expected function type."));
                        }
                        yield LambdaResolver.resolveFunctionReference(context, variable, null);
                    }
                    final var implicitFieldType = MemberInteropResolver.resolveImplicitFieldRead(context, variable);
                    if (implicitFieldType != null) yield implicitFieldType;
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND, name,
                            "Unknown symbol: '" + name.lexeme() + "'."));
                    yield TypeDescriptor.ofInfer();
                }
                Zeron.debug("resolving variable lookup   " + name.lexeme());
                if (!context.symbols.getSymbol(resolvedSymbol).isInit()
                        && context.symbols.getSymbol(resolvedSymbol).lvt() != SymbolTable.GLOBAL) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.INVALID_INITIALIZER, name,
                            "Can't read local variable in its own initializer."));
                }

                final var binding = context.symbols.getSymbol(resolvedSymbol);
                final var resolvedType = effectiveType(context, binding);
                if (binding.declaration() instanceof Stmt.Var value
                        && context.topLevelTokensByDeclaration.containsKey(value)) {
                    variable.setResolvedValue(resolvedSymbol, value);
                }
                variable.setType(resolvedType);
                Zeron.debug(" -> " + resolvedType);
                yield resolvedType;
            }
        };
    }

    private static TypeDescriptor resolveStringConcatenation(final ResolutionContext context,
                                                             final Expr.Binary binary,
                                                             final TypeDescriptor rightType) {
        final var nullable = rightType instanceof NullableDescriptor;
        var valueType = nullable ? ((NullableDescriptor) rightType).baseType() : rightType;
        if (valueType instanceof ReferenceDescriptor reference) valueType = reference.baseType();
        binary.setNullableStringification(nullable || valueType instanceof NullDescriptor);
        if (valueType instanceof NullDescriptor || valueType instanceof StringDescriptor
                || valueType instanceof IntDescriptor || valueType instanceof FloatDescriptor
                || valueType instanceof BooleanDescriptor || valueType instanceof UnitDescriptor) {
            binary.setBuiltInStringification(true);
            return TypeDescriptor.ofString();
        }

        final var display = context.contracts.values().stream()
                .filter(contract -> contract.name().lexeme().equals("zeron.lang.Display"))
                .findFirst().orElse(null);
        if (display == null || display.typeParameters().size() != 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    binary.operator, "String concatenation requires the zeron.lang.Display contract."));
        }
        final var displayType = TypeDescriptor.genericOf(TypeDescriptor.ofName(display.name().lexeme()), valueType);
        final var method = display.methods().stream()
                .filter(candidate -> candidate.name().lexeme().equals("toString"))
                .findFirst().orElse(null);
        if (method == null || method.typeDescriptor().arity() != 0
                || !(method.typeDescriptor().returnType() instanceof StringDescriptor)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    display.name(), "Display must declare toString(): String."));
        }

        final var token = new Token(TokenType.IDENTIFIER, "toString", null, binary.operator.span());
        final var call = new Expr.MemberCall(binary.right, token, binary.operator,
                List.of(), List.of(), TypeDescriptor.ofString());
        if (valueType instanceof TypeParameterDescriptor parameter) {
            if (parameter.bounds().stream().noneMatch(displayType::equals)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        binary.operator, "String concatenation requires an explicit Display<T> bound."));
            }
            MemberInteropResolver.resolveMemberCall(context, call);
            binary.setDisplayCall(call);
            return TypeDescriptor.ofString();
        }

        if (context.typeCompatibility.isContractProjection(displayType, valueType)) {
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            substitutions.put(display.typeParameters().getFirst(), valueType);
            call.setResolvedOwnerName(display.name().lexeme());
            call.setResolvedContractMethod(method);
            call.setResolvedDescriptor((FunctionDescriptor) TypeSubstitution.substitute(
                    method.typeDescriptor(), substitutions));
            call.setType(TypeDescriptor.ofString());
            binary.setDisplayCall(call);
            return TypeDescriptor.ofString();
        }

        final var resolved = MemberInteropResolver.resolveWitnessMethodForContract(
                context, call, valueType, displayType);
        if (resolved == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    binary.operator, "No Display witness is in scope for '" + valueType + "'."));
        }
        binary.setDisplayCall(call);
        return TypeDescriptor.ofString();
    }

    private static void resolveEquality(final ResolutionContext context,
                                       final Expr.Binary binary,
                                       final TypeDescriptor operandType) {
        final var operandBaseType = operandType instanceof ReferenceDescriptor reference
                ? reference.baseType() : operandType;
        final var nullableBaseType = operandBaseType instanceof NullableDescriptor nullable
                ? nullable.baseType() : operandBaseType;
        final var comparableType = nullableBaseType instanceof ReferenceDescriptor reference
                ? reference.baseType() : nullableBaseType;
        if (comparableType instanceof IntDescriptor || comparableType instanceof FloatDescriptor
                || comparableType instanceof BooleanDescriptor || comparableType instanceof StringDescriptor
                || comparableType instanceof UnitDescriptor) {
            return;
        }

        final var equalityContract = context.contracts.get("zeron.lang.Eq");
        if (equalityContract == null || equalityContract.typeParameters().size() != 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    binary.operator, "The standard Eq<T> contract is not available."));
        }
        final var equalityTarget = operandBaseType instanceof NullableDescriptor
                ? operandBaseType : comparableType;
        final var equalityType = TypeDescriptor.genericOf(
                TypeDescriptor.ofName(equalityContract.name().lexeme()), equalityTarget);
        if (comparableType instanceof TypeParameterDescriptor parameter
                && parameter.bounds().stream().noneMatch(bound ->
                        bound.equals(TypeDescriptor.genericOf(
                                TypeDescriptor.ofName(equalityContract.name().lexeme()), parameter)))) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    binary.operator, "Equality on type parameter '" + parameter.name()
                            + "' requires an explicit Eq<" + parameter.name() + "> bound."));
        }
        if (!TypeClassEvidence.hasEvidence(context, equalityType, equalityTarget, binary.operator)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    binary.operator, "No Eq witness is in scope for '" + equalityTarget + "'."));
        }
        final var equalityEvidence = TypeClassEvidence.selectEqualityMethod(
                context, equalityTarget, binary.operator);
        if (!(equalityEvidence.signature().returnType() instanceof BooleanDescriptor)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE,
                    binary.operator, "Eq.eq must return Boolean."));
        }
        binary.setSynthesizedEqualityEvidence(equalityEvidence);
    }

    private static boolean isRelationalOperator(final TokenType operator) {
        return operator == TokenType.GREATER || operator == TokenType.GREATER_EQUAL
                || operator == TokenType.LESS || operator == TokenType.LESS_EQUAL;
    }

    private static void resolveComparableComparison(final ResolutionContext context,
                                                    final Expr.Binary binary,
                                                    final TypeDescriptor operandType) {
        final var comparableContract = context.contracts.get("zeron.lang.Comparable");
        final var ordering = context.sums.get("zeron.lang.Ordering");
        if (comparableContract == null || comparableContract.typeParameters().size() != 1 || ordering == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    binary.operator, "The standard Comparable<T> and Ordering declarations are not available."));
        }
        final var orderingCases = Set.of("LT", "EQ", "GT");
        if (ordering.cases().size() != orderingCases.size()
                || ordering.cases().stream().map(sumCase -> sumCase.name().lexeme()).distinct().count()
                        != orderingCases.size()
                || ordering.cases().stream().anyMatch(sumCase ->
                        !orderingCases.contains(sumCase.name().lexeme()) || !sumCase.payloads().isEmpty())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    binary.operator, "Ordering must contain exactly the nullary cases LT, EQ, and GT."));
        }
        final var comparableType = operandType;
        final var comparableContractType = TypeDescriptor.genericOf(
                TypeDescriptor.ofName(comparableContract.name().lexeme()), comparableType);
        final var token = new Token(TokenType.IDENTIFIER, "compareTo", null, binary.operator.span());
        final var call = new Expr.MemberCall(binary.left, token, binary.operator,
                List.of(binary.right), List.of(), TypeDescriptor.ofInfer());
        if (!TypeClassEvidence.hasEvidence(context, comparableContractType, comparableType, binary.operator)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    binary.operator, "No Comparable<" + comparableType + "> witness is in scope."));
        }
        final TypeDescriptor returnType;
        if (comparableType instanceof TypeParameterDescriptor
                || context.typeCompatibility.isContractProjection(comparableContractType, comparableType)) {
            returnType = MemberInteropResolver.resolveMemberCall(context, call);
        } else {
            returnType = MemberInteropResolver.resolveWitnessMethodForContract(
                    context, call, comparableType, comparableContractType);
        }
        if (returnType == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    binary.operator, "Comparable<" + comparableType + "> has no compareTo method."));
        }
        final var expectedOrdering = TypeDescriptor.ofName(ordering.name().lexeme());
        if (!expectedOrdering.equals(returnType)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_INFERENCE_FAILURE,
                    binary.operator, "Comparable.compareTo must return zeron.lang.Ordering."));
        }
        binary.setComparisonCall(call);
        RaisedEffectFlow.add(context, new LinkedHashSet<>(call.resolvedDescriptor().raisedEffects()),
                binary.operator);
    }

    private static TypeDescriptor resolveHandle(final ResolutionContext context, final Expr.Handle handle) {
        final var enclosingEffects = RaisedEffectFlow.beginHandledExpression(context);
        TypeDescriptor protectedType;
        final var fullyHandledEffects = new LinkedHashSet<TypeDescriptor>();
        final var armPatterns = new ArrayList<MatchPatternResolution>();
        try {
            protectedType = resolve(context, handle.expression);
            for (final var arm : handle.arms) {
                final var effect = TypeResolver.requireEffectType(context, arm.effectType(), arm.keyword());
                arm.setResolvedEffectType(effect);
                final var pattern = resolveMatchPattern(context, arm.pattern(), effect, false,
                        Set.of(), List.of(), MatchRootKind.SEALED);
                armPatterns.add(pattern);
                if (arm.guard() == null && pattern.irrefutable()) fullyHandledEffects.add(effect);
            }
        } finally {
            RaisedEffectFlow.endHandledExpression(
                    context, enclosingEffects, fullyHandledEffects, handle.keyword);
        }

        TypeDescriptor resultType = protectedType instanceof NeverDescriptor ? null : protectedType;
        final var armsFlow = new ArrayList<FlowState>();
        final var protectedFlow = context.frame.flowState.copy();
        for (int i = 0; i < handle.arms.size(); i++) {
            final var arm = handle.arms.get(i);
            final var pattern = armPatterns.get(i);
            context.frame.flowState = protectedFlow.copy();
            context.symbols.beginScope();
            try {
                for (final var binding : pattern.bindings().values()) {
                    context.symbols.declareSymbol(SYNTHETIC_VAR, binding.name(),
                            binding.type(), BindingMutability.IMMUTABLE);
                    context.symbols.define(binding.name());
                }
                if (arm.suppressedBinding() != null) {
                    final var suppressedType = TypeDescriptor.arrayOf(TypeDescriptor.ofAny().toNullable());
                    context.symbols.declareSymbol(SYNTHETIC_VAR, arm.suppressedBinding(),
                            suppressedType, BindingMutability.IMMUTABLE);
                    context.symbols.define(arm.suppressedBinding());
                }
                if (arm.guard() != null) {
                    final var guardType = resolve(context, arm.guard());
                    ensureAssignable(context, TypeDescriptor.ofBoolean(), guardType, arm.keyword());
                }
                final var armType = resolve(context, arm.expression());
                resultType = resultType == null ? armType
                        : ensureCommonParent(context, arm.keyword(), resultType, armType);
                armsFlow.add(context.frame.flowState.copy());
            } finally {
                context.symbols.endScope();
            }
        }
        var joined = protectedType instanceof NeverDescriptor
                ? FlowState.unreachable() : protectedFlow;
        for (final var flow : armsFlow) joined = FlowState.join(joined, flow);
        context.frame.flowState = joined;
        if (resultType == null) resultType = protectedType;
        handle.setType(resultType);
        return resultType;
    }

    static TypeDescriptor resolveExpression(final ResolutionContext context, final Expr expression) {
        return resolve(context, expression);
    }

    static TypeDescriptor resolveEmptyArrayLiteral(final ResolutionContext context,
                                                    final Expr.ArrayLiteral literal,
                                                    TypeDescriptor expectedType) {
        if (expectedType instanceof NullableDescriptor nullable) expectedType = nullable.baseType();
        if (expectedType instanceof ReferenceDescriptor reference) expectedType = reference.baseType();
        if (!(expectedType instanceof ArrayDescriptor arrayType)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TYPE_INFERENCE_FAILURE,
                    SYNTHETIC_IDENTIFIER,
                    "An empty array literal requires an expected Array<T> type."));
            throw new IllegalStateException("unreachable");
        }
        final var operation = IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.ARRAY_LITERAL,
                List.of(arrayType.elementType()), List.of(), SYNTHETIC_IDENTIFIER);
        literal.setIntrinsicOperation(operation);
        literal.setType(operation.resultType());
        return operation.resultType();
    }

    private static TypeDescriptor resolvePipeline(final ResolutionContext context, final Expr.Pipeline pipeline) {
        var sourceType = resolve(context, pipeline.source);
        if (sourceType instanceof ReferenceDescriptor ref) {
            sourceType = ref.baseType();
        }
        if (sourceType instanceof NullableDescriptor) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NULLABLE_VALUE_REQUIRES_HANDLING,
                    pipeline.keyword,
                    "For-expressions cannot take nullable values as a source; prove the value non-null first."));
        }
        var elementType = Resolver.ensureIterable(context, sourceType, pipeline.keyword);
        pipeline.setSourceElementType(elementType);
        if (!(sourceType instanceof ArrayDescriptor)) {
            pipeline.setIterationProtocol(context.iterationProtocols.get(pipeline.keyword));
        }
        TypeDescriptor sinkType = null;
        TypeDescriptor sinkElementType = null;
        for (int stageIndex = 0; stageIndex < pipeline.stages.size(); stageIndex++) {
            final var stage = pipeline.stages.get(stageIndex);
            if (stage instanceof Expr.PipelineStage.Collect && stageIndex != pipeline.stages.size() - 1) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_PIPELINE_OPERATION,
                        stage.keyword(), "The collect stage must be the final for-expression stage."));
            }
            elementType = switch (stage) {
                case Expr.PipelineStage.Map m -> {
                    final var parameter = m.wildcard() ? Resolver.SYNTHETIC_IDENTIFIER : m.binding();
                    final var lambda = new Expr.Lambda(m.keyword(), List.of(parameter),
                            List.of(new Stmt.Return(m.expression(), m.keyword())),
                            TypeDescriptor.functionOf("", TypeDescriptor.ofInfer(), elementType));
                    final var mappedType = Resolver.resolveLambda(context, lambda,
                            TypeDescriptor.functionOf("", TypeDescriptor.ofInfer(), elementType)).returnType();
                    m.setLambda(lambda);
                    m.setType(mappedType);
                    yield mappedType;
                }
                case Expr.PipelineStage.FlatMap f -> {
                    final var parameter = f.wildcard() ? Resolver.SYNTHETIC_IDENTIFIER : f.binding();
                    final var lambda = new Expr.Lambda(f.keyword(), List.of(parameter),
                            List.of(new Stmt.Return(f.expression(), f.keyword())),
                            TypeDescriptor.functionOf("", TypeDescriptor.ofInfer(), elementType));
                    final var resultType = Resolver.resolveLambda(context, lambda,
                            TypeDescriptor.functionOf("", TypeDescriptor.ofInfer(), elementType)).returnType();
                    final var flattenedType = Resolver.ensureIterable(context, resultType, f.keyword());
                    if (resultType instanceof ArrayDescriptor
                            || resultType instanceof ReferenceDescriptor reference
                            && reference.baseType() instanceof ArrayDescriptor) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_PIPELINE_OPERATION,
                                f.keyword(), "A flatMap stage must return an Iterable<T> value."));
                    }
                    f.setLambda(lambda);
                    f.setType(flattenedType);
                    yield flattenedType;
                }
                case Expr.PipelineStage.Filter f -> {
                    final var parameter = f.wildcard() ? Resolver.SYNTHETIC_IDENTIFIER : f.binding();
                    final var lambda = new Expr.Lambda(f.keyword(), List.of(parameter),
                            List.of(new Stmt.Return(f.expression(), f.keyword())),
                            TypeDescriptor.functionOf("", TypeDescriptor.ofBoolean(), elementType));
                    Resolver.resolveLambda(context, lambda,
                            TypeDescriptor.functionOf("", TypeDescriptor.ofBoolean(), elementType));
                    f.setLambda(lambda);
                    yield elementType;
                }
                case Expr.PipelineStage.Drop d -> {
                    final var countType = resolveExpression(context, d.expression());
                    Resolver.ensureAssignable(context, TypeDescriptor.ofInt(), countType, d.keyword());
                    d.setType(elementType);
                    yield elementType;
                }
                case Expr.PipelineStage.Take t -> {
                    final var countType = resolveExpression(context, t.expression());
                    Resolver.ensureAssignable(context, TypeDescriptor.ofInt(), countType, t.keyword());
                    t.setType(elementType);
                    yield elementType;
                }
                case Expr.PipelineStage.DropWhile d -> {
                    final var parameter = d.wildcard() ? Resolver.SYNTHETIC_IDENTIFIER : d.binding();
                    final var lambda = new Expr.Lambda(d.keyword(), List.of(parameter),
                            List.of(new Stmt.Return(d.expression(), d.keyword())),
                            TypeDescriptor.functionOf("", TypeDescriptor.ofBoolean(), elementType));
                    Resolver.resolveLambda(context, lambda,
                            TypeDescriptor.functionOf("", TypeDescriptor.ofBoolean(), elementType));
                    d.setLambda(lambda);
                    yield elementType;
                }
                case Expr.PipelineStage.TakeWhile t -> {
                    final var parameter = t.wildcard() ? Resolver.SYNTHETIC_IDENTIFIER : t.binding();
                    final var lambda = new Expr.Lambda(t.keyword(), List.of(parameter),
                            List.of(new Stmt.Return(t.expression(), t.keyword())),
                            TypeDescriptor.functionOf("", TypeDescriptor.ofBoolean(), elementType));
                    Resolver.resolveLambda(context, lambda,
                            TypeDescriptor.functionOf("", TypeDescriptor.ofBoolean(), elementType));
                    t.setLambda(lambda);
                    yield elementType;
                }
                case Expr.PipelineStage.Collect c -> {
                    sinkType = resolveExpression(context, stage.expression());
                    c.setType(sinkType);
                    if (!(sinkType instanceof ReferenceDescriptor)) {
                        Zeron.resolutionError(new ResolutionError(
                                DiagnosticCatalog.INVALID_COLLECTOR, c.keyword(),
                                "The collect stage requires a mutable reference to a type conforming to Sink<T>."));
                    }
                    sinkElementType = Resolver.ensureSink(context, sinkType, c.keyword());
                    Resolver.ensureAssignable(context, sinkElementType, elementType, c.keyword());
                    yield elementType;
                }
            };
        }
        pipeline.setSinkProtocol(new Resolver.SinkProtocol("zeron.collections.Sink", "empty", "add"));
        final var resultType = sinkType == null
                ? TypeDescriptor.genericOf(TypeDescriptor.ofName("zeron.collections.Stream"), elementType)
                : sinkType;
        pipeline.setType(resultType);
        return resultType;
    }

    private static TypeDescriptor resolveMatch(final ResolutionContext context, final Expr.Match match) {
        var scrutineeType = resolve(context, match.scrutinee);
        if (scrutineeType instanceof ReferenceDescriptor reference) {
            scrutineeType = reference.baseType();
        }
        if (scrutineeType instanceof NullableDescriptor) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NULLABLE_VALUE_REQUIRES_HANDLING,
                    match.keyword,
                    "Matching nullable values is not supported; prove the value non-null first."));
        }
        final var sealedBound = scrutineeType instanceof TypeParameterDescriptor parameter
                ? parameter.bounds().stream()
                .filter(bound -> {
                    final var contract = context.contracts.get(bound.name());
                    return contract != null && contract.isSealed();
                }).findFirst().orElse(null)
                : null;
        final var matchDomainType = sealedBound == null ? scrutineeType : sealedBound;
        final var sealedContract = context.contracts.get(matchDomainType.name());
        final boolean sealedRoot = sealedContract != null && sealedContract.isSealed();
        final boolean productRoot = context.products.containsKey(scrutineeType.name());
        final boolean literalRoot = isLiteralPatternType(scrutineeType);
        final boolean concreteLiteralRoot = isConcretePatternType(context, scrutineeType)
                && match.arms.stream().anyMatch(arm -> containsLiteralPattern(arm.pattern()));
        final MatchRootKind rootKind;
        if (sealedRoot) {
            rootKind = MatchRootKind.SEALED;
        } else if (productRoot) {
            rootKind = MatchRootKind.PRODUCT;
        } else if (literalRoot && match.arms.stream().anyMatch(arm -> containsLiteralPattern(arm.pattern()))) {
            rootKind = MatchRootKind.LITERAL;
        } else if (concreteLiteralRoot) {
            rootKind = MatchRootKind.CONCRETE_LITERAL;
        } else {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                    match.keyword,
                    "Match expressions require a sealed contract, product type, or literal match on a concrete non-open type."));
            throw new IllegalStateException("Resolution errors must abort match resolution.");
        }
        final var contractArguments = matchDomainType instanceof GenericDescriptor generic
                ? generic.typeParameters()
                : List.<TypeDescriptor>of();
        if (sealedRoot && contractArguments.size() != sealedContract.typeParameters().size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                    match.keyword,
                    "The sealed contract type arguments could not be resolved."));
        }

        final var permittedNames = sealedRoot
                ? sealedContract.permittedClasses().stream()
                .map(permitted -> permitted.name().lexeme())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                : rootKind == MatchRootKind.PRODUCT || rootKind == MatchRootKind.CONCRETE_LITERAL
                ? new LinkedHashSet<>(Set.of(scrutineeType.name()))
                : new LinkedHashSet<String>();
        final var matchedNames = new LinkedHashSet<String>();
        final var matchedLiterals = new LinkedHashSet<LiteralPatternKey>();
        boolean wildcardSeen = false;
        boolean rootCovered = false;
        final var incomingFlow = context.frame.flowState.copy();
        final var armFlows = new ArrayList<FlowState>();
        TypeDescriptor resultType = null;

        for (final var arm : match.arms) {
            if (rootCovered) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        arm.keyword(), "A match case follows an irrefutable case and is unreachable."));
            }
            if (wildcardSeen) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        arm.keyword(),
                        "A match case after '_' is unreachable."));
            }
            final var pattern = resolveMatchPattern(context, arm.pattern(), scrutineeType, true,
                    permittedNames, contractArguments, rootKind);
            if (!arm.pattern().wildcard() && pattern.matchedType() != null
                    && matchedNames.contains(pattern.matchedType().name())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        arm.keyword(), "A match case for '" + pattern.matchedType().name()
                                + "' follows an unguarded case and is unreachable."));
            }
            if (!java.util.Collections.disjoint(matchedLiterals, pattern.coveredLiterals())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        arm.keyword(), "A literal match case is duplicated and unreachable."));
            }
            if (arm.pattern().wildcard()) {
                if (arm.guard() != null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                            arm.keyword(), "A wildcard match case cannot have a guard."));
                }
                wildcardSeen = true;
                rootCovered = true;
            }

            context.frame.flowState = incomingFlow.copy();
            beginScope(context);
            try {
                for (final var binding : pattern.bindings().values()) {
                    declare(context, new Stmt.Var(binding.name(), binding.type(), null,
                                    BindingMutability.IMMUTABLE, false),
                            binding.name(), binding.type(), BindingMutability.IMMUTABLE);
                    define(context, binding.name());
                }
                if (arm.guard() != null) {
                    final var guardType = resolve(context, arm.guard());
                    ensureAssignable(context, TypeDescriptor.ofBoolean(), guardType, arm.keyword());
                }
                final var armResultType = resolve(context, arm.expression());
                resultType = resultType == null
                        ? armResultType
                        : ensureCommonParent(context, arm.keyword(), resultType, armResultType);
                var armFlow = context.frame.flowState.copy();
                if (arm.guard() != null) armFlow = FlowState.join(incomingFlow, armFlow);
                armFlows.add(armFlow);
            } finally {
                endScope(context);
            }
            if (arm.guard() == null) {
                matchedNames.addAll(pattern.coveredClasses());
                matchedLiterals.addAll(pattern.coveredLiterals());
                if (rootKind == MatchRootKind.SEALED && matchedNames.containsAll(permittedNames)
                        || (rootKind == MatchRootKind.PRODUCT || rootKind == MatchRootKind.CONCRETE_LITERAL)
                        && matchedNames.contains(scrutineeType.name())
                        || rootKind == MatchRootKind.LITERAL
                        && TypeDescriptor.ofBoolean().equals(scrutineeType)
                        && matchedLiterals.containsAll(Set.of(
                        new LiteralPatternKey(TypeDescriptor.ofBoolean(), true),
                        new LiteralPatternKey(TypeDescriptor.ofBoolean(), false)))) {
                    rootCovered = true;
                }
            }
        }

        if (!rootCovered) {
            final var missing = rootKind == MatchRootKind.SEALED
                    ? permittedNames.stream().filter(name -> !matchedNames.contains(name))
                    .map(Resolver::simpleName).collect(java.util.stream.Collectors.joining(", "))
                    : rootKind == MatchRootKind.LITERAL && TypeDescriptor.ofBoolean().equals(scrutineeType)
                    ? "true, false"
                    : "a wildcard or total pattern";
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                    match.keyword,
                    "Non-exhaustive match; missing cases: " + missing + "."));
        }

        var joinedFlow = FlowState.unreachable();
        for (final var armFlow : armFlows) joinedFlow = FlowState.join(joinedFlow, armFlow);
        context.frame.flowState = joinedFlow;
        match.setType(resultType);
        return resultType;
    }

    private enum MatchRootKind {
        SEALED,
        PRODUCT,
        LITERAL,
        CONCRETE_LITERAL
    }

    private record LiteralPatternKey(TypeDescriptor type, Object value) {}

    private static boolean isLiteralPatternType(final TypeDescriptor type) {
        return type instanceof IntDescriptor
                || type instanceof BooleanDescriptor
                || type instanceof StringDescriptor;
    }

    private static boolean isConcretePatternType(final ResolutionContext context,
                                                 final TypeDescriptor type) {
        if (!(type instanceof NominalDescriptor || type instanceof GenericDescriptor)) return false;
        final var declaration = context.classes.get(type.name());
        return declaration != null && !declaration.isEffect();
    }

    private static boolean containsLiteralPattern(final Expr.MatchPattern pattern) {
        if (pattern.literal() != null) return true;
        return pattern.alternatives().stream().anyMatch(ExpressionFlowResolver::containsLiteralPattern)
                || pattern.arguments().stream().anyMatch(ExpressionFlowResolver::containsLiteralPattern);
    }

    private record MatchBinding(Token name, TypeDescriptor type) {}

    private record MatchPatternResolution(Map<String, MatchBinding> bindings,
                                          Set<String> coveredClasses,
                                          Set<LiteralPatternKey> coveredLiterals,
                                          boolean irrefutable,
                                          TypeDescriptor matchedType) {}

    private static MatchPatternResolution resolveMatchPattern(
            final ResolutionContext context,
            final Expr.MatchPattern pattern,
            final TypeDescriptor inputType,
            final boolean root,
            final Set<String> permittedNames,
            final List<TypeDescriptor> contractArguments,
            final MatchRootKind rootKind) {
        if (!pattern.alternatives().isEmpty()) {
            pattern.resolve(null, inputType, false);
            final var alternatives = pattern.alternatives().stream()
                    .map(alternative -> resolveMatchPattern(context, alternative, inputType, root,
                            permittedNames, contractArguments, rootKind))
                    .toList();
            final var first = alternatives.getFirst();
            final var names = first.bindings().keySet();
            final var covered = new LinkedHashSet<String>();
            final var coveredLiterals = new LinkedHashSet<LiteralPatternKey>();
            boolean irrefutable = false;
            for (int i = 0; i < alternatives.size(); i++) {
                final var alternative = alternatives.get(i);
                if (!alternative.bindings().keySet().equals(names)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                            pattern.keyword(), "Every OR-pattern alternative must bind the same names."));
                }
                for (final var name : names) {
                    final var firstBinding = first.bindings().get(name);
                    final var otherBinding = alternative.bindings().get(name);
                    if (!context.typeCompatibility.canAssign(firstBinding.type(), otherBinding.type())
                            || !context.typeCompatibility.canAssign(otherBinding.type(), firstBinding.type())) {
                        Zeron.resolutionError(new ResolutionError(
                                DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH, otherBinding.name(),
                                "OR-pattern bindings must have compatible types."));
                    }
                }
                covered.addAll(alternative.coveredClasses());
                coveredLiterals.addAll(alternative.coveredLiterals());
                irrefutable |= alternative.irrefutable();
            }
            return new MatchPatternResolution(first.bindings(), covered, coveredLiterals, irrefutable, inputType);
        }

        if (pattern.literal() != null) {
            final var literalType = pattern.literal().getType();
            if (!isLiteralPatternType(literalType)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        pattern.keyword(), "Literal patterns currently support only Int, Boolean, and String."));
            }
            final var valueType = inputType instanceof ReferenceDescriptor reference
                    ? reference.baseType() : inputType;
            if (valueType instanceof NullableDescriptor || !literalType.equals(valueType)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        pattern.keyword(), "A literal pattern must have the same non-null type as the matched value."));
            }
            pattern.resolve(null, valueType, false);
            final var bindings = new LinkedHashMap<String, MatchBinding>();
            if (pattern.alias() != null) addPatternBinding(context, bindings, pattern.alias(), valueType);
            final var literal = pattern.literal();
            return new MatchPatternResolution(bindings, Set.of(),
                    Set.of(new LiteralPatternKey(literalType, literal.value)), false, valueType);
        }

        if (pattern.wildcard()) {
            pattern.resolve(null, inputType, false);
            return new MatchPatternResolution(Map.of(), root ? permittedNames : Set.of(),
                    Set.of(), true, inputType);
        }
        if (pattern.binding() != null) {
            pattern.resolve(null, inputType, false);
            final var bindings = new LinkedHashMap<String, MatchBinding>();
            addPatternBinding(context, bindings, pattern.binding(), inputType);
            return new MatchPatternResolution(bindings, Set.of(), Set.of(), true, inputType);
        }
        final TypeDescriptor patternType;
        if (pattern.type() == null && pattern.extractor() != null) {
            final var sumType = findUnqualifiedPatternSum(context, inputType, root,
                    permittedNames, pattern.extractor());
            if (sumType != null) {
                patternType = sumType;
            } else {
                final var resolvedName = MemberInteropResolver.resolveClassName(
                        context, pattern.extractor().lexeme(), pattern.extractor());
                if (resolvedName == null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND,
                            pattern.extractor(), "Unknown product type '" + pattern.extractor().lexeme() + "'."));
                }
                patternType = TypeDescriptor.ofName(resolvedName);
            }
        } else if (pattern.type() == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                    pattern.keyword(), "Expected a type, binding, or wildcard pattern."));
            throw new IllegalStateException("Unreachable after reporting an invalid match pattern.");
        } else {
            patternType = resolveStarImportedPatternType(context, pattern);
        }

        final var effectiveInputType = inputType instanceof ReferenceDescriptor reference
                ? reference.baseType() : inputType;
        final var resolvedPatternType = patternType;
        final var productPatternType = pattern.productPattern()
                && context.products.containsKey(resolvedPatternType.name())
                && resolvedPatternType instanceof NominalDescriptor
                && effectiveInputType instanceof GenericDescriptor applied
                && applied.name().equals(resolvedPatternType.name())
                ? effectiveInputType
                : resolvedPatternType;
        final var patternTypeForResolution = productPatternType;
        final var sum = pattern.extractor() == null ? null : context.sums.get(patternTypeForResolution.name());
        if (sum == null) TypeResolver.validateType(context, patternTypeForResolution, pattern.keyword());
        if (patternTypeForResolution instanceof NullableDescriptor
                || patternTypeForResolution instanceof ReferenceDescriptor) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                    pattern.keyword(), "Match case types must be non-null class types."));
        }
        if (sum != null && pattern.extractor() != null) {
            if (!root && !patternTypeForResolution.name().equals(inputType.name())
                    || patternTypeForResolution instanceof GenericDescriptor applied
                    && inputType instanceof GenericDescriptor input
                    && !applied.typeParameters().equals(input.typeParameters())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        pattern.keyword(), "Nested sum patterns must match the extracted sum type."));
            }
            final var sumCase = sum.cases().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(pattern.extractor().lexeme()))
                    .findFirst().orElse(null);
            if (sumCase == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        pattern.extractor(), "Unknown case '" + pattern.extractor().lexeme()
                                + "' for sum '" + sum.name().lexeme() + "'."));
            }
            final var caseName = sum.generatedCaseName(sumCase);
            if (root && !permittedNames.contains(caseName)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        pattern.keyword(), "Sum case '" + pattern.extractor().lexeme()
                                + "' is not permitted by the matched sealed contract."));
            }
            final var patternArguments = patternTypeForResolution instanceof GenericDescriptor generic
                    ? generic.typeParameters() : List.<TypeDescriptor>of();
            final var inputArguments = inputType instanceof GenericDescriptor generic
                    && inputType.name().equals(sum.name().lexeme())
                    ? generic.typeParameters()
                    : List.<TypeDescriptor>of();
            final var expectedSumArguments = !inputArguments.isEmpty() ? inputArguments
                    : root && !contractArguments.isEmpty() ? contractArguments
                    : patternArguments;
            if (!patternArguments.isEmpty() && !patternArguments.equals(expectedSumArguments)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        pattern.keyword(), "Case type arguments must match the scrutinee sum arguments."));
            }
            if (pattern.alias() != null || pattern.arguments().size() != sumCase.payloads().size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        pattern.extractor(), "Sum case expects " + sumCase.payloads().size()
                                + " payload patterns and does not support an alias."));
            }
            final TypeDescriptor appliedSumType = expectedSumArguments.isEmpty()
                    ? TypeDescriptor.ofName(sum.name().lexeme())
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName(sum.name().lexeme()), expectedSumArguments);
            final var substitutions = TypeResolver.substitutionsFor(context, sum.typeParameters(), appliedSumType);
            final var bindings = new LinkedHashMap<String, MatchBinding>();
            boolean refutable = false;
            for (int index = 0; index < sumCase.payloads().size(); index++) {
                final var argument = pattern.arguments().get(index);
                final var payloadType = TypeSubstitution.substitute(
                        sumCase.payloads().get(index).type(), substitutions);
                final var nested = resolveMatchPattern(context, argument, payloadType, false,
                        permittedNames, contractArguments, rootKind);
                mergePatternBindings(context, bindings, nested.bindings(), argument.keyword());
                refutable |= !nested.irrefutable();
            }
            final var caseType = expectedSumArguments.isEmpty()
                    ? TypeDescriptor.ofName(caseName)
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName(caseName), expectedSumArguments);
            pattern.resolve(null, caseType, false);
            pattern.markSumCase();
            return new MatchPatternResolution(bindings, root && !refutable ? Set.of(caseName) : Set.of(),
                    Set.of(), !refutable, caseType);
        }
        final var product = context.products.get(patternTypeForResolution.name());
        if (pattern.productPattern() && product != null) {
            if (pattern.type() != null && pattern.extractor() != null
                    || pattern.arguments().size() != product.components().size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        pattern.keyword(), "Product pattern expects " + product.components().size()
                                + " component patterns."));
            }
            TypeResolver.validateType(context, patternTypeForResolution, pattern.keyword());
            final var classArguments = patternTypeForResolution instanceof GenericDescriptor generic
                    ? generic.typeParameters() : List.<TypeDescriptor>of();
            if (root) {
                if (!permittedNames.contains(patternTypeForResolution.name())
                        || !classArguments.equals(contractArguments)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                            pattern.keyword(), "Product pattern type must match the product scrutinee."));
                }
            } else if (!context.typeCompatibility.canTypeTest(inputType, patternTypeForResolution)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        pattern.keyword(), "Nested product pattern is incompatible with its extracted value."));
            }
            pattern.resolve(null, patternTypeForResolution, false);
            final var bindings = new LinkedHashMap<String, MatchBinding>();
            if (pattern.alias() != null) addPatternBinding(context, bindings, pattern.alias(), patternType);
            final var substitutions = TypeResolver.substitutionsFor(
                    context, product.typeParameters(), patternTypeForResolution);
            boolean refutable = false;
            for (int index = 0; index < product.components().size(); index++) {
                final var argument = pattern.arguments().get(index);
                final var componentType = TypeSubstitution.substitute(
                        product.components().get(index).type(), substitutions);
                final var nested = resolveMatchPattern(context, argument, componentType, false,
                        permittedNames, contractArguments, rootKind);
                mergePatternBindings(context, bindings, nested.bindings(), argument.keyword());
                refutable |= !nested.irrefutable();
            }
            return new MatchPatternResolution(bindings,
                    root && !refutable ? Set.of(patternType.name()) : Set.of(),
                    Set.of(), !refutable, patternType);
        }
        if (pattern.productPattern()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                    pattern.keyword(), "Positional destructuring patterns require a product type."));
        }
        final var classDeclaration = context.classes.get(patternType.name());
        if (classDeclaration == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                    pattern.keyword(), "Match cases must name classes."));
        }
        if (root) {
            if (!permittedNames.contains(patternType.name())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        pattern.keyword(), "Class '" + patternType.name()
                                + "' is not permitted by the sealed contract."));
            }
            final var classArguments = patternType instanceof GenericDescriptor generic
                    ? generic.typeParameters()
                    : List.<TypeDescriptor>of();
            if (!classArguments.equals(contractArguments)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        pattern.keyword(),
                        "Match case type arguments must match the sealed contract's type arguments."));
            }
        } else if (!context.typeCompatibility.canTypeTest(inputType, patternType)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                    pattern.keyword(), "Nested pattern type is incompatible with its extracted value."));
        }

        pattern.resolve(null, patternType, false);
        final var bindings = new LinkedHashMap<String, MatchBinding>();
        if (pattern.alias() != null) addPatternBinding(context, bindings, pattern.alias(), patternType);
        boolean refutable = !root && !patternType.equals(inputType);
        if (pattern.extractor() != null) {
            final var declaration = classDeclaration.patterns().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(pattern.extractor().lexeme()))
                    .findFirst().orElse(null);
            if (declaration != null) {
                if (!declaration.isPublic()
                        && !Objects.equals(context.frame.currentClassName, patternType.name())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                            pattern.extractor(), "Pattern is private."));
                }
                if (declaration.outputs().size() != pattern.arguments().size()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                            pattern.extractor(), "Pattern expects " + declaration.outputs().size()
                                    + " outputs, found " + pattern.arguments().size() + "."));
                }
                pattern.resolve(declaration, patternType, false);
                refutable = declaration.refutable();
                final var substitutions = TypeResolver.substitutionsFor(
                        context, classDeclaration.typeParameters(), patternType);
                for (int i = 0; i < declaration.outputs().size(); i++) {
                    final var outputType = TypeSubstitution.substitute(
                            declaration.outputs().get(i).type(), substitutions);
                    final var nested = resolveMatchPattern(context, pattern.arguments().get(i), outputType,
                            false, permittedNames, contractArguments, rootKind);
                    mergePatternBindings(context, bindings, nested.bindings(), pattern.arguments().get(i).keyword());
                    refutable |= !nested.irrefutable();
                }
            } else {
                final var property = MemberInteropResolver.findProperty(
                        context, patternType.name(), pattern.extractor());
                if (property == null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                            pattern.extractor(), "Class '" + simpleName(patternType.name())
                                    + "' has no public pattern named '" + pattern.extractor().lexeme() + "'."));
                }
                if (!property.isPublic()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                            pattern.extractor(), "Named patterns can only read public properties."));
                }
                if (pattern.arguments().size() != 1) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                            pattern.extractor(), "Legacy property patterns require exactly one argument."));
                }
                final var resolvedOutput = MemberInteropResolver.resolvedPropertyType(
                        context, patternType.name(), property, patternType);
                pattern.resolve(null, patternType, true);
                pattern.setResolvedPatternTypes(property.type(), resolvedOutput);
                final var nested = resolveMatchPattern(context, pattern.arguments().getFirst(), resolvedOutput,
                        false, permittedNames, contractArguments, rootKind);
                mergePatternBindings(context, bindings, nested.bindings(), pattern.arguments().getFirst().keyword());
                refutable = !nested.irrefutable();
            }
        }
        return new MatchPatternResolution(bindings,
                root && !refutable ? Set.of(patternType.name()) : Set.of(),
                Set.of(), !refutable, patternType);
    }

    private static TypeDescriptor findUnqualifiedPatternSum(final ResolutionContext context,
                                                            final TypeDescriptor inputType,
                                                            final boolean root,
                                                            final Set<String> permittedNames,
                                                            final Token caseName) {
        final var inputSum = context.sums.get(inputType.name());
        if (inputSum != null) return inputType;
        if (!root) return null;
        for (final var sum : context.sums.values()) {
            final var permittedCase = sum.cases().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(caseName.lexeme()))
                    .map(sum::generatedCaseName)
                    .anyMatch(permittedNames::contains);
            if (permittedCase) return TypeDescriptor.ofName(sum.name().lexeme());
        }
        return null;
    }

    private static TypeDescriptor resolveStarImportedPatternType(final ResolutionContext context,
                                                                 final Expr.MatchPattern pattern) {
        final var patternType = pattern.type();
        final var sourceTypeName = pattern.keyword().lexeme();
        final var resolvedName = MemberInteropResolver.resolveClassName(
                context, sourceTypeName, pattern.keyword());
        if (resolvedName == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND, pattern.keyword(),
                    "Unknown type '" + sourceTypeName + "'."));
        }
        if (resolvedName.equals(patternType.name())) return patternType;

        final var resolvedBase = TypeDescriptor.ofName(resolvedName);
        return patternType instanceof GenericDescriptor generic
                ? TypeDescriptor.genericOf(resolvedBase, generic.typeParameters())
                : resolvedBase;
    }

    private static void addPatternBinding(final ResolutionContext context,
                                          final Map<String, MatchBinding> bindings,
                                          final Token name,
                                          final TypeDescriptor type) {
        if (bindings.putIfAbsent(name.lexeme(), new MatchBinding(name, type)) != null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                    name, "A pattern cannot bind the same name more than once."));
        }
    }

    static List<Token> beginSuccessfulSumCaseTestScope(final ResolutionContext context,
                                                       final Expr condition) {
        final var candidate = condition instanceof Expr.Grouping grouping
                ? grouping.expression : condition;
        if (!(candidate instanceof Expr.SumCaseTest test)) return List.of();
        final var payloadBindings = test.pattern.arguments().stream()
                .map(Expr.MatchPattern::binding).filter(Objects::nonNull).toList();
        if (payloadBindings.isEmpty()) return List.of();
        context.symbols.beginScope();
        final var bindings = new ArrayList<Token>();
        for (final var argument : test.pattern.arguments()) {
            if (argument.binding() == null) continue;
            final var name = argument.binding();
            final var type = argument.resolvedType();
            Resolver.declare(context, new Stmt.Var(name, type, null,
                            BindingMutability.IMMUTABLE, false),
                    name, type, BindingMutability.IMMUTABLE);
            Resolver.define(context, name);
            bindings.add(name);
        }
        return bindings;
    }

    static void endSuccessfulSumCaseTestScope(final ResolutionContext context,
                                               final List<Token> bindings) {
        if (bindings.isEmpty()) return;
        bindings.forEach(context.frame.flowState::remove);
        context.symbols.endScope();
    }

    private static void mergePatternBindings(final ResolutionContext context,
                                             final Map<String, MatchBinding> destination,
                                             final Map<String, MatchBinding> source,
                                             final Token location) {
        for (final var entry : source.entrySet()) {
            if (destination.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        location, "A pattern cannot bind the same name more than once."));
            }
        }
    }

    private static TypeDescriptor resolveTypeTest(final ResolutionContext context, final Expr.TypeTest test) {
        final var sourceType = resolve(context, test.value);
        final var targetType = resolveSumCaseTestType(context, test.targetType, sourceType, test.operator);
        validateRuntimeTestTarget(context, targetType, test.operator);
        if (!context.typeCompatibility.canTypeTest(sourceType, targetType)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                    test.operator,
                    "Type test is impossible between " + sourceType + " and " + targetType + "."));
        }
        test.setResolvedTargetType(targetType);
        test.setType(TypeDescriptor.ofBoolean());
        return TypeDescriptor.ofBoolean();
    }

    private static TypeDescriptor resolveSumCaseTestType(final ResolutionContext context,
                                                          final TypeDescriptor targetType,
                                                          final TypeDescriptor sourceType,
                                                          final Token where) {
        final var separator = targetType.name().lastIndexOf('.');
        if (separator < 0) return targetType;
        final var sumName = targetType.name().substring(0, separator);
        final var caseName = targetType.name().substring(separator + 1);
        final var sum = context.sums.get(sumName);
        if (sum == null) return targetType;
        final var sumCase = sum.cases().stream()
                .filter(candidate -> candidate.name().lexeme().equals(caseName))
                .findFirst().orElse(null);
        if (sumCase == null) return targetType;
        final var source = sourceType instanceof ReferenceDescriptor reference
                ? reference.baseType() : sourceType;
        final var sumType = source instanceof NullableDescriptor nullable ? nullable.baseType() : source;
        if (!sumType.name().equals(sum.name().lexeme())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                    where, "Sum case '" + targetType.name() + "' is not a case of " + sourceType + "."));
        }
        final var sumArguments = sumType instanceof GenericDescriptor generic
                ? generic.typeParameters() : List.<TypeDescriptor>of();
        final var generatedName = sum.generatedCaseName(sumCase);
        return sumArguments.isEmpty() ? TypeDescriptor.ofName(generatedName)
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(generatedName), sumArguments);
    }

    private static TypeDescriptor resolveSumCaseTest(final ResolutionContext context,
                                                       final Expr.SumCaseTest test) {
        if (!context.frame.resolvingSumCaseCondition) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    test.operator, "'matches' is currently supported only as a direct if or while condition."));
        }
        final var sourceType = resolve(context, test.value);
        final var source = sourceType instanceof ReferenceDescriptor reference
                ? reference.baseType() : sourceType;
        final var sumType = source instanceof NullableDescriptor nullable ? nullable.baseType() : source;
        final var sumDeclaration = context.sums.get(sumType.name());
        if (sumDeclaration == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    test.operator, "'matches' requires a nominal sum value."));
        }
        final var pattern = test.pattern;
        if (pattern.extractor() == null || pattern.binding() != null || pattern.wildcard()
                || !pattern.alternatives().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    test.operator, "'matches' requires one sum case pattern."));
        }
        final var permittedCases = new LinkedHashSet<String>();
        for (final var sumCase : sumDeclaration.cases()) {
            permittedCases.add(sumDeclaration.generatedCaseName(sumCase));
        }
        final var caseArguments = sumType instanceof GenericDescriptor generic
                ? generic.typeParameters() : List.<TypeDescriptor>of();
        final var resolvedPattern = resolveMatchPattern(context, pattern, sumType, true,
                permittedCases, caseArguments, MatchRootKind.SEALED);
        final var matchedType = resolvedPattern.matchedType();
        final var resolvedCaseType = matchedType == null || caseArguments.isEmpty()
                ? matchedType
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(matchedType.name()), caseArguments);
        if (resolvedCaseType != null) {
            pattern.resolve(null, resolvedCaseType, false);
            pattern.markSumCase();
        }
        if (resolvedCaseType == null || !context.typeCompatibility.canTypeTest(sourceType, resolvedCaseType)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST,
                    test.operator, "Sum case test is impossible between " + sourceType + " and "
                            + resolvedCaseType + "."));
        }
        final var payloadTypes = pattern.arguments().stream().map(Expr.MatchPattern::resolvedType).toList();
        test.setResolvedCaseType(resolvedCaseType);
        test.setPayloadTypes(payloadTypes);
        test.setType(TypeDescriptor.ofBoolean());
        return TypeDescriptor.ofBoolean();
    }

    private static TypeDescriptor resolveCoalesceAssignment(final ResolutionContext context, final Expr.CoalesceAssignment assignment) {
        final var binding = context.symbols.getSymbol(assignment.name);
        if (!binding.mutability().isReassignable()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED, assignment.name,
                    "Cannot reassign immutable binding '" + assignment.name.lexeme() + "'."));
        }
        if (binding.lvt() == SymbolTable.GLOBAL) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED, assignment.name,
                    "'??=' is currently supported only for mutable local bindings."));
        }
        if (!(binding.type() instanceof NullableDescriptor)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NULLABLE_VALUE_REQUIRES_HANDLING,
                    assignment.name,
                    "The target of '??=' must have a nullable declared type."));
        }

        final var incoming = context.frame.flowState.copy();
        final var currentType = effectiveType(context, binding);
        final var canBeNonNull = !(currentType instanceof NullDescriptor);
        final var canBeNull = currentType instanceof NullableDescriptor
                || currentType instanceof NullDescriptor;
        final var nonNullFlow = canBeNonNull ? incoming.copy() : FlowState.unreachable();
        final var nullFlow = canBeNull ? incoming.copy() : FlowState.unreachable();
        if (canBeNonNull && canBeNull) {
            final var existingFact = incoming.get(binding.name());
            nonNullFlow.put(binding.name(), existingFact == null
                    ? nonNullFact(context, binding.type())
                    : new FlowFact(false, existingFact.nonNullAlternatives()));
        }
        if (canBeNull) {
            nullFlow.put(binding.name(), new FlowFact(true, Set.of()));
        }

        context.frame.flowState = nullFlow.copy();
        final var valueType = MemberInteropResolver.resolveArgument(context, assignment.value, binding.type());
        ensureAssignable(context, binding.type(), valueType, assignment.name);
        if (context.frame.flowState.isReachable()) {
            if (valueType instanceof NullDescriptor) {
                context.frame.flowState.put(binding.name(), new FlowFact(true, Set.of()));
            } else if (!(valueType instanceof NullableDescriptor)) {
                context.frame.flowState.put(binding.name(), nonNullFact(context, binding.type()));
            } else {
                context.frame.flowState.remove(binding.name());
            }
            if (context.frame.resolvingPattern
                    && context.frame.patternOutputNames.contains(binding.name().lexeme())) {
                context.frame.flowState.recordWrite(binding.name());
            }
            for (final var writeScope : context.frame.flowWriteScopes) writeScope.add(binding.name());
        }
        context.frame.flowState = FlowState.join(nonNullFlow, context.frame.flowState);
        context.symbols.define(assignment.name);
        assignment.setType(binding.type());
        return binding.type();
    }

    private static void validateRuntimeTestTarget(final ResolutionContext context, final TypeDescriptor targetType, final Token where) {
        TypeResolver.validateType(context, targetType, where);
        if (targetType instanceof NullableDescriptor || targetType instanceof ReferenceDescriptor
                || targetType instanceof ArrayDescriptor || targetType instanceof GenericDescriptor
                || targetType instanceof FunctionDescriptor || targetType instanceof TypeParameterDescriptor
                || targetType instanceof NullDescriptor || targetType instanceof NeverDescriptor
                || targetType instanceof InferDescriptor) {
            if (targetType instanceof GenericDescriptor generic
                    && context.classes.containsKey(generic.name())
                    && isGeneratedSumCase(context, generic.name())) return;
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OPERATOR_CAST_OR_TYPE_TEST, where,
                    "This type cannot be used as a runtime type-test target."));
        }
    }

    private static boolean isGeneratedSumCase(final ResolutionContext context, final String className) {
        return context.sums.values().stream().anyMatch(sum ->
                sum.cases().stream().anyMatch(sumCase -> sum.generatedCaseName(sumCase).equals(className)));
    }

    static StatementResolver.ConditionFlows resolveCondition(final ResolutionContext context, final Expr expression,
                                                              final FlowState incoming,
                                                              final Token where) {
        final var enclosingFlow = context.frame.flowState;
        try {
            final var condition = ungroupCondition(expression);
            if (containsSumCaseTest(expression) && !(condition instanceof Expr.SumCaseTest)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        where, "'matches' cannot be composed with other condition operators yet."));
            }
            if (expression instanceof Expr.Literal literal && literal.value instanceof Boolean value) {
                final var whenTrue = incoming.copy();
                final var whenFalse = incoming.copy();
                if (value) whenFalse.markUnreachable();
                else whenTrue.markUnreachable();
                return new StatementResolver.ConditionFlows(whenTrue, whenFalse);
            }
            if (expression instanceof Expr.Grouping grouping) {
                final var result = resolveCondition(context, grouping.expression, incoming, where);
                grouping.setType(TypeDescriptor.ofBoolean());
                return result;
            }
            if (expression instanceof Expr.Unary unary
                    && unary.operator.type() == TokenType.NOT) {
                final var result = resolveCondition(context, unary.right, incoming, unary.operator);
                unary.setType(TypeDescriptor.ofBoolean());
                return new StatementResolver.ConditionFlows(result.whenFalse(), result.whenTrue());
            }
            if (expression instanceof Expr.Logical logical) {
                final var left = resolveCondition(context, logical.left, incoming, logical.operator);
                final StatementResolver.ConditionFlows result;
                if (logical.operator.type() == TokenType.AND) {
                    final var right = resolveCondition(context, logical.right, left.whenTrue(), logical.operator);
                    result = new StatementResolver.ConditionFlows(right.whenTrue(),
                            FlowState.join(left.whenFalse(), right.whenFalse()));
                } else {
                    final var right = resolveCondition(context, logical.right, left.whenFalse(), logical.operator);
                    result = new StatementResolver.ConditionFlows(FlowState.join(left.whenTrue(), right.whenTrue()),
                            right.whenFalse());
                }
                logical.setType(TypeDescriptor.ofBoolean());
                return result;
            }

            context.frame.flowState = incoming.copy();
            final var previousSumCaseCondition = context.frame.resolvingSumCaseCondition;
            context.frame.resolvingSumCaseCondition = condition instanceof Expr.SumCaseTest;
            try {
                ensureBoolean(context, resolve(context, expression), where);
            } finally {
                context.frame.resolvingSumCaseCondition = previousSumCaseCondition;
            }
            final var evaluated = context.frame.flowState.copy();
            final var whenTrue = evaluated.copy();
            final var whenFalse = evaluated.copy();

            if (expression instanceof Expr.TypeTest test) {
                final var variable = directVariable(context, test.value);
                if (variable != null && isRefinable(context, variable.name)) {
                    whenTrue.put(context.symbols.getSymbol(variable.name).name(),
                            refineTypeFact(context, context.symbols.getSymbol(variable.name).type(),
                                    evaluated.get(context.symbols.getSymbol(variable.name).name()),
                                    test.resolvedTargetType()));
                }
            } else if (expression instanceof Expr.SumCaseTest test) {
                final var variable = directVariable(context, test.value);
                if (variable != null && isRefinable(context, variable.name)) {
                    final var binding = context.symbols.getSymbol(variable.name);
                    whenTrue.put(binding.name(), refineTypeFact(context, binding.type(),
                            evaluated.get(binding.name()), test.resolvedCaseType()));
                }
            } else if (expression instanceof Expr.Binary binary
                    && isNullComparison(context, binary)) {
                final var checked = directVariable(context, isNullLiteral(context, binary.left) ? binary.right : binary.left);
                if (checked != null && isRefinable(context, checked.name)) {
                    final var binding = context.symbols.getSymbol(checked.name);
                    final var nullFact = new FlowFact(true, Set.of());
                    final var nonNullFact = nonNullFact(context, binding.type());
                    final var equalsNull = binary.operator.type() == TokenType.EQUAL_EQUAL
                            || binary.operator.type() == TokenType.EQUAL_EQUAL_EQUAL;
                    whenTrue.put(binding.name(), equalsNull ? nullFact : nonNullFact);
                    whenFalse.put(binding.name(), equalsNull ? nonNullFact : nullFact);
                }
            }
            return new StatementResolver.ConditionFlows(whenTrue, whenFalse);
        } finally {
            context.frame.flowState = enclosingFlow;
        }
    }

    private static Expr ungroupCondition(final Expr expression) {
        return expression instanceof Expr.Grouping grouping
                ? ungroupCondition(grouping.expression) : expression;
    }

    private static boolean containsSumCaseTest(final Expr expression) {
        return switch (expression) {
            case Expr.SumCaseTest _ -> true;
            case Expr.Grouping grouping -> containsSumCaseTest(grouping.expression);
            case Expr.Logical logical -> containsSumCaseTest(logical.left)
                    || containsSumCaseTest(logical.right);
            case Expr.Unary unary -> containsSumCaseTest(unary.right);
            default -> false;
        };
    }

    private static FlowFact refineTypeFact(final ResolutionContext context, final TypeDescriptor declaredType,
                                   final FlowFact existing,
                                   final TypeDescriptor targetType) {
        final Set<TypeDescriptor> currentAlternatives;
        if (existing != null) {
            currentAlternatives = existing.nonNullAlternatives();
        } else {
            currentAlternatives = initialNonNullAlternatives(context, declaredType);
        }
        if (currentAlternatives == null) return new FlowFact(false, Set.of(targetType));

        final var narrowed = new LinkedHashSet<TypeDescriptor>();
        for (final var alternative : currentAlternatives) {
            if (context.typeCompatibility.canAssign(targetType, alternative)) {
                narrowed.add(alternative);
            } else if (context.typeCompatibility.canAssign(alternative, targetType)) {
                narrowed.add(targetType);
            }
        }
        if (narrowed.isEmpty()) narrowed.add(targetType);
        return new FlowFact(false, narrowed);
    }

    static FlowFact nonNullFact(final ResolutionContext context, final TypeDescriptor declaredType) {
        final var alternatives = initialNonNullAlternatives(context, declaredType);
        return new FlowFact(false, alternatives);
    }

    private static Set<TypeDescriptor> initialNonNullAlternatives(final ResolutionContext context, final TypeDescriptor declaredType) {
        var baseType = declaredType instanceof NullableDescriptor nullable
                ? nullable.baseType()
                : declaredType;
        if (baseType instanceof ReferenceDescriptor reference) baseType = reference.baseType();
        if (baseType instanceof AnyDescriptor) return null;
        return Set.of(baseType);
    }

    private static TypeDescriptor effectiveType(final ResolutionContext context, final Bind binding) {
        final var fact = context.frame.flowState.get(binding.name());
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

        final var commonType = context.typeCompatibility.commonTypeForAlternatives(fact.nonNullAlternatives());
        final var declaredBase = binding.type() instanceof NullableDescriptor nullable
            ? nullable.baseType()
            : binding.type();
        final var effectiveBase = declaredBase instanceof ReferenceDescriptor
            ? new ReferenceDescriptor(commonType)
            : commonType;
        return fact.mayBeNull() ? effectiveBase.toNullable() : effectiveBase;
    }

    static boolean isRefinable(final ResolutionContext context, final Token name) {
        return context.symbols.containsSymbol(name)
                && context.symbols.getSymbol(name).lvt() != SymbolTable.GLOBAL;
    }

    static Expr.Variable directVariable(final ResolutionContext context, final Expr expression) {
        return switch (expression) {
            case Expr.Variable variable -> variable;
            case Expr.Grouping grouping -> directVariable(context, grouping.expression);
            default -> null;
        };
    }

    private static boolean isNullLiteral(final ResolutionContext context, final Expr expression) {
        return switch (expression) {
            case Expr.Literal literal -> literal.value == null;
            case Expr.Grouping grouping -> isNullLiteral(context, grouping.expression);
            default -> false;
        };
    }

    private static boolean isNullComparison(final ResolutionContext context, final Expr.Binary binary) {
        return (binary.operator.type() == TokenType.EQUAL_EQUAL
                || binary.operator.type() == TokenType.BANG_EQUAL
                || binary.operator.type() == TokenType.EQUAL_EQUAL_EQUAL)
                && (isNullLiteral(context, binary.left) || isNullLiteral(context, binary.right));
    }

    private static boolean isIdentityComparable(final ResolutionContext context, final TypeDescriptor type) {
        final var identityType = identityViewType(context, type);
        if (identityType instanceof NominalDescriptor nominal
                && context.newtypes.containsKey(nominal.name())) return false;
        return identityType instanceof AnyDescriptor
                || identityType instanceof NominalDescriptor
                || identityType instanceof GenericDescriptor
                || identityType instanceof ArrayDescriptor
                || identityType instanceof FunctionDescriptor
                || identityType instanceof StringDescriptor;
    }

    private static TypeDescriptor identityViewType(final ResolutionContext context, final TypeDescriptor type) {
        if (type instanceof NullableDescriptor nullable) return identityViewType(context, nullable.baseType());
        if (type instanceof ReferenceDescriptor reference) return identityViewType(context, reference.baseType());
        return type;
    }

    private static boolean isPrimitive(final ResolutionContext context, final TypeDescriptor type) {
        final var baseType = type instanceof NullableDescriptor nullable
                ? nullable.baseType()
                : type;
        return baseType instanceof IntDescriptor || baseType instanceof FloatDescriptor
                || baseType instanceof BooleanDescriptor;
    }

}
