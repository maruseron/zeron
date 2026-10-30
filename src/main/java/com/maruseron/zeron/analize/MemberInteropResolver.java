package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.*;

import static com.maruseron.zeron.analize.Resolver.*;

final class MemberInteropResolver {
    record PropertyInfo(String name, Token nameToken, TypeDescriptor type,
                        boolean isPublic, boolean isMutating) {}
    private record SafeNavigationFlows(FlowState nonNull, FlowState nullPath) {}
    private record JavaCandidate(JavaClassPath.JavaMethod method, List<TypeDescriptor> parameterTypes,
                                 TypeDescriptor returnType, int cost, boolean varArgs) {}

    private static FunctionDescriptor instantiateDefaultMethodType(
            final ResolutionContext context,
            final Stmt.ClassDecl owner,
            final TypeDescriptor receiverType,
            final Resolver.DefaultMethodSelection selection) {
        return (FunctionDescriptor) TypeSubstitution.substitute(selection.instantiatedType(),
                TypeResolver.substitutionsFor(context, owner.typeParameters(), receiverType));
    }

    static TypeDescriptor resolveImplicitFieldRead(final ResolutionContext context, final Expr.Variable variable) {
        if (context.frame.currentMethodOwner == null) return null;
        final var availableFields = context.frame.initializerVisibleFields == null
                ? context.frame.currentMethodOwner.fields()
                : context.frame.initializerVisibleFields;
        var field = availableFields.stream()
                .filter(candidate -> candidate.name().lexeme().equals(variable.name.lexeme()))
                .findFirst()
                .orElse(null);
        if (field == null && context.frame.currentMethodOwner.dataClass()) {
            field = context.frame.currentMethodOwner.dataComponentFields().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(variable.name.lexeme()))
                    .findFirst()
                    .orElse(null);
            if (field != null && context.frame.initializerVisibleFields != null
                    && context.frame.initializerVisibleFields.stream()
                    .noneMatch(candidate -> candidate.name().lexeme().equals(variable.name.lexeme()))) {
                field = null;
            }
        }
        if (field == null) return null;

        final var receiver = new Expr.Variable(
                new Token(TokenType.THIS, "this", null, variable.name.span()),
                TypeDescriptor.ofInfer());
        final var receiverType = ExpressionFlowResolver.resolveExpression(context, receiver);
        ensureFieldAccessible(context, variable.name, context.frame.currentMethodOwner.name().lexeme());
        final var receiverBaseType = receiverType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : receiverType;
        final var resolvedType = TypeSubstitution.substitute(field.type(),
                TypeResolver.substitutionsFor(context, context.frame.currentMethodOwner.typeParameters(), receiverBaseType));
        variable.setImplicitFieldRead(receiver, context.frame.currentMethodOwner.name().lexeme(), field.type());
        variable.setType(resolvedType);
        context.recordReference(variable.name, field.name(), resolvedType);
        return resolvedType;
    }

    static TypeDescriptor resolveProperty(final ResolutionContext context, final Expr.Property property) {
        return resolveProperty(context, property, null, false);
    }

    private static TypeDescriptor resolveProperty(final ResolutionContext context, final Expr.Property property,
                                                  final TypeDescriptor alreadyResolvedReceiverType,
                                                  final boolean allowMissing) {
        final var receiverPath = expressionPath(property.receiver);
        final var floatConstant = "Float".equals(receiverPath)
                ? switch (property.name.lexeme()) {
                    case "NaN" -> "NaN";
                    case "POSITIVE_INFINITY" -> "POSITIVE_INFINITY";
                    case "NEGATIVE_INFINITY" -> "NEGATIVE_INFINITY";
                    default -> null;
                }
                : null;
        if (floatConstant != null) {
            if (property.safeNavigation()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        property.name, "Safe navigation cannot be used for a static property."));
            }
            property.setJavaFieldTarget(new JavaFieldTarget(
                    "java.lang.Double", floatConstant, "D", true, true));
            property.setType(TypeDescriptor.ofFloat());
            return TypeDescriptor.ofFloat();
        }
        if (receiverPath != null) {
            final var where = property.receiver instanceof Expr.Variable typeName
                    ? typeName.name : property.name;
            final var ownerName = resolveClassName(context, receiverPath, where);
            final var externalClass = ownerName == null ? null : context.externalClasses.get(ownerName);
            final var isZeronType = ownerName != null
                    && (context.classes.containsKey(ownerName) || context.contracts.containsKey(ownerName));
            final var javaClass = ownerName == null || isZeronType
                    ? null : context.javaClassPath.find(ownerName);
            if (javaClass != null) {
                if (property.safeNavigation()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                            property.name, "Safe navigation cannot be used for a static property."));
                }
                final var declaredProperty = externalClass == null ? null : externalClass.staticProperties().stream()
                        .filter(candidate -> candidate.name().lexeme().equals(property.name.lexeme()))
                        .findFirst().orElse(null);
                final var javaField = javaClass == null ? null : javaClass.fields().stream()
                        .filter(candidate -> candidate.name().equals(property.name.lexeme()))
                        .findFirst().orElse(null);
                final var fieldType = javaField == null ? null
                        : javaFieldType(context, javaClass, javaField, null, property.name);
                final var facadeFieldAllowed = externalClass != null
                        ? declaredProperty != null && declaredProperty.isPublic()
                            && fieldType != null && fieldType.equals(declaredProperty.type())
                        : !ownerName.equals("java.lang.System") || property.name.lexeme().equals("out");
                if (javaField == null || !javaField.isStatic() || fieldType == null
                        || !facadeFieldAllowed) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                            property.name, "Unknown or unsupported external static property."));
                }
                property.setJavaFieldTarget(new JavaFieldTarget(ownerName, javaField.name(),
                        javaField.descriptor().descriptorString(), true, javaField.isFinal()));
                property.setType(fieldType);
                return fieldType;
            }
        }
        final var namespaceValueName = namespaceMemberName(context, property.receiver, property.name.lexeme());
        if (namespaceValueName != null && context.topLevelValues.containsKey(namespaceValueName)) {
            if (property.safeNavigation()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        property.name, "Safe navigation cannot be used for a namespace value."));
            }
            final var value = context.topLevelValues.get(namespaceValueName);
            if (!isNamespaceMemberAccessible(context, namespaceValueName, value.isPublic())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                        property.name, "Namespace value '" + namespaceValueName + "' is private."));
            }
            final var symbol = context.topLevelValueSymbols.get(namespaceValueName);
            property.setNamespaceValue(symbol, value);
            final var binding = context.symbols.getSymbol(symbol);
            final var type = binding == null ? value.type() : binding.type();
            property.setType(type);
            return type;
        }
        final var receiverType = alreadyResolvedReceiverType == null
                ? ExpressionFlowResolver.resolveExpression(context, property.receiver)
                : alreadyResolvedReceiverType;
        final var safeFlows = property.safeNavigation()
                ? beginSafeNavigation(context, property.receiver, property.name, receiverType)
                : null;
        final var memberReceiverType = receiverType instanceof NullableDescriptor nullable
                ? nullable.baseType()
                : receiverType;
        final var baseType = memberReceiverType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : memberReceiverType;
        final var propertyIntrinsic = context.intrinsics.property(property.name.lexeme());
        if (propertyIntrinsic != null && propertyIntrinsic.id() == IntrinsicId.ARRAY_LENGTH
            && baseType instanceof ArrayDescriptor arrayType) {
            final var operation = IntrinsicResolver.resolveIntrinsic(context, propertyIntrinsic.id(),
                List.of(arrayType.elementType()), List.of(memberReceiverType), property.name);
            property.setIntrinsicOperation(operation);
            final var resultType = property.safeNavigation()
                    ? operation.resultType().toNullable()
                    : operation.resultType();
            property.setType(resultType);
            finishSafeNavigation(context, safeFlows);
            return resultType;
        }

        if ("length".equals(property.name.lexeme()) && baseType instanceof ByteArrayDescriptor) {
            final var operation = IntrinsicResolver.resolveIntrinsic(context, IntrinsicId.BYTE_ARRAY_LENGTH,
                    List.of(), List.of(memberReceiverType), property.name);
            property.setIntrinsicOperation(operation);
            final var resultType = property.safeNavigation()
                    ? operation.resultType().toNullable()
                    : operation.resultType();
            property.setType(resultType);
            finishSafeNavigation(context, safeFlows);
            return resultType;
        }
        if (baseType instanceof TypeParameterDescriptor parameter) {
            final var properties = parameter.bounds().stream()
                    .map(ContractComposition::fromType)
                    .filter(Objects::nonNull)
                    .flatMap(use -> ContractComposition.closure(context, use).stream())
                    .flatMap(applied -> applied.declaration().properties().stream()
                            .filter(required -> required.name().lexeme().equals(property.name.lexeme()))
                            .map(required -> Map.entry(applied, required)))
                    .toList();
            if (!properties.isEmpty()) {
                final var selected = properties.getFirst();
                final var propertyType = TypeSubstitution.substitute(
                        selected.getValue().type(), selected.getKey().substitutions());
                if (properties.stream().skip(1).anyMatch(candidate -> !TypeClassEvidence.sameAppliedType(
                        propertyType, TypeSubstitution.substitute(
                                candidate.getValue().type(), candidate.getKey().substitutions())))) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                            property.name, "Property is ambiguous across the type parameter's contract bounds."));
                }
                property.setResolvedOwnerName(selected.getKey().declaration().name().lexeme());
                property.setResolvedAsProperty(true);
                context.recordReference(property.name, selected.getValue().name(), propertyType);
                final var resultType = property.safeNavigation()
                        ? propertyType.toNullable()
                        : propertyType;
                property.setType(resultType);
                finishSafeNavigation(context, safeFlows);
                return resultType;
            }
        }
        final var ownerName = className(context, baseType);
        final var owner = context.classes.get(ownerName);
        for (final var sum : context.sums.values()) {
            for (final var sumCase : sum.cases()) {
                if (!sum.generatedCaseName(sumCase).equals(ownerName)) continue;
                for (int index = 0; index < sumCase.payloads().size(); index++) {
                    final var payload = sumCase.payloads().get(index);
                    if (!payload.name().lexeme().equals(property.name.lexeme())) continue;
                    final var appliedSumType = baseType instanceof GenericDescriptor generic
                            ? TypeDescriptor.genericOf(TypeDescriptor.ofName(sum.name().lexeme()),
                                    generic.typeParameters())
                            : TypeDescriptor.ofName(sum.name().lexeme());
                    final var payloadType = TypeSubstitution.substitute(payload.type(),
                            TypeResolver.substitutionsFor(context, sum.typeParameters(), appliedSumType));
                    property.setResolvedOwnerName(ownerName);
                    property.setSumPayloadIndex(index);
                    final var resultType = property.safeNavigation()
                            ? payloadType.toNullable() : payloadType;
                    property.setType(resultType);
                    finishSafeNavigation(context, safeFlows);
                    return resultType;
                }
            }
        }
        final var propertyDeclaration = findProperty(context, ownerName, property.name);
        if (propertyDeclaration != null) {
            if (!propertyDeclaration.isPublic()
                    && !Objects.equals(context.frame.currentClassName, ownerName)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                        property.name, "Property is private."));
            }
            final var propertyType = exposeProjectedResult(
                    resolvedPropertyType(context, ownerName, propertyDeclaration, memberReceiverType), property.name);
            property.setResolvedOwnerName(ownerName);
            property.setResolvedAsProperty(true);
            context.recordReference(property.name, propertyDeclaration.nameToken(), propertyType);
            final var resultType = property.safeNavigation()
                    ? propertyType.toNullable()
                    : propertyType;
            property.setType(resultType);
            finishSafeNavigation(context, safeFlows);
            return resultType;
        }
        if (owner == null) {
            final var javaClass = context.contracts.containsKey(ownerName)
                    ? null : context.javaClassPath.find(ownerName);
            final var javaField = javaClass == null ? null : javaClass.fields().stream()
                    .filter(candidate -> candidate.name().equals(property.name.lexeme()))
                    .findFirst().orElse(null);
            if (javaField != null) {
                if (context.externalClasses.containsKey(ownerName)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                            property.name, "Java fields must be declared in the external-class facade."));
                }
                if (javaField.isStatic()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                            property.name, "Static Java fields must be accessed through their class."));
                }
                final var resolvedFieldType = javaFieldType(
                        context, javaClass, javaField, memberReceiverType, property.name);
                if (resolvedFieldType == null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE,
                            property.name, "Java field descriptor is not supported."));
                }
                final var fieldType = exposeProjectedResult(resolvedFieldType, property.name);
                property.setJavaFieldTarget(new JavaFieldTarget(ownerName, javaField.name(),
                        javaField.descriptor().descriptorString(), false, javaField.isFinal()));
                final var resultType = property.safeNavigation() ? fieldType.toNullable() : fieldType;
                property.setType(resultType);
                finishSafeNavigation(context, safeFlows);
                return resultType;
            }
        }
        if (owner != null) {
            final var field = owner.fields().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(property.name.lexeme()))
                    .findFirst()
                    .orElse(null);
            if (field != null) {
                ensureFieldAccessible(context, property.name, ownerName);
                final var fieldType = TypeSubstitution.substitute(field.type(),
                        TypeResolver.substitutionsFor(context, owner.typeParameters(), memberReceiverType));
                context.recordReference(property.name, field.name(), fieldType);
                final var resultType = property.safeNavigation() ? fieldType.toNullable() : fieldType;
                property.setType(resultType);
                finishSafeNavigation(context, safeFlows);
                return resultType;
            }
        }
        final var extensionReceiverType = property.safeNavigation() ? memberReceiverType : receiverType;
        final var extensionCall = new Expr.MemberCall(property.receiver, property.name, property.name,
                List.of(), List.of(), TypeDescriptor.ofInfer());
        final var extensionResult = resolveImportedExtension(context, extensionCall, extensionReceiverType, true);
        if (extensionResult != null) {
            property.setExtensionCall(extensionCall);
            final var resultType = property.safeNavigation() ? extensionResult.toNullable() : extensionResult;
            property.setType(resultType);
            RaisedEffectFlow.add(context,
                    new LinkedHashSet<>(extensionCall.resolvedDescriptor().raisedEffects()), property.name);
            finishSafeNavigation(context, safeFlows);
            return resultType;
        }
        if (allowMissing) return null;
        if (owner == null) Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                property.name, "Unknown property."));
        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                property.name, "Unknown field."));
        throw new IllegalStateException("Unreachable");
    }

    static TypeDescriptor resolveMemberCall(final ResolutionContext context, final Expr.MemberCall call) {
        return resolveMemberCall(context, call, null);
    }

    static TypeDescriptor resolveOperatorContractCall(final ResolutionContext context,
                                                      final Expr.MemberCall call,
                                                      final TypeDescriptor receiverType,
                                                      final TypeDescriptor requiredContractType) {
        if (receiverType instanceof TypeParameterDescriptor parameter) {
            if (!TypeClassEvidence.hasEvidence(context, requiredContractType, receiverType, call.name)) {
                return null;
            }
            return resolveBoundedMemberCall(context, call, parameter, requiredContractType);
        }
        if (context.typeCompatibility.isContractProjection(requiredContractType, receiverType)) {
            return resolveMemberCall(context, call, receiverType, null);
        }
        return resolveWitnessMethodCall(context, call, receiverType, requiredContractType);
    }

    static TypeDescriptor resolveWitnessMethodForContract(final ResolutionContext context,
                                                          final Expr.MemberCall call,
                                                          final TypeDescriptor receiverType,
                                                          final TypeDescriptor contractType) {
        return resolveWitnessMethodCall(context, call, receiverType, contractType);
    }

    private static TypeDescriptor resolveMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                                     final TypeDescriptor expectedType) {
        if (call.safeNavigation()) {
            final var receiverType = ExpressionFlowResolver.resolveExpression(context, call.receiver);
            final var safeFlows = beginSafeNavigation(context, call.receiver, call.name, receiverType);
            final var memberReceiverType = receiverType instanceof NullableDescriptor nullable
                    ? nullable.baseType()
                    : receiverType;
            context.frame.flowState = safeFlows.nonNull().copy();
            final var resultType = resolveMemberCall(context, call, memberReceiverType, expectedType);
            finishSafeNavigation(context, safeFlows);
            final var nullableResult = resultType.toNullable();
            call.setType(nullableResult);
            return nullableResult;
        }
        return resolveMemberCall(context, call, null, expectedType);
    }

    private static TypeDescriptor resolveHigherKindedCall(final ResolutionContext context,
                                                          final Expr.MemberCall call,
                                                          final TypeParameterDescriptor parameter) {
        final var matches = new LinkedHashMap<String, Map.Entry<Integer, ContractComposition.MethodSlot>>();
        for (int boundIndex = 0; boundIndex < parameter.bounds().size(); boundIndex++) {
            final var use = ContractComposition.fromType(parameter.bounds().get(boundIndex));
            if (use == null) continue;
            for (final var slot : ContractComposition.methods(context, use)) {
                if (slot.applied().declaration().higherKinded()
                        && slot.method().name().lexeme().equals(call.name.lexeme())) {
                    matches.putIfAbsent(methodProviderKey(slot), Map.entry(boundIndex, slot));
                }
            }
        }
        if (matches.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "The higher-kinded parameter has no bound providing this operation."));
        }
        if (matches.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "HKT operation is ambiguous across the constructor parameter's bounds."));
        }
        final var selected = matches.values().iterator().next();
        final var selectedSignature = (FunctionDescriptor) TypeSubstitution.substitute(
                selected.getValue().method().typeDescriptor(),
                selected.getValue().applied().substitutions());
        final var method = selected.getValue().method();
        if (method.isMutating() && !Stmt.hasReceiverParameter(method.parameters())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "A mutating HKT operation must declare an explicit receiver parameter."));
        }
        final var callableSignature = method.isMutating()
                ? withReferenceReceiver(selectedSignature)
                : selectedSignature;
        final var methodIndex = selected.getValue().index();
        call.setWitnessEvidence(Expr.methodEvidenceToken(parameter, selected.getKey(), methodIndex),
                callableSignature);
        call.setResolvedOwnerName(selected.getValue().applied().declaration().name().lexeme());
        final var fixedArity = Stmt.fixedArity(method.parameters(), method.variadic());
        if (callableSignature.isGeneric()) {
            return resolveGenericMemberCall(context, call, callableSignature,
                    method.minimumArity(), method.variadic(), fixedArity);
        }
        ensureMemberCallArity(call, callableSignature, method.minimumArity(), method.variadic());
        if (method.variadic()) {
            call.setVariadic(((ArrayDescriptor) callableSignature.parameters().getLast()).elementType(),
                    fixedArity);
        }
        for (int index = 0; index < call.arguments.size(); index++) {
            final var expected = memberParameterType(callableSignature.parameters(), index,
                    fixedArity, method.variadic());
            ensureAssignable(context, expected,
                    resolveArgument(context, call.arguments.get(index), expected), call.name);
        }
        call.setResolvedDescriptor(callableSignature);
        call.setType(callableSignature.returnType());
        return callableSignature.returnType();
    }

    private static TypeDescriptor resolveHigherKindedReceiverCall(
            final ResolutionContext context, final Expr.MemberCall call,
            final AppliedConstructorDescriptor receiverType, final boolean mutableReceiver) {
        final var constructor = receiverType.constructor();
        final var hasBehavioralProvider = constructor.bounds().stream()
                .map(ContractComposition::fromType)
                .filter(Objects::nonNull)
                .filter(use -> {
                    final var declaration = context.contracts.get(use.name().lexeme());
                    return declaration != null && !declaration.higherKinded();
                })
                .flatMap(use -> ContractComposition.methods(context, use).stream())
                .anyMatch(slot -> slot.method().name().lexeme().equals(call.name.lexeme()));
        if (hasBehavioralProvider) {
            return resolveBoundedMemberCall(context, call, constructor, null, true);
        }
        final var matches = new LinkedHashMap<String, Map.Entry<Integer, ContractComposition.MethodSlot>>();
        for (int boundIndex = 0; boundIndex < constructor.bounds().size(); boundIndex++) {
            final var use = ContractComposition.fromType(constructor.bounds().get(boundIndex));
            if (use == null) continue;
            for (final var slot : ContractComposition.methods(context, use)) {
                if (slot.applied().declaration().higherKinded()
                        && Stmt.hasReceiverParameter(slot.method().parameters())
                        && slot.method().name().lexeme().equals(call.name.lexeme())) {
                    matches.putIfAbsent(methodProviderKey(slot), Map.entry(boundIndex, slot));
                }
            }
        }
        if (matches.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "The higher-kinded parameter has no receiver operation with this name."));
        }
        if (matches.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "HKT receiver operation is ambiguous across the constructor parameter's bounds."));
        }
        final var selected = matches.values().iterator().next();
        final var signature = (FunctionDescriptor) TypeSubstitution.substitute(
                selected.getValue().method().typeDescriptor(),
                selected.getValue().applied().substitutions());

        final var method = selected.getValue().method();
        if (method.isMutating() && !mutableReceiver) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED, call.name,
                    "Mutating HKT operations require a mutable reference receiver."));
        }
        final var receiverParameter = signature.parameters().getFirst();
        final var explicitParameters = signature.parameters().subList(1, signature.arity());
        final var explicitSignature = TypeDescriptor.functionWithEffectsOf(signature.name(), signature.returnType(),
                explicitParameters, signature.typeParameters(), signature.raisedEffects());
        final var fixedArity = Stmt.fixedArity(method.parameters(), method.variadic()) - 1;
        final var minimumArity = method.minimumArity() - 1;
        final var evidenceToken = Expr.methodEvidenceToken(
                constructor, selected.getKey(), selected.getValue().index());
        call.setResolvedOwnerName(selected.getValue().applied().declaration().name().lexeme());
        if (explicitSignature.isGeneric()) {
            final var result = resolveGenericMemberCall(context, call, explicitSignature,
                    minimumArity, method.variadic(), fixedArity,
                    receiverParameter, receiverType);
            call.setWitnessMethodEvidence(evidenceToken, signature);
            return result;
        }
        if (!call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    call.name, "This operation does not declare type parameters."));
        }
        ensureAssignable(context, receiverParameter, receiverType, call.name);
        ensureMemberCallArity(call, explicitSignature, minimumArity, method.variadic());
        if (method.variadic()) {
            call.setVariadic(((ArrayDescriptor) explicitSignature.parameters().getLast()).elementType(),
                    fixedArity);
        }
        for (int index = 0; index < call.arguments.size(); index++) {
            final var expected = memberParameterType(explicitSignature.parameters(), index,
                    fixedArity, method.variadic());
            ensureAssignable(context, expected,
                    resolveArgument(context, call.arguments.get(index), expected), call.name);
        }
        call.setWitnessMethodEvidence(evidenceToken, signature);
        call.setResolvedDescriptor(explicitSignature);
        call.setType(signature.returnType());
        return signature.returnType();
    }

    private static String methodProviderKey(final ContractComposition.MethodSlot slot) {
        return slot.applied().declaration().name().lexeme() + slot.applied().use().typeArguments()
                + "#" + slot.method().name().lexeme();
    }

    private static FunctionDescriptor withReferenceReceiver(final FunctionDescriptor function) {
        final var parameters = new ArrayList<>(function.parameters());
        parameters.set(0, new ReferenceDescriptor(parameters.getFirst()));
        return TypeDescriptor.functionWithEffectsOf(function.name(), function.returnType(),
                parameters, function.typeParameters(), function.raisedEffects());
    }

    private static TypeDescriptor resolveMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                             final TypeDescriptor alreadyResolvedReceiverType,
                                             final TypeDescriptor expectedType) {
        Zeron.debug("resolving member call       " + call.name.lexeme() + " for " + call.receiver);
        final var receiverDescriptor = call.receiver.getType() instanceof TypeParameterDescriptor parameter
                ? context.activeTypeSubstitutions.getOrDefault(parameter, parameter)
                : call.receiver.getType();
        if (receiverDescriptor instanceof TypeParameterDescriptor parameter
                && parameter.isHigherKinded()) {
            return resolveHigherKindedCall(context, call, parameter);
        }
        if (receiverDescriptor instanceof AppliedConstructorDescriptor applied
                && applied.constructor().isHigherKinded()) {
            return resolveHigherKindedReceiverCall(context, call, applied, false);
        }
        final var receiverPath = expressionPath(call.receiver);
        final var classOwnerName = receiverPath == null ? null : resolveClassName(context, receiverPath,
                call.receiver instanceof Expr.Variable typeName ? typeName.name : call.name);
        final var classDeclaration = classOwnerName == null ? null : context.classes.get(classOwnerName);
        final var sumDeclaration = classOwnerName == null ? null : context.sums.get(classOwnerName);
        final var namespaceFunctionName = !call.safeNavigation()
                ? namespaceMemberName(context, call.receiver, call.name.lexeme())
                : null;
        final var namespaceFunction = namespaceFunctionName == null
                ? null : context.functions.get(namespaceFunctionName);
        if (sumDeclaration != null) {
            final var sumCase = sumDeclaration.cases().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(call.name.lexeme()))
                    .findFirst().orElse(null);
            final var namespaceCall = namespaceFunction != null
                    && !(namespaceFunction instanceof Stmt.ExtensionMethod);
            if (sumCase != null && namespaceCall) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        call.name,
                        "Ambiguous name: both a sum case constructor and namespace function are declared."));
            }
            if (sumCase == null && namespaceCall) {
                if (!isNamespaceMemberAccessible(context, namespaceFunctionName, namespaceFunction.isPublic())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                            call.name, "Namespace function '" + namespaceFunctionName + "' is private."));
                }
                return context.callResolver.resolveNamespaceCall(call, namespaceFunctionName);
            }
            if (sumCase == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        call.name, "Unknown case '" + call.name.lexeme() + "' for sum '"
                                + sumDeclaration.name().lexeme() + "'."));
            }
            final var caseName = sumDeclaration.generatedCaseName(sumCase);
            final var caseClass = context.classes.get(caseName);
            final var caseExpectedType = expectedCaseType(sumDeclaration, expectedType, caseName);
            final var result = resolveClassFactoryCall(context, call, caseClass, caseName,
                    new ClassFactory(null, true), caseExpectedType, null);
            call.setSumCaseFactory(true);
            final var caseType = result instanceof ReferenceDescriptor reference
                    ? reference.baseType() : result;
            final var arguments = caseType instanceof GenericDescriptor generic
                    ? generic.typeParameters() : List.<TypeDescriptor>of();
            final TypeDescriptor sumType = arguments.isEmpty()
                    ? TypeDescriptor.ofName(sumDeclaration.name().lexeme())
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName(sumDeclaration.name().lexeme()), arguments);
            final var sumResult = new ReferenceDescriptor(sumType);
            final var constructorType = context.callSiteFunctionTypes.get(call.name);
            if (constructorType != null) {
                context.recordCallSiteFunctionType(call.name, TypeDescriptor.functionWithEffectsOf(
                        call.name.lexeme(), sumResult, constructorType.parameters(),
                        constructorType.typeParameters(), constructorType.raisedEffects()));
            }
            call.setType(sumResult);
            return sumResult;
        }
        final var classFactory = classDeclaration == null ? null
                : findClassFactory(classDeclaration, call.name.lexeme());
        if (classFactory != null) {
            if (namespaceFunction != null && !(namespaceFunction instanceof Stmt.ExtensionMethod)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        call.name, "Ambiguous name: both a class constructor and namespace function are declared."));
            }
            return resolveClassFactoryCall(context, call, classDeclaration, classOwnerName,
                    classFactory, expectedType, null);
        }
        if (!call.safeNavigation()) {
            if (namespaceFunction != null && !(namespaceFunction instanceof Stmt.ExtensionMethod)) {
                if (!isNamespaceMemberAccessible(context, namespaceFunctionName, namespaceFunction.isPublic())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                            call.name, "Namespace function '" + namespaceFunctionName + "' is private."));
                }
                return context.callResolver.resolveNamespaceCall(call, namespaceFunctionName);
            }
        }
        if (call.safeNavigation() && classOwnerName != null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "Safe navigation cannot be used for constructors or static calls."));
        }
        if (classOwnerName != null) {
            final var declaration = context.classes.get(classOwnerName);
            if (declaration == null) {
                final var javaClass = context.javaClassPath.find(classOwnerName);
                if (javaClass != null) return resolveJavaTypeCall(context, call, javaClass, expectedType);
            }
            if (declaration != null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        call.name, "Unknown named constructor."));
            }
        }

        final var receiverType = alreadyResolvedReceiverType == null
                ? ExpressionFlowResolver.resolveExpression(context, call.receiver)
                : alreadyResolvedReceiverType;
        final var receiverBaseType = receiverType instanceof ReferenceDescriptor reference
                ? reference.baseType() : receiverType;
        if (receiverBaseType instanceof AppliedConstructorDescriptor applied
                && applied.constructor().isHigherKinded()) {
            return resolveHigherKindedReceiverCall(
                    context, call, applied, receiverType instanceof ReferenceDescriptor);
        }
        if (receiverType instanceof TypeParameterDescriptor parameter) {
            return resolveBoundedMemberCall(context, call, parameter);
        }
        final var ownerName = className(context, receiverType);
        final var owner = context.classes.get(ownerName);
        final var classMethods = owner == null ? List.<Stmt.Method>of() : owner.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .filter(method -> method.isPublic() || Objects.equals(context.frame.currentClassName, ownerName))
                .toList();
        final var contract = context.contracts.get(ownerName);
        final var appliedContract = contract == null ? null
                : contractCompositionForType(context, ownerName, receiverType).stream()
                        .filter(applied -> applied.declaration().methods().stream().anyMatch(method ->
                                method.name().lexeme().equals(call.name.lexeme())))
                        .findFirst().orElse(null);
        final var contractMethods = appliedContract == null ? List.<Stmt.ContractMethod>of()
                : appliedContract.declaration().methods().stream()
                        .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                        .toList();
        final var classMethod = classMethods.isEmpty() ? null : classMethods.getFirst();
        final var contractMethod = classMethod != null || contractMethods.isEmpty()
                ? null : contractMethods.getFirst();
        final var contractMethodOwner = appliedContract == null ? null : appliedContract.declaration();
        call.setResolvedSourceMethod(classMethod);
        call.setResolvedContractMethod(contractMethod);
        if (classMethod != null) {
            context.recordReference(call.name, classMethod.name(), classMethod.typeDescriptor());
        } else if (contractMethod != null) {
            context.recordReference(call.name, contractMethod.name(), contractMethod.typeDescriptor());
        }
        if (owner != null && classMethod != null
                && call.arguments.size() < classMethod.minimumArity()) {
            final var defaults = DeclarationResolver.defaultMethodsOnClass(context, owner, call.name.lexeme());
            if (defaults.size() > 1) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        call.name,
                        "Multiple default contract methods named '" + call.name.lexeme()
                                + "' require an explicit class implementation."));
            }

            if (!defaults.isEmpty()) {
                final var defaultMethod = defaults.getFirst();
                final var descriptor = instantiateDefaultMethodType(context, owner, receiverType, defaultMethod);
                if (defaultMethod.method().isMutating()
                        && !(receiverType instanceof ReferenceDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            call.name,
                            "Mutating method requires a mutable reference."));
                }
                call.setResolvedOwnerName(defaultMethod.ownerName());
                call.setResolvedContractMethod(defaultMethod.method());
                context.recordReference(call.name, defaultMethod.method().name(),
                        defaultMethod.method().typeDescriptor());
                call.setReceiverRequiresCast(true);
                final var variadic = defaultMethod.method().variadic();
                final var fixedArity = Stmt.fixedArity(defaultMethod.method().parameters(), variadic);
                if (descriptor.isGeneric()) {
                    return resolveGenericMemberCall(
                            context, call, descriptor, defaultMethod.method().minimumArity(), variadic, fixedArity);
                }
                if (!call.explicitTypeArguments.isEmpty()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                            call.name, "This method does not declare type parameters."));
                }
                call.setResolvedDescriptor(descriptor);
                ensureMemberCallArity(call, descriptor, defaultMethod.method().minimumArity(), variadic);
                if (variadic) {
                    call.setVariadic(((ArrayDescriptor) descriptor.parameters().getLast()).elementType(), fixedArity);
                }
                for (int i = 0; i < call.arguments.size(); i++) {
                    final var expected = memberParameterType(
                            descriptor.parameters(), i, fixedArity, variadic);
                    ensureAssignable(context, expected,
                            resolveArgument(context, call.arguments.get(i), expected),
                            call.name);
                }
                final var returnType = exposeProjectedResult(descriptor.returnType(), call.name);
                call.setType(returnType);
                return returnType;
            }
        }
        if (classMethod == null && contractMethod == null) {
            if (owner != null) {
                final var defaults = DeclarationResolver.defaultMethodsOnClass(context, owner, call.name.lexeme());
                if (defaults.size() > 1) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                            call.name,
                            "Multiple default contract methods named '" + call.name.lexeme()
                                    + "' require an explicit class implementation."));
                }
                if (!defaults.isEmpty()) {
                    final var defaultMethod = defaults.getFirst();
                    final var descriptor = instantiateDefaultMethodType(context, owner, receiverType, defaultMethod);
                    if (defaultMethod.method().isMutating()
                            && !(receiverType instanceof ReferenceDescriptor)) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                                call.name,
                                "Mutating method requires a mutable reference."));
                    }
                    call.setResolvedOwnerName(defaultMethod.ownerName());
                    call.setResolvedContractMethod(defaultMethod.method());
                    context.recordReference(call.name, defaultMethod.method().name(),
                            defaultMethod.method().typeDescriptor());
                    call.setReceiverRequiresCast(true);
                    final var variadic = defaultMethod.method().variadic();
                    final var fixedArity = Stmt.fixedArity(defaultMethod.method().parameters(), variadic);
                    if (descriptor.isGeneric()) {
                        return resolveGenericMemberCall(
                                context, call, descriptor, defaultMethod.method().minimumArity(),
                                variadic, fixedArity);
                    }
                    if (!call.explicitTypeArguments.isEmpty()) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                                call.name,
                                "This method does not declare type parameters."));
                    }
                    call.setResolvedDescriptor(descriptor);
                    ensureMemberCallArity(call, descriptor, defaultMethod.method().minimumArity(), variadic);
                    if (variadic) {
                        call.setVariadic(((ArrayDescriptor) descriptor.parameters().getLast()).elementType(),
                                fixedArity);
                    }
                    for (int i = 0; i < call.arguments.size(); i++) {
                        final var expected = memberParameterType(
                                descriptor.parameters(), i, fixedArity, variadic);
                        ensureAssignable(context, expected,
                                resolveArgument(context, call.arguments.get(i), expected),
                                call.name);
                    }
                    final var returnType = exposeProjectedResult(descriptor.returnType(), call.name);
                    call.setType(returnType);
                    return returnType;
                }
            }
            final var extensionResult = resolveImportedExtension(context, call, receiverType, false);
            if (extensionResult != null) return extensionResult;
            final var witnessResult = resolveWitnessMethodCall(context, call, receiverType);
            if (witnessResult != null) return witnessResult;
            final var javaClass = context.javaClassPath.find(ownerName);
            if (javaClass != null) return resolveJavaInstanceCall(context, call, receiverType, javaClass);
            final var functionPropertyResult = resolveFunctionPropertyCall(context, call, receiverType);
            if (functionPropertyResult != null) return functionPropertyResult;
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    call.name, "Unknown method: '" + call.name.lexeme() + "'"));
        }

        if (classMethod == null && contractMethod == null) {
            final var extensionResult = resolveImportedExtension(context, call, receiverType, false);
            if (extensionResult != null) return extensionResult;
            final var witnessResult = resolveWitnessMethodCall(context, call, receiverType);
            if (witnessResult != null) return witnessResult;
            final var javaClass = context.javaClassPath.find(ownerName);
            if (javaClass != null) return resolveJavaInstanceCall(context, call, receiverType, javaClass);
            final var functionPropertyResult = resolveFunctionPropertyCall(context, call, receiverType);
            if (functionPropertyResult != null) return functionPropertyResult;
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    call.name, "Unknown method: '" + call.name.lexeme() + "'"));
        }

        final var descriptor = classMethod != null
                ? classMethod.typeDescriptor()
                : contractMethod.typeDescriptor();
        final var typeParameters = classMethod != null
            ? owner.typeParameters()
            : contractMethodOwner.typeParameters();
        final var instantiatedDescriptor = (FunctionDescriptor) TypeSubstitution.substitute(descriptor,
                classMethod != null
                        ? TypeResolver.substitutionsFor(context, typeParameters, receiverType)
                        : appliedContract.substitutions());
        final var exposedReturn = exposeProjectedResult(instantiatedDescriptor.returnType(), call.name);
        final var isMutating = classMethod != null
                ? classMethod.isMutating()
                : contractMethod.isMutating();
        final var minimumArity = classMethod != null
                ? classMethod.minimumArity()
                : contractMethod.minimumArity();
        final var variadic = classMethod != null
                ? classMethod.variadic()
                : contractMethod.variadic();
        if (context.frame.resolvingPattern && isMutating) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                    call.name, "Pattern bodies cannot call mutating methods."));
        }
        final var fixedArity = classMethod != null
                ? Stmt.fixedArity(classMethod.parameters(), variadic)
                : Stmt.fixedArity(contractMethod.parameters(), variadic);
        if (classMethod != null && !classMethod.isPublic()
                && !Objects.equals(context.frame.currentClassName, ownerName)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                    call.name, "Method is private."));
        }
        if (isMutating && !(receiverType instanceof ReferenceDescriptor)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED, call.name,
                    "Mutating method requires a mutable reference."));
        }
        if (instantiatedDescriptor.isGeneric()) {
            return resolveGenericMemberCall(
                    context, call, instantiatedDescriptor, minimumArity, variadic, fixedArity);
        }
        if (!call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    call.name,
                    "This method does not declare type parameters."));
        }
        call.setResolvedDescriptor(instantiatedDescriptor);
        ensureMemberCallArity(call, instantiatedDescriptor, minimumArity, variadic);
        if (variadic) {
            call.setVariadic(((ArrayDescriptor) instantiatedDescriptor.parameters().getLast()).elementType(),
                    fixedArity);
        }
        for (int i = 0; i < call.arguments.size(); i++) {
            final var expected = memberParameterType(
                    instantiatedDescriptor.parameters(), i, fixedArity, variadic);
            ensureAssignable(context, expected,
                    resolveArgument(context, call.arguments.get(i), expected),
                    call.name);
        }
        call.setType(exposedReturn);
        return exposedReturn;
    }

    private static TypeDescriptor resolveFunctionPropertyCall(final ResolutionContext context,
                                                              final Expr.MemberCall call,
                                                              final TypeDescriptor receiverType) {
        final var property = new Expr.Property(call.receiver, call.name, TypeDescriptor.ofInfer());
        final var propertyType = resolveProperty(context, property, receiverType, true);
        if (propertyType == null) return null;
        final var callableType = propertyType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : propertyType;
        if (!(callableType instanceof FunctionDescriptor functionType)) return null;
        if (!call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    call.name, "A function-valued property does not declare type parameters."));
        }
        if (call.arguments.size() != functionType.arity()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name, "Expected " + functionType.arity() + " arguments, found "
                            + call.arguments.size() + "."));
        }
        for (int index = 0; index < call.arguments.size(); index++) {
            final var expected = functionType.parameters().get(index);
            ensureAssignable(context, expected,
                    resolveArgument(context, call.arguments.get(index), expected), call.name);
        }
        call.setFunctionProperty(property);
        call.setResolvedDescriptor(functionType);
        call.setType(functionType.returnType());
        return functionType.returnType();
    }

    private static TypeDescriptor resolveWitnessMethodCall(final ResolutionContext context,
                                                           final Expr.MemberCall call,
                                                           final TypeDescriptor receiverType) {
        return resolveWitnessMethodCall(context, call, receiverType, null);
    }

    private static TypeDescriptor resolveWitnessMethodCall(final ResolutionContext context,
                                                           final Expr.MemberCall call,
                                                           final TypeDescriptor receiverType,
                                                           final TypeDescriptor requiredContractType) {
        final var selection = requiredContractType == null
                ? TypeClassEvidence.selectMemberMethod(context, receiverType, call.name.lexeme(), call.name)
                : TypeClassEvidence.selectMethod(context, receiverType,
                        requiredContractType, call.name.lexeme(), call.name);
        if (selection == null) return null;
        final var method = selection.method();
        final var signature = method.methodSignature();
        final var descriptor = (FunctionDescriptor) TypeSubstitution.substitute(
                signature.typeDescriptor(), selection.substitutions());
        context.recordReference(call.name, method.implementation().name(), descriptor);
        call.setWitnessMethod(selection.helperQualifiedName(),
                method.implementation().name().lexeme(),
                selection.helperType(), !method.isStatic());
        final var witnessEvidence = TypeClassEvidence.selectArguments(
                context, method.implementation().typeDescriptor().typeParameters(),
                selection.substitutions(), call.name);
        final var receiverOperation = Stmt.hasReceiverParameter(signature.parameters());
        final var resolvedSignature = receiverOperation
                ? TypeDescriptor.functionWithEffectsOf(descriptor.name(), descriptor.returnType(),
                        descriptor.parameters().subList(1, descriptor.arity()),
                        descriptor.typeParameters(), descriptor.raisedEffects())
                : descriptor;
        final var fixedArity = Stmt.fixedArity(signature.parameters(), signature.variadic())
                - (receiverOperation ? 1 : 0);
        final var minimumArity = signature.minimumArity() - (receiverOperation ? 1 : 0);
        final var receiverParameter = receiverOperation ? descriptor.parameters().getFirst() : null;
        final var receiverBaseType = receiverType instanceof ReferenceDescriptor reference
                ? reference.baseType() : receiverType;
        if (resolvedSignature.isGeneric()) {
            final var result = resolveGenericMemberCall(context, call, resolvedSignature, minimumArity,
                    signature.variadic(), fixedArity, receiverParameter,
                    receiverOperation ? receiverBaseType : null);
            if (!witnessEvidence.isEmpty()) {
                final var evidence = new ArrayList<>(witnessEvidence);
                evidence.addAll(call.evidenceArguments());
                call.setEvidenceArguments(evidence);
            }
            return result;
        }
        if (!call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    call.name, "This method does not declare type parameters."));
        }
        if (receiverOperation) {
            ensureAssignable(context, receiverParameter, receiverBaseType, call.name);
        }
        call.setResolvedDescriptor(resolvedSignature);
        ensureMemberCallArity(call, resolvedSignature, minimumArity,
                signature.variadic());
        if (signature.variadic()) {
            call.setVariadic(((ArrayDescriptor) resolvedSignature.parameters().getLast()).elementType(), fixedArity);
        }
        for (int index = 0; index < call.arguments.size(); index++) {
            final var expected = memberParameterType(resolvedSignature.parameters(), index,
                    fixedArity, signature.variadic());
            ensureAssignable(context, expected,
                    resolveArgument(context, call.arguments.get(index), expected), call.name);
        }
        call.setType(resolvedSignature.returnType());
        call.setEvidenceArguments(witnessEvidence);
        return resolvedSignature.returnType();
    }

    private record ExtensionReceiverMatch(Stmt.ExtensionMethod method, TypeDescriptor projection) {}

    private static TypeDescriptor resolveImportedExtension(final ResolutionContext context,
                                                            final Expr.MemberCall call,
                                                            final TypeDescriptor receiverType,
                                                            final boolean propertyAccess) {
        final var localDeclarations = localExtensionDeclarations(context, call.name.lexeme());
        final var extensionName = context.currentImports.extensions().get(call.name.lexeme());
        final var explicitDeclarations = extensionName == null
                ? List.<Stmt.ExtensionMethod>of()
                : extensionDeclarations(context, List.of(extensionName));
        final var namespaceDeclarations = namespaceExtensionDeclarations(context, call.name.lexeme());
        final var importedDeclarations = new ArrayList<>(explicitDeclarations);
        importedDeclarations.addAll(namespaceDeclarations);
        final var starDeclarations = starExtensionDeclarations(context, call.name.lexeme());
        final var matchingLocalDeclarations = localDeclarations.stream()
                .filter(method -> method.property() == propertyAccess).toList();
        final var matchingImportedDeclarations = importedDeclarations.stream()
                .distinct()
                .filter(method -> method.property() == propertyAccess).toList();
        final var matchingStarDeclarations = starDeclarations.stream()
                .filter(method -> method.property() == propertyAccess).toList();
        if (matchingLocalDeclarations.isEmpty() && matchingImportedDeclarations.isEmpty()
                && matchingStarDeclarations.isEmpty()) return null;
        final var mutableReceiver = receiverType instanceof ReferenceDescriptor;
        final var receiverBase = receiverType instanceof ReferenceDescriptor reference
                ? reference.baseType() : receiverType;
        final var receiverProjections = extensionReceiverProjections(context, receiverBase);
        var receiverMatches = findReceiverApplicableExtensions(
                context, matchingLocalDeclarations, receiverProjections, receiverType);
        if (receiverMatches.isEmpty()) {
            receiverMatches = findReceiverApplicableExtensions(
                    context, matchingImportedDeclarations, receiverProjections, receiverType);
        }
        if (receiverMatches.isEmpty()) {
            receiverMatches = findReceiverApplicableExtensions(
                    context, matchingStarDeclarations, receiverProjections, receiverType);
        }
        if (receiverMatches.isEmpty()) return null;
        if (!mutableReceiver) {
            receiverMatches = receiverMatches.stream()
                    .filter(match -> !match.method().isMutating())
                    .toList();
            if (receiverMatches.isEmpty()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED, call.name,
                        "Mutating extension method requires a mutable receiver."));
            }
        }
        if (receiverMatches.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT, call.name,
                    "Ambiguous extension method '" + call.name.lexeme()
                            + "' for receiver type '" + TypeFormatter.format(receiverBase) + "'."));
        }
        final var selected = receiverMatches.getFirst();
        final var method = selected.method();
        final var fixedArity = method.fixedCallArity();
        if ((!call.explicitTypeArguments.isEmpty()
                && call.explicitTypeArguments.size() != method.methodTypeParameters().size())
                || call.arguments.size() < method.minimumCallArity()
                || !method.variadic() && call.arguments.size() > fixedArity) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name, "Arguments do not match extension method '" + call.name.lexeme() + "'."));
        }
        if (context.frame.resolvingPattern && method.isMutating()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                    call.name, "Pattern bodies cannot call mutating extension methods."));
        }
        final var actualArguments = call.arguments.stream().map(argument ->
                argument instanceof Expr.Lambda || LambdaResolver.isFunctionReferenceCandidate(context, argument)
                        ? null : ExpressionFlowResolver.resolveExpression(context, argument)).toList();
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        TypeUnifier.unify(method.receiverType(), selected.projection(), substitutions, call.name);
        for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
            final var explicitType = call.explicitTypeArguments.get(i);
            TypeResolver.validateType(context, explicitType, call.name);
            substitutions.put(method.methodTypeParameters().get(i), explicitType);
        }
        final var resolvedArguments = new TypeDescriptor[call.arguments.size()];
        final var signature = method.typeDescriptor();
        for (int i = 0; i < call.arguments.size(); i++) {
            if (actualArguments.get(i) == null) continue;
            resolvedArguments[i] = actualArguments.get(i);
            final var formal = extensionArgumentType(signature.parameters(), i, fixedArity, method.variadic());
            if (TypeSubstitution.containsTypeParameter(formal)) {
                TypeUnifier.unify(formal, resolvedArguments[i], substitutions, call.name);
            }
        }
        for (int i = 0; i < call.arguments.size(); i++) {
            if (actualArguments.get(i) != null) continue;
            final var formal = extensionArgumentType(signature.parameters(), i, fixedArity, method.variadic());
            final var expected = TypeSubstitution.substitute(formal, substitutions);
            if (!(expected instanceof FunctionDescriptor)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.name, "Cannot infer the type of this function argument."));
            }
            resolvedArguments[i] = resolveArgument(context, call.arguments.get(i), expected);
            if (TypeSubstitution.containsTypeParameter(formal)) {
                TypeUnifier.unify(formal, resolvedArguments[i], substitutions, call.name);
            }
        }
        for (final var parameter : signature.typeParameters()) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.GENERIC_INFERENCE_FAILURE,
                        call.name, "Cannot infer type parameter '" + parameter.name()
                                + "' for imported extension method '" + call.name.lexeme()
                                + "'; provide an explicit type argument."));
            }
            substitutions.put(parameter, TypeResolver.validateTypeArgument(
                    context, parameter, substitutions.get(parameter), call.name));
            for (final var bound : parameter.bounds()) {
                final var requiredBound = TypeSubstitution.substitute(bound, substitutions);
                final var actualType = substitutions.get(parameter);
                if (!TypeClassEvidence.hasEvidence(context, requiredBound, actualType, call.name)) {
                    ensureAssignable(context, requiredBound, actualType, call.name);
                }
            }
        }
        call.setEvidenceArguments(TypeClassEvidence.selectArguments(
                context, signature.typeParameters(), substitutions, call.name));
        final var instantiatedDescriptor = (FunctionDescriptor) TypeSubstitution.substitute(signature, substitutions);
        call.setResolvedExtensionMethod(method);
        context.recordReference(call.name, method.name(), method.typeDescriptor());
        call.setResolvedDescriptor(instantiatedDescriptor);
        if (method.variadic()) {
            call.setVariadic(((ArrayDescriptor) instantiatedDescriptor.parameters().getLast())
                    .elementType(), fixedArity);
        }
        for (int i = 0; i < call.arguments.size(); i++) {
            final var expected = extensionArgumentType(instantiatedDescriptor.parameters(), i,
                    fixedArity, method.variadic());
            ensureAssignable(context, expected, resolvedArguments[i], call.name);
        }
        call.setType(instantiatedDescriptor.returnType());
        return instantiatedDescriptor.returnType();
    }

    private static List<Stmt.ExtensionMethod> localExtensionDeclarations(
            final ResolutionContext context, final String methodName) {
        if (context.currentSourcePath == null) return List.of();
        return context.extensionMethods.values().stream()
                .flatMap(Collection::stream)
                .filter(method -> method.name().lexeme().equals(methodName))
                .filter(method -> Objects.equals(context.sourcePath(method.name()), context.currentSourcePath))
                .toList();
    }

    private static List<Stmt.ExtensionMethod> extensionDeclarations(
            final ResolutionContext context, final List<String> qualifiedNames) {
        return qualifiedNames.stream()
                .flatMap(name -> context.extensionMethods.getOrDefault(name, List.of()).stream())
                .filter(method -> method.isPublic()
                        || Objects.equals(context.namespaceMemberPackages.get(
                                context.functionNamesByDeclaration.get(method.name())), context.packageName))
                .toList();
    }

    private static List<Stmt.ExtensionMethod> starExtensionDeclarations(
            final ResolutionContext context, final String methodName) {
        return context.extensionMethods.values().stream()
                .flatMap(Collection::stream)
                .filter(method -> method.name().lexeme().equals(methodName))
                .filter(method -> context.currentImports.onDemandPackages().contains(
                        context.namespaceMemberPackages.get(context.functionNamesByDeclaration.get(method.name()))))
                .filter(Stmt.ExtensionMethod::isPublic)
                .distinct()
                .toList();
    }

    private static List<Stmt.ExtensionMethod> namespaceExtensionDeclarations(
            final ResolutionContext context, final String methodName) {
        return context.extensionMethods.values().stream()
                .flatMap(Collection::stream)
                .filter(method -> method.isPublic()
                        || Objects.equals(context.namespaceMemberPackages.get(
                                context.functionNamesByDeclaration.get(method.name())), context.packageName))
                .filter(method -> method.name().lexeme().equals(methodName))
                .filter(method -> {
                    final var qualifiedName = context.functionNamesByDeclaration.get(method.name());
                    final var separator = qualifiedName == null ? -1 : qualifiedName.lastIndexOf('.');
                    final var namespaceName = separator < 0 ? null : qualifiedName.substring(0, separator);
                    return context.currentImports.onDemandNamespaces().contains(namespaceName);
                })
                .distinct()
                .toList();
    }

    private static List<ExtensionReceiverMatch> findReceiverApplicableExtensions(
            final ResolutionContext context, final List<Stmt.ExtensionMethod> declarations,
            final List<TypeDescriptor> receiverProjections, final TypeDescriptor receiverType) {
        final var matches = new ArrayList<ExtensionReceiverMatch>();
        for (final var method : declarations) {
            for (final var projection : receiverProjections) {
                final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
                try {
                    TypeUnifier.unify(method.receiverType(), projection, substitutions, method.name());
                } catch (final ResolutionError _) {
                    continue;
                }
                final var instantiated = (FunctionDescriptor) TypeSubstitution.substitute(
                        method.typeDescriptor(), substitutions);
                final var expectedReceiver = instantiated.parameters().getFirst();
                final var mutableReceiverType = receiverType instanceof ReferenceDescriptor
                        ? receiverType : new ReferenceDescriptor(receiverType);
                if (context.typeCompatibility.canAssign(expectedReceiver, receiverType)
                        || method.isMutating()
                        && context.typeCompatibility.canAssign(expectedReceiver, mutableReceiverType)) {
                    matches.add(new ExtensionReceiverMatch(method, projection));
                    break;
                }
            }
        }
        return matches;
    }

    private static List<TypeDescriptor> extensionReceiverProjections(final ResolutionContext context,
                                                                     final TypeDescriptor receiverBase) {
        final var projections = new ArrayList<TypeDescriptor>();
        projections.add(receiverBase);
        final var className = className(context, receiverBase);
        final var declaration = context.classes.get(className);
        if (declaration == null) return List.copyOf(projections);
        final var classSubstitutions = TypeResolver.substitutionsFor(
                context, declaration.typeParameters(), receiverBase);
        for (final var contractUse : declaration.contractUses()) {
            final var contract = context.contracts.get(contractUse.name().lexeme());
            if (contract == null) continue;
            final var arguments = contractUse.typeArguments().stream()
                    .map(argument -> TypeSubstitution.substitute(argument, classSubstitutions))
                    .toList();
            final TypeDescriptor projection = arguments.isEmpty()
                    ? TypeDescriptor.ofName(contractUse.name().lexeme())
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName(contractUse.name().lexeme()), arguments);
            if (!projections.contains(projection)) projections.add(projection);
        }
        return List.copyOf(projections);
    }

    private static TypeDescriptor extensionArgumentType(final List<TypeDescriptor> parameters,
                                                        final int argumentIndex,
                                                        final int fixedArity,
                                                        final boolean variadic) {
        final var parameterIndex = argumentIndex + 1;
        if (!variadic || argumentIndex < fixedArity) return parameters.get(parameterIndex);
        return ((ArrayDescriptor) parameters.getLast()).elementType();
    }

    private static void ensureMemberCallArity(final Expr.MemberCall call,
                                              final FunctionDescriptor descriptor,
                                              final int minimumArity,
                                              final boolean variadic) {
        if (call.arguments.size() < minimumArity || !variadic && call.arguments.size() > descriptor.arity()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name, variadic
                            ? "Expected at least " + minimumArity + " arguments, found " + call.arguments.size()
                                    + "."
                            : "Expected between " + minimumArity + " and " + descriptor.arity()
                                    + " arguments, found " + call.arguments.size() + "."));
        }
    }

    private static TypeDescriptor memberParameterType(final List<TypeDescriptor> parameters,
                                                      final int argumentIndex,
                                                      final int fixedArity,
                                                      final boolean variadic) {
        if (!variadic || argumentIndex < fixedArity) return parameters.get(argumentIndex);
        return ((ArrayDescriptor) parameters.getLast()).elementType();
    }

    private static SafeNavigationFlows beginSafeNavigation(final ResolutionContext context, final Expr receiver,
                                                    final Token where,
                                                    final TypeDescriptor receiverType) {
        if (receiverType instanceof NullDescriptor) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NULLABLE_VALUE_REQUIRES_HANDLING, where,
                    "Safe navigation requires a receiver with a known non-null type."));
        }
        final var afterReceiver = context.frame.flowState.copy();
        final var mayBeNull = receiverType instanceof NullableDescriptor;
        final var nonNullFlow = afterReceiver.copy();
        final var nullPath = mayBeNull ? afterReceiver.copy() : FlowState.unreachable();
        if (mayBeNull) {
            final var variable = ExpressionFlowResolver.directVariable(context, receiver);
            if (variable != null && ExpressionFlowResolver.isRefinable(context, variable.name)) {
                final var binding = context.symbols.getSymbol(variable.name);
                final var existingFact = afterReceiver.get(binding.name());
                nonNullFlow.put(binding.name(), existingFact == null
                        ? ExpressionFlowResolver.nonNullFact(context, binding.type())
                        : new FlowFact(false, existingFact.nonNullAlternatives()));
                nullPath.put(binding.name(), new FlowFact(true, Set.of()));
            }
        }
        context.frame.flowState = nonNullFlow.copy();
        return new SafeNavigationFlows(nonNullFlow, nullPath);
    }

    private static void finishSafeNavigation(final ResolutionContext context, final SafeNavigationFlows flows) {
        if (flows != null) {
            context.frame.flowState = FlowState.join(context.frame.flowState, flows.nullPath());
        }
    }

    private record ClassFactory(Stmt.NamedConstructor named, boolean canonical) {}

    private static ClassFactory findClassFactory(final Stmt.ClassDecl declaration, final String name) {
        if (name.equals("new")) return new ClassFactory(null, true);
        final var named = declaration.namedConstructors().stream()
                .filter(candidate -> candidate.name().lexeme().equals(name))
                .findFirst()
                .orElse(null);
        return named == null ? null : new ClassFactory(named, false);
    }

    private static boolean classFactoryArityMatches(final ClassFactory factory,
                                                     final Stmt.ClassDecl declaration,
                                                     final Expr.MemberCall call) {
        if (factory.canonical()) {
            return call.arguments.size() == declaration.canonicalConstructorTypes().size();
        }
        final var named = factory.named();
        final var fixedArity = Stmt.fixedArity(named.parameters(), named.variadic());
        return named.variadic()
                ? call.arguments.size() >= fixedArity
                : call.arguments.size() == named.typeDescriptor().arity();
    }

    private static TypeDescriptor resolveClassFactoryCall(final ResolutionContext context,
                                                           final Expr.MemberCall call,
                                                           final Stmt.ClassDecl declaration,
                                                           final String className,
                                                           final ClassFactory factory,
                                                           final TypeDescriptor expectedType,
                                                           final List<TypeDescriptor> priorArgumentTypes) {
        call.setResolvedClassName(className);
        final var typeParameters = declaration.typeParameters();
        final var explicitClassTypeArguments = call.explicitOwnerTypeArguments().isEmpty()
                ? call.explicitTypeArguments : call.explicitOwnerTypeArguments();
        if (!call.explicitOwnerTypeArguments().isEmpty() && !call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    call.name, "Zeron constructors do not declare method type parameters."));
        }
        if (!explicitClassTypeArguments.isEmpty()
                && typeParameters.size() != explicitClassTypeArguments.size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name, "Expected " + typeParameters.size() + " class type arguments, found "
                            + explicitClassTypeArguments.size() + "."));
        }
        for (final var explicitType : explicitClassTypeArguments) {
            TypeResolver.validateType(context, explicitType, call.name);
        }

        final var signature = factory.canonical()
                ? canonicalFactoryDescriptor(declaration)
                : factory.named().typeDescriptor();
        final boolean variadic = !factory.canonical() && factory.named().variadic();
        final int fixedArity = factory.canonical()
                ? signature.arity()
                : Stmt.fixedArity(factory.named().parameters(), variadic);
        final int minimumArity = factory.canonical() ? signature.arity() : fixedArity;
        if (!classFactoryArityMatches(factory, declaration, call)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name, variadic
                            ? "Expected at least " + minimumArity + " arguments, found "
                                    + call.arguments.size() + "."
                            : "Expected " + signature.arity() + " constructor arguments, found "
                                    + call.arguments.size() + "."));
        }
        if (factory.canonical() && !declaration.constructor().isPublic()
                && !call.internalNewtypeConstructor()
                && !Objects.equals(context.frame.currentClassName, declaration.name().lexeme())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                    call.name, "Constructor is private."));
        }
        if (!factory.canonical() && !factory.named().isPublic()
                && !Objects.equals(context.frame.currentClassName, declaration.name().lexeme())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                    call.name, "Named constructor is private."));
        }

        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < explicitClassTypeArguments.size(); i++) {
            substitutions.put(typeParameters.get(i), explicitClassTypeArguments.get(i));
        }
        final var resolvedArguments = new TypeDescriptor[call.arguments.size()];
        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (isDeferredConstructorArgument(context, argument)) continue;
            final var priorType = priorArgumentTypes == null ? null : priorArgumentTypes.get(i);
            resolvedArguments[i] = priorType == null
                    ? ExpressionFlowResolver.resolveExpression(context, argument) : priorType;
            final var formal = classFactoryParameterType(signature, i, fixedArity, variadic);
            if (containsUninferredClassTypeParameter(formal, typeParameters, substitutions)) {
                TypeUnifier.unify(formal, resolvedArguments[i], substitutions, call.name);
            }
        }

        if (explicitClassTypeArguments.isEmpty()) {
            inferClassArgumentsFromExpectedType(declaration, substitutions, expectedType, call.name);
        }

        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (!isDeferredConstructorArgument(context, argument)) continue;
            final var formal = classFactoryParameterType(signature, i, fixedArity, variadic);
            final var expected = TypeSubstitution.substitute(formal, substitutions);
            if (!(argument instanceof Expr.MemberCall)
                    && !acceptsDeferredArrayArgument(argument, expected)
                    && LambdaResolver.functionType(context, expected) == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.name, "A function value argument requires a function parameter type."));
            }
            resolvedArguments[i] = resolveArgument(context, argument, expected);
            if (containsUninferredClassTypeParameter(formal, typeParameters, substitutions)) {
                TypeUnifier.unify(formal, resolvedArguments[i], substitutions, call.name);
            }
        }

        for (final var parameter : typeParameters) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.GENERIC_INFERENCE_FAILURE,
                        call.name, "Cannot infer class type parameter '" + parameter.name()
                                + "'; provide an explicit type argument or an expected type."));
            }
            substitutions.put(parameter, TypeResolver.validateTypeArgument(
                    context, parameter, substitutions.get(parameter), call.name));
        }

        final var instantiatedParameters = signature.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                .toList();
        for (int i = 0; i < resolvedArguments.length; i++) {
            final var expected = classFactoryParameterType(instantiatedParameters, i, fixedArity, variadic);
            ensureAssignable(context, expected, resolvedArguments[i], call.name);
        }
        if (variadic) {
            call.setVariadic(((ArrayDescriptor) instantiatedParameters.getLast()).elementType(), fixedArity);
        }

        final TypeDescriptor constructedType = typeParameters.isEmpty()
                ? TypeDescriptor.of(declaration.name().lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(declaration.name().lexeme()),
                        typeParameters.stream().map(substitutions::get).toList());
        final var constructorToken = factory.canonical()
                ? declaration.constructor().name()
                : factory.named().name();
        final var instantiatedFactoryParameters = signature.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                .toList();
        final var callSiteFactory = TypeDescriptor.functionWithEffectsOf(
                constructorToken.lexeme(), new ReferenceDescriptor(constructedType),
                instantiatedFactoryParameters, List.of(),
                signature.raisedEffects().stream()
                        .map(effect -> TypeSubstitution.substitute(effect, substitutions))
                        .toList());
        context.recordReference(call.name, constructorToken, signature);
        context.recordCallSiteFunctionType(call.name, callSiteFactory);
        call.setResolvedDescriptor(callSiteFactory);
        RaisedEffectFlow.add(context, new LinkedHashSet<>(callSiteFactory.raisedEffects()), call.name);
        call.setEvidenceArguments(TypeClassEvidence.selectClassArguments(
                context, declaration, substitutions, call.name));
        final TypeDescriptor result;
        if (factory.canonical()) {
            result = new ReferenceDescriptor(constructedType);
        } else {
            result = callSiteFactory.returnType();
        }
        call.setType(result);
        return result;
    }

    private static FunctionDescriptor canonicalFactoryDescriptor(final Stmt.ClassDecl declaration) {
        final var constructorTypes = declaration.canonicalConstructorTypes();
        return TypeDescriptor.functionWithEffectsOf("new", new ReferenceDescriptor(classType(declaration)),
                constructorTypes, List.of(), declaration.constructorEffects());
    }

    private static TypeDescriptor expectedCaseType(final Stmt.SumDecl sum,
                                                   final TypeDescriptor expectedType,
                                                   final String caseName) {
        final var expected = expectedType instanceof ReferenceDescriptor reference
                ? reference.baseType() : expectedType;
        if (expected == null || !expected.name().equals(sum.name().lexeme())) return expectedType;
        final var arguments = expected instanceof GenericDescriptor generic
                ? generic.typeParameters() : List.<TypeDescriptor>of();
        final TypeDescriptor caseType = arguments.isEmpty()
                ? TypeDescriptor.ofName(caseName)
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(caseName), arguments);
        return new ReferenceDescriptor(caseType);
    }

    private static TypeDescriptor classType(final Stmt.ClassDecl declaration) {
        final var base = TypeDescriptor.ofName(declaration.name().lexeme());
        return declaration.typeParameters().isEmpty()
                ? base : TypeDescriptor.genericOf(base,
                        declaration.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
    }

    private static TypeDescriptor classFactoryParameterType(final FunctionDescriptor signature,
                                                              final int index,
                                                              final int fixedArity,
                                                              final boolean variadic) {
        return classFactoryParameterType(signature.parameters(), index, fixedArity, variadic);
    }

    private static TypeDescriptor classFactoryParameterType(final List<TypeDescriptor> parameters,
                                                              final int index,
                                                              final int fixedArity,
                                                              final boolean variadic) {
        return variadic && index >= fixedArity
                ? ((ArrayDescriptor) parameters.getLast()).elementType()
                : parameters.get(index);
    }

    private static boolean isDeferredConstructorArgument(final ResolutionContext context, final Expr argument) {
        return argument instanceof Expr.Lambda
                || LambdaResolver.isFunctionReferenceCandidate(context, argument)
                || argument instanceof Expr.MemberCall
                || argument instanceof Expr.ArrayLiteral literal && literal.elements.isEmpty();
    }

    private static boolean acceptsDeferredArrayArgument(final Expr argument, final TypeDescriptor expected) {
        return argument instanceof Expr.ArrayLiteral literal && literal.elements.isEmpty()
                && expected instanceof ArrayDescriptor;
    }

    private static boolean containsUninferredClassTypeParameter(
            final TypeDescriptor type,
            final List<TypeParameterDescriptor> classParameters,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        return switch (type) {
            case TypeParameterDescriptor parameter ->
                    classParameters.contains(parameter) && !substitutions.containsKey(parameter);
            case NullableDescriptor nullable ->
                    containsUninferredClassTypeParameter(nullable.baseType(), classParameters, substitutions);
            case ReferenceDescriptor reference ->
                    containsUninferredClassTypeParameter(reference.baseType(), classParameters, substitutions);
            case ArrayDescriptor array ->
                    containsUninferredClassTypeParameter(array.elementType(), classParameters, substitutions);
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(argument -> containsUninferredClassTypeParameter(
                            argument, classParameters, substitutions));
            case FunctionDescriptor function -> function.parameters().stream()
                    .anyMatch(parameter -> containsUninferredClassTypeParameter(
                            parameter, classParameters, substitutions))
                    || containsUninferredClassTypeParameter(
                            function.returnType(), classParameters, substitutions);
            default -> false;
        };
    }

    private static boolean containsAnyTypeParameter(final TypeDescriptor type,
                                                    final List<TypeParameterDescriptor> parameters) {
        return switch (type) {
            case TypeParameterDescriptor parameter -> parameters.contains(parameter);
            case AppliedConstructorDescriptor applied -> containsAnyTypeParameter(applied.constructor(), parameters)
                    || containsAnyTypeParameter(applied.argument(), parameters);
            case NullableDescriptor nullable -> containsAnyTypeParameter(nullable.baseType(), parameters);
            case ReferenceDescriptor reference -> containsAnyTypeParameter(reference.baseType(), parameters);
            case ArrayDescriptor array -> containsAnyTypeParameter(array.elementType(), parameters);
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(argument -> containsAnyTypeParameter(argument, parameters));
            case TypeProjectionDescriptor projection -> containsAnyTypeParameter(projection.bound(), parameters);
            case CapturedTypeDescriptor capture -> containsAnyTypeParameter(capture.lowerBound(), parameters)
                    || containsAnyTypeParameter(capture.upperBound(), parameters);
            case FunctionDescriptor function -> containsAnyTypeParameter(function.returnType(), parameters)
                    || function.parameters().stream()
                    .anyMatch(parameter -> containsAnyTypeParameter(parameter, parameters))
                    || function.raisedEffects().stream()
                    .anyMatch(effect -> containsAnyTypeParameter(effect, parameters));
            default -> false;
        };
    }

    private static void inferClassArgumentsFromExpectedType(final Stmt.ClassDecl declaration,
                                                             final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                                                             TypeDescriptor expectedType,
                                                             final Token where) {
        if (expectedType instanceof ReferenceDescriptor reference) expectedType = reference.baseType();
        if (expectedType instanceof NullableDescriptor nullable) expectedType = nullable.baseType();
        if (!(expectedType instanceof GenericDescriptor || expectedType instanceof NominalDescriptor)
                || !expectedType.name().equals(declaration.name().lexeme())) return;
        final var pattern = classType(declaration);
        if (pattern instanceof GenericDescriptor) TypeUnifier.unify(pattern, expectedType, substitutions, where);
    }

    private static TypeDescriptor resolveGenericMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                                    final FunctionDescriptor genericType, final int minimumArity,
                                                    final boolean variadic, final int fixedArity) {
        return resolveGenericMemberCall(context, call, genericType, minimumArity, variadic, fixedArity,
                null, null);
    }

    private static TypeDescriptor resolveGenericMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                                    final FunctionDescriptor genericType, final int minimumArity,
                                                    final boolean variadic, final int fixedArity,
                                                    final TypeDescriptor receiverParameter,
                                                    final TypeDescriptor receiverType) {
        final var typeParameters = genericType.typeParameters();
        if (!call.explicitTypeArguments.isEmpty()
                && call.explicitTypeArguments.size() != typeParameters.size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name,
                    "Expected " + typeParameters.size() + " type arguments, found "
                            + call.explicitTypeArguments.size() + "."));
        }
        if (call.arguments.size() < minimumArity || !variadic && call.arguments.size() > genericType.arity()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name,
                    variadic
                            ? "Expected at least " + minimumArity + " arguments, found " + call.arguments.size()
                                    + "."
                            : "Expected between " + minimumArity + " and " + genericType.arity()
                                    + " arguments, found " + call.arguments.size() + "."));
        }

        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
            final var explicitType = call.explicitTypeArguments.get(i);
            TypeResolver.validateType(context, explicitType, call.name);
            substitutions.put(typeParameters.get(i), explicitType);
        }
        if (receiverParameter != null) {
            TypeUnifier.unify(receiverParameter, receiverType, substitutions, call.name);
        }

        final var resolvedArguments = new TypeDescriptor[call.arguments.size()];
        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (argument instanceof Expr.Lambda || LambdaResolver.isFunctionReferenceCandidate(context, argument)) continue;
            resolvedArguments[i] = ExpressionFlowResolver.resolveExpression(context, argument);
            TypeUnifier.unify(memberParameterType(genericType.parameters(), i, fixedArity, variadic),
                    resolvedArguments[i], substitutions, call.name);
        }

        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (!(argument instanceof Expr.Lambda) && !LambdaResolver.isFunctionReferenceCandidate(context, argument)) continue;
            final var expected = TypeSubstitution.substitute(
                    memberParameterType(genericType.parameters(), i, fixedArity, variadic), substitutions);
            if (LambdaResolver.functionType(context, expected) == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.name,
                        "A function value argument requires a function parameter type."));
            }
            resolvedArguments[i] = resolveArgument(context, argument, expected);
            TypeUnifier.unify(memberParameterType(genericType.parameters(), i, fixedArity, variadic),
                    resolvedArguments[i], substitutions, call.name);
        }

        for (final var parameter : typeParameters) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.GENERIC_INFERENCE_FAILURE,
                        call.name,
                        "Cannot infer type parameter '" + parameter.name()
                                + "'; provide an explicit type argument."));
            }
            substitutions.put(parameter, TypeResolver.validateTypeArgument(
                    context, parameter, substitutions.get(parameter), call.name));
            for (final var bound : parameter.bounds()) {
                final var requiredBound = TypeSubstitution.substitute(bound, substitutions);
                final var actualType = substitutions.get(parameter);
                if (!TypeClassEvidence.hasEvidence(context, requiredBound, actualType, call.name)) {
                    ensureAssignable(context, requiredBound, actualType, call.name);
                }
            }
        }

        call.setEvidenceArguments(TypeClassEvidence.selectArguments(
                context, typeParameters, substitutions, call.name));
        final var instantiatedParameters = genericType.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                .toList();
        for (int i = 0; i < resolvedArguments.length; i++) {
            ensureAssignable(context, memberParameterType(instantiatedParameters, i, fixedArity, variadic),
                    resolvedArguments[i], call.name);
        }
        final var instantiatedReturn = TypeSubstitution.substitute(genericType.returnType(), substitutions);
        final var exposedReturn = exposeProjectedResult(instantiatedReturn, call.name);
        call.setResolvedDescriptor(TypeDescriptor.functionOf(genericType.name(), instantiatedReturn,
                instantiatedParameters.toArray(TypeDescriptor[]::new)));
        if (variadic) {
            call.setVariadic(((ArrayDescriptor) instantiatedParameters.getLast()).elementType(), fixedArity);
        }
        call.setType(exposedReturn);
        return exposedReturn;
    }

    private static TypeDescriptor resolveJavaTypeCall(final ResolutionContext context, final Expr.MemberCall call,
                                               final JavaClassPath.JavaClass javaClass,
                                               final TypeDescriptor expectedType) {
        final var constructor = call.name.lexeme().equals("new");
        if (constructor && (javaClass.isInterface() || javaClass.isAbstract())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    call.name,
                    "Cannot construct an abstract Java class or interface."));
        }
        if (!constructor && !call.explicitOwnerTypeArguments().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    call.name, "Explicit owner type arguments are only valid on constructor calls."));
        }
        final var methodName = constructor ? "<init>" : call.name.lexeme();
        if (constructor && !call.explicitOwnerTypeArguments().isEmpty()
                && call.explicitOwnerTypeArguments().size() != javaClass.typeParameters().size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name, "Expected " + javaClass.typeParameters().size() + " Java class type arguments, found "
                            + call.explicitOwnerTypeArguments().size() + "."));
        }
        return resolveJavaCall(context, call, javaClass, methodName, constructor, !constructor, null, null,
                expectedType);
    }

    private static TypeDescriptor javaFieldType(final ResolutionContext context,
                                                final JavaClassPath.JavaClass javaClass,
                                                final JavaClassPath.JavaField field,
                                                final TypeDescriptor receiverType,
                                                final Token where) {
        if (field.genericSignature() == null) return JavaTypeMapping.toZeronType(field.descriptor());
        final TypeDescriptor fieldType;
        try {
            fieldType = JavaGenericSignatureParser.parseFieldType(
                    field.genericSignature(), javaClass.typeParameters());
        } catch (final IllegalArgumentException _) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE,
                    where, "The Java field generic signature is not supported."));
            throw new IllegalStateException("Unreachable");
        }
        final var substitutions = receiverType == null
                ? Map.<TypeParameterDescriptor, TypeDescriptor>of()
                : TypeResolver.substitutionsFor(context, javaClass.typeParameters(), receiverType);
        return TypeSubstitution.substitute(fieldType, substitutions);
    }

    private static TypeDescriptor exposeProjectedResult(final TypeDescriptor resultType,
                                                        final Token where) {
        if (TypeSubstitution.hasUnexposableCapture(resultType)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE,
                    where, "A captured projected type cannot escape inside a compound result type."));
        }
        return TypeSubstitution.widenCapture(resultType);
    }

    private static TypeDescriptor resolveJavaInstanceCall(final ResolutionContext context, final Expr.MemberCall call,
                                                   final TypeDescriptor receiverType,
                                                   final JavaClassPath.JavaClass javaClass) {
        final var externalClass = context.externalClasses.get(javaClass.binaryName());
        if (!(receiverType instanceof ReferenceDescriptor) && externalClass == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE,
                    call.name,
                    "Java instance method calls require a mutable & receiver."));
        }
        return resolveJavaCall(context, call, javaClass, call.name.lexeme(), false, false,
                receiverType instanceof ReferenceDescriptor, receiverType, null);
    }

    private static boolean externalFacadeAllows(final ResolutionContext context,
                                                final JavaClassPath.JavaClass javaClass,
                                                final JavaClassPath.JavaMethod method,
                                                final boolean constructor,
                                                final Boolean mutableReceiver,
                                                final int argumentCount) {
        final var facade = context.externalClasses.get(javaClass.binaryName());
        if (facade == null) return true;
        if (constructor) {
            return facade.constructors().stream()
                    .anyMatch(declaration -> DeclarationRegistry.matchesExternalConstructor(declaration, method));
        }
        return facade.methods().stream()
                .filter(declaration -> declaration.isStatic() == (mutableReceiver == null))
                .filter(declaration -> declaration.isStatic()
                        || !declaration.isMutating() || Boolean.TRUE.equals(mutableReceiver))
                .filter(declaration -> !method.isVarArgs()
                        || declaration.typeDescriptor().parameters().size()
                                != method.descriptor().parameterCount() - 1
                        || argumentCount == method.descriptor().parameterCount() - 1)
                .anyMatch(declaration -> DeclarationRegistry.matchesExternalMethod(declaration, method));
    }

    private static TypeDescriptor resolveJavaCall(final ResolutionContext context, final Expr.MemberCall call,
                                           final JavaClassPath.JavaClass javaClass,
                                           final String methodName,
                                           final boolean constructor,
                                           final boolean staticCall,
                                           final Boolean mutableReceiver,
                                           final TypeDescriptor receiverType,
                                           final TypeDescriptor expectedType) {
        if (call.arguments.stream().anyMatch(Expr.Lambda.class::isInstance)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE,
                    call.name,
                    "Java functional-interface callback conversion is not supported by this interop slice."));
        }
        final var actualTypes = call.arguments.stream().map(expression -> ExpressionFlowResolver.resolveExpression(context, expression)).toList();
        final var namedCandidates = javaClass.methods().stream()
                .filter(method -> method.name().equals(methodName))
                .filter(method -> method.isConstructor() == constructor)
                .filter(method -> constructor || method.isStatic() == staticCall)
                .filter(method -> externalFacadeAllows(context, javaClass, method, constructor,
                        mutableReceiver, call.arguments.size()))
                .toList();
        final var candidates = new ArrayList<JavaCandidate>();
        var hasUnsupportedShape = false;
        var hasGenericInferenceFailure = false;
        var hasExplicitTypeArgumentMismatch = false;
        final var classSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        if (receiverType != null) {
            classSubstitutions.putAll(
                    TypeResolver.substitutionsFor(context, javaClass.typeParameters(), receiverType));
        }
        if (constructor) {
            final var explicitOwnerArguments = call.explicitOwnerTypeArguments();
            for (int i = 0; i < explicitOwnerArguments.size(); i++) {
                final var explicitType = explicitOwnerArguments.get(i);
                if (explicitType instanceof TypeProjectionDescriptor) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                            call.name, "A Java constructor requires concrete class type arguments."));
                }
                TypeResolver.validateType(context, explicitType, call.name);
                classSubstitutions.put(javaClass.typeParameters().get(i), explicitType);
            }
            if (explicitOwnerArguments.isEmpty()) {
                var expectedBase = expectedType;
                if (expectedBase instanceof ReferenceDescriptor reference) expectedBase = reference.baseType();
                if (expectedBase instanceof NullableDescriptor nullable) expectedBase = nullable.baseType();
                if (expectedBase instanceof GenericDescriptor generic
                        && generic.name().equals(javaClass.binaryName())
                        && generic.typeParameters().size() == javaClass.typeParameters().size()) {
                    for (int i = 0; i < javaClass.typeParameters().size(); i++) {
                        classSubstitutions.put(javaClass.typeParameters().get(i),
                                generic.typeParameters().get(i));
                    }
                }
            }
        }
        for (final var method : namedCandidates) {
            JavaGenericSignatureParser.MethodSignature genericSignature = null;
            if (method.genericSignature() != null) {
                try {
                    genericSignature = JavaGenericSignatureParser.parseMethodSignature(
                            method.genericSignature(), javaClass.typeParameters());
                } catch (final IllegalArgumentException _) {
                    if (!method.isVarArgs()
                            || call.arguments.size() != method.descriptor().parameterCount() - 1) {
                        hasUnsupportedShape = true;
                        continue;
                    }
                    genericSignature = null;
                }
                if (genericSignature != null
                        && genericSignature.parameterTypes().size() != method.descriptor().parameterCount()) {
                    hasUnsupportedShape = true;
                    continue;
                }
                if (genericSignature != null
                        && genericSignature.throwsTypes().stream().anyMatch(TypeSubstitution::containsTypeParameter)) {
                    hasUnsupportedShape = true;
                    continue;
                }
            }
            if (method.hasGenericSignature()
                    && genericSignature == null
                    && (!method.isVarArgs()
                    || call.arguments.size() != method.descriptor().parameterCount() - 1)) {
                hasUnsupportedShape = true;
                continue;
            }
            final var formalCount = method.descriptor().parameterCount();
            final var fixedCount = method.isVarArgs() ? formalCount - 1 : formalCount;
            if (method.isVarArgs()) {
                if (formalCount == 0 || !method.descriptor().parameterType(formalCount - 1).isArray()) {
                    hasUnsupportedShape = true;
                    continue;
                }
                if (actualTypes.size() < fixedCount) continue;
            } else if (formalCount != actualTypes.size()) {
                continue;
            }
            final var methodTypeParameters = genericSignature == null
                    ? List.<TypeParameterDescriptor>of() : genericSignature.typeParameters();
            if (call.explicitTypeArguments.size() != 0
                    && call.explicitTypeArguments.size() != methodTypeParameters.size()) {
                hasExplicitTypeArgumentMismatch = true;
                continue;
            }
            final var substitutions = new LinkedHashMap<>(classSubstitutions);
            for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
                final var explicitType = call.explicitTypeArguments.get(i);
                TypeResolver.validateType(context, explicitType, call.name);
                substitutions.put(methodTypeParameters.get(i), explicitType);
            }
            final var parameterTypes = new ArrayList<TypeDescriptor>();
            var cost = 0;
            var applicable = true;
            for (int i = 0; i < actualTypes.size(); i++) {
                final var formalIndex = method.isVarArgs() && i >= fixedCount ? formalCount - 1 : i;
                final var javaParameterType = method.isVarArgs() && i >= fixedCount
                        ? method.descriptor().parameterType(formalCount - 1).componentType()
                        : method.descriptor().parameterType(i);
                final var genericParameterPattern = genericSignature == null
                        ? null : genericSignature.parameterTypes().get(formalIndex);
                if (genericParameterPattern != null) {
                    final var inferenceParameters = new ArrayList<>(methodTypeParameters);
                    if (constructor) {
                        inferenceParameters.addAll(javaClass.typeParameters());
                    }
                    final var inferencePattern = TypeSubstitution.substitute(
                            genericParameterPattern, substitutions);
                    if (containsAnyTypeParameter(inferencePattern, inferenceParameters)) {
                        try {
                            TypeUnifier.unify(inferencePattern, actualTypes.get(i), substitutions, call.name);
                        } catch (final ResolutionError _) {
                            applicable = false;
                            break;
                        }
                    }
                }
                final var parameterType = genericParameterPattern == null
                        ? JavaTypeMapping.toZeronMethodType(javaParameterType)
                        : TypeSubstitution.substitute(genericParameterPattern, substitutions);
                if (parameterType == null) {
                    hasUnsupportedShape = true;
                    applicable = false;
                    break;
                }
                final var acceptsObjectArray = isJavaObjectArray(javaParameterType)
                        && isZeronArrayType(actualTypes.get(i));
                if (!acceptsObjectArray
                        && !context.typeCompatibility.canAssign(parameterType, actualTypes.get(i))) {
                    applicable = false;
                    break;
                }
                parameterTypes.add(parameterType);
                cost += acceptsObjectArray
                        ? javaObjectArrayConversionCost(parameterType, actualTypes.get(i))
                        : javaConversionCost(context, parameterType, actualTypes.get(i));
            }
            if (!applicable) continue;

            if (constructor || receiverType != null) {
                for (final var parameter : javaClass.typeParameters()) {
                    if (!substitutions.containsKey(parameter)) {
                        if (constructor) hasGenericInferenceFailure = true;
                        applicable = false;
                        break;
                    }
                }
            }
            for (final var parameter : methodTypeParameters) {
                if (!substitutions.containsKey(parameter)) {
                    hasGenericInferenceFailure = true;
                    applicable = false;
                    break;
                }
            }
            if (!applicable || !javaTypeParameterBoundsSatisfied(
                    context, javaClass.typeParameters(), substitutions)
                    || !javaTypeParameterBoundsSatisfied(context, methodTypeParameters, substitutions)) {
                continue;
            }

            final var constructedType = javaClass.typeParameters().isEmpty()
                    ? TypeDescriptor.ofName(javaClass.binaryName())
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName(javaClass.binaryName()),
                            javaClass.typeParameters().stream().map(substitutions::get).toList());
            var returnType = constructor
                    ? new ReferenceDescriptor(constructedType)
                    : genericSignature == null
                            ? JavaTypeMapping.toZeronReturnType(method.descriptor().returnType())
                            : TypeSubstitution.substitute(genericSignature.returnType(), substitutions);
            if (returnType == null) {
                hasUnsupportedShape = true;
                continue;
            }
            if (!constructor) {
                final var facade = context.externalClasses.get(javaClass.binaryName());
                if (facade != null) {
                    final var declaredMethod = facade.methods().stream()
                            .filter(declaration -> DeclarationRegistry.matchesExternalMethod(declaration, method))
                            .findFirst().orElse(null);
                    if (declaredMethod != null) returnType = declaredMethod.typeDescriptor().returnType();
                }
            }
            returnType = exposeProjectedResult(returnType, call.name);
            if (!constructor) TypeResolver.validateType(context, returnType, call.name);
            candidates.add(new JavaCandidate(method, List.copyOf(parameterTypes), returnType, cost,
                    method.isVarArgs()));
        }

        if (candidates.isEmpty()) {
            if (hasExplicitTypeArgumentMismatch) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        call.name, "The explicit Java method type argument count does not match the selected method."));
            }
            if (hasGenericInferenceFailure && !hasUnsupportedShape) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.GENERIC_INFERENCE_FAILURE,
                        call.name, "Cannot infer all Java generic type arguments; provide explicit type arguments."));
            }
            final var reason = hasUnsupportedShape
                    ? "Matching Java generic, varargs, or unsupported type signatures are not supported."
                    : "No applicable public Java overload was found.";
            Zeron.resolutionError(new ResolutionError(hasUnsupportedShape
                    ? DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE
                    : DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, call.name, reason));
        }
        final var fixedArityCandidates = candidates.stream().filter(candidate -> !candidate.varArgs()).toList();
        final var applicableCandidates = fixedArityCandidates.isEmpty()
            ? candidates.stream().filter(JavaCandidate::varArgs).toList()
            : fixedArityCandidates;
        final var bestCost = applicableCandidates.stream().mapToInt(JavaCandidate::cost).min().orElseThrow();
        final var bestCandidates = applicableCandidates.stream()
            .filter(candidate -> candidate.cost() == bestCost).toList();
        if (bestCandidates.size() != 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    call.name,
                    "Java overload resolution is ambiguous; provide arguments with a more specific type."));
        }
        final var selected = bestCandidates.getFirst();
        for (int i = 0; i < selected.parameterTypes().size(); i++) {
            final var javaParameterType = selected.method().isVarArgs()
                    && i >= selected.method().descriptor().parameterCount() - 1
                    ? selected.method().descriptor().parameterType(
                            selected.method().descriptor().parameterCount() - 1).componentType()
                    : selected.method().descriptor().parameterType(i);
            if (!isJavaObjectArray(javaParameterType) || !isZeronArrayType(actualTypes.get(i))) {
                ensureAssignable(context, selected.parameterTypes().get(i), actualTypes.get(i), call.name);
            }
        }
        final var invocationKind = constructor
                ? JavaCallTarget.InvocationKind.CONSTRUCTOR
                : staticCall ? JavaCallTarget.InvocationKind.STATIC
                : javaClass.isInterface() ? JavaCallTarget.InvocationKind.INTERFACE
                : JavaCallTarget.InvocationKind.VIRTUAL;
        final var facade = context.externalClasses.get(javaClass.binaryName());
        final var facadeMethod = facade == null || constructor ? null : facade.methods().stream()
                .filter(method -> DeclarationRegistry.matchesExternalMethod(method, selected.method()))
                .findFirst().orElse(null);
        final var facadeConstructor = facade == null || !constructor ? null : facade.constructors().stream()
                .filter(declaration -> DeclarationRegistry.matchesExternalConstructor(
                        declaration, selected.method()))
                .findFirst().orElse(null);
        final var returnType = facadeMethod == null
                ? selected.returnType()
                : facadeMethod.typeDescriptor().returnType();
        final var checkedExceptions = selected.method().exceptionTypes().stream()
                .filter(exceptionType -> isCheckedJavaException(context, exceptionType))
                .toList();
        if (!checkedExceptions.isEmpty() && (constructor
                ? facadeConstructor == null : facadeMethod == null)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    call.name, "Java calls declaring checked exceptions require an external facade mapping."));
        }
        final var exceptionMappings = constructor
                ? facadeConstructor == null ? List.<JavaCallTarget.ExceptionMapping>of()
                        : resolveExceptionMappings(context, facadeConstructor, selected.method())
                : facadeMethod == null ? List.<JavaCallTarget.ExceptionMapping>of()
                        : resolveExceptionMappings(context, facadeMethod, selected.method());
        call.setJavaCallTarget(new JavaCallTarget(javaClass.binaryName(), methodName,
            selected.method().descriptor().descriptorString(), invocationKind, selected.varArgs(),
                exceptionMappings));
        final var raisedEffects = facadeMethod != null
                ? facadeMethod.typeDescriptor().raisedEffects()
                : facadeConstructor == null ? List.<TypeDescriptor>of()
                        : facadeConstructor.exceptionMappings().stream()
                                .map(Stmt.ExternalExceptionMapping::effectType).distinct().toList();
        call.setResolvedDescriptor(TypeDescriptor.functionWithEffectsOf(methodName, returnType,
                selected.parameterTypes(), List.of(), raisedEffects));
        call.setType(returnType);
        return returnType;
    }

    static void validateExternalExceptionMappings(final ResolutionContext context,
                                                  final Stmt.ExternalMethod method,
                                                  final JavaClassPath.JavaMethod javaMethod) {
        resolveExceptionMappings(context, method, javaMethod);
    }

    static void validateExternalExceptionMappings(final ResolutionContext context,
                                                  final Stmt.ExternalConstructor constructor,
                                                  final JavaClassPath.JavaMethod javaConstructor) {
        resolveExceptionMappings(context, constructor, javaConstructor);
    }

    static List<JavaCallTarget.ExceptionMapping> resolveExternalFunctionExceptionMappings(
            final ResolutionContext context, final Stmt.ExternalFunction function) {
        final var binding = context.externalFunctionBindings.get(function);
        if (binding == null) {
            throw new IllegalStateException("External function binding is missing: "
                    + function.name().lexeme());
        }
        final var javaMethod = boundJavaMethod(context, binding);
        if (javaMethod == null && !function.exceptionMappings().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    function.name(), "Java exception mappings require public class-file method metadata."));
        }
        return javaMethod == null ? List.of()
                : resolveExceptionMappings(context, function.exceptionMappings(), function.name(),
                        function.typeDescriptor(), javaMethod);
    }

    private static JavaClassPath.JavaMethod boundJavaMethod(
            final ResolutionContext context, final FunctionBindingRegistry.Binding binding) {
        final String ownerName;
        final String methodName;
        final MethodTypeDesc descriptor;
        final boolean isStatic;
        switch (binding.target()) {
            case FunctionBindingRegistry.StaticMethod target -> {
                ownerName = binaryName(target.owner());
                methodName = target.methodName();
                descriptor = target.descriptor();
                isStatic = true;
            }
            case FunctionBindingRegistry.InstanceMethod target -> {
                ownerName = binaryName(target.owner());
                methodName = target.methodName();
                descriptor = target.descriptor();
                isStatic = false;
            }
            case FunctionBindingRegistry.StaticFieldInstanceMethod target -> {
                ownerName = binaryName(target.methodOwner());
                methodName = target.methodName();
                descriptor = target.methodDescriptor();
                isStatic = false;
            }
            case FunctionBindingRegistry.IntrinsicBinding _ -> {
                return null;
            }
        }
        final var javaClass = context.javaClassPath.find(ownerName);
        if (javaClass == null) return null;
        return javaClass.methods().stream()
                .filter(method -> method.name().equals(methodName) && method.isStatic() == isStatic
                        && !method.isConstructor() && method.descriptor().equals(descriptor))
                .findFirst().orElse(null);
    }

    private static String binaryName(final ClassDesc classDescriptor) {
        final var descriptor = classDescriptor.descriptorString();
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
        throw new IllegalArgumentException("Expected a Java reference class, found " + classDescriptor);
    }

    private static List<JavaCallTarget.ExceptionMapping> resolveExceptionMappings(
            final ResolutionContext context, final Stmt.ExternalMethod method,
            final JavaClassPath.JavaMethod javaMethod) {
        TypeResolver.validateFunctionTypes(context, method.typeDescriptor(), method.name());
        return resolveExceptionMappings(context, method.exceptionMappings(), method.name(),
                method.typeDescriptor(), javaMethod);
    }

    private static List<JavaCallTarget.ExceptionMapping> resolveExceptionMappings(
            final ResolutionContext context, final Stmt.ExternalConstructor constructor,
            final JavaClassPath.JavaMethod javaConstructor) {
        return resolveExceptionMappings(context, constructor.exceptionMappings(), constructor.name(), null,
                javaConstructor);
    }

    private static List<JavaCallTarget.ExceptionMapping> resolveExceptionMappings(
            final ResolutionContext context, final List<Stmt.ExternalExceptionMapping> exceptionMappings,
            final Token where, final FunctionDescriptor signature,
            final JavaClassPath.JavaMethod javaMethod) {
        if (signature != null) TypeResolver.validateFunctionTypes(context, signature, where);
        final var mappings = new ArrayList<JavaCallTarget.ExceptionMapping>();
        final var mappedExceptionTypes = new HashSet<String>();
        for (final var mapping : exceptionMappings) {
            TypeResolver.validateType(context, mapping.javaExceptionType(), mapping.constructorName());
            TypeResolver.validateType(context, mapping.effectType(), mapping.constructorName());
            final var exceptionName = mapping.javaExceptionType().name();
            if (!mappedExceptionTypes.add(exceptionName)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        mapping.constructorName(), "A Java exception type can be mapped only once per callable."));
            }
            if (!context.javaClassPath.isSubtypeOf(exceptionName, "java.lang.Throwable")
                    || !isCheckedJavaException(context, exceptionName)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        mapping.constructorName(), "Exception mappings must name checked Java Throwable subtypes."));
            }
            final var effectName = mapping.effectType().name();
            final var effectClass = context.classes.get(effectName);
            if (effectClass == null || !effectClass.isEffect()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        mapping.constructorName(), "A Java exception mapping must target a declared effect type."));
            }

            final boolean canonicalConstructor = mapping.constructorName().lexeme().equals("new");
            final FunctionDescriptor constructorType;
            if (canonicalConstructor) {
                if (!effectClass.constructor().isPublic()
                        || effectClass.canonicalConstructorTypes().size() != 1) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                            mapping.constructorName(),
                            "Mapped effect constructor must be public and accept exactly one Java exception."));
                }
                final var parameterType = effectClass.canonicalConstructorTypes().getFirst();
                if (!acceptsJavaException(context, parameterType, exceptionName)) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                            mapping.constructorName(),
                            "Mapped effect constructor parameter must accept Java exception type '"
                                    + exceptionName + "'."));
                }
                constructorType = TypeDescriptor.functionOf("new",
                        new ReferenceDescriptor(TypeDescriptor.ofName(effectName)), parameterType);
            } else {
                final var constructor = effectClass.namedConstructors().stream()
                        .filter(candidate -> candidate.name().lexeme().equals(mapping.constructorName().lexeme()))
                        .findFirst().orElse(null);
                if (constructor == null || !constructor.isPublic()
                        || constructor.typeDescriptor().parameters().size() != 1) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                            mapping.constructorName(),
                            "Mapped effect constructor must be public and accept exactly one Java exception."));
                }
                TypeResolver.validateFunctionTypes(context, constructor.typeDescriptor(), constructor.name());
                final var parameterType = constructor.typeDescriptor().parameters().getFirst();
                if (!acceptsJavaException(context, parameterType, exceptionName)) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                            mapping.constructorName(),
                            "Mapped effect constructor parameter must accept Java exception type '"
                                    + exceptionName + "'."));
                }
                constructorType = constructor.typeDescriptor();
            }
            final var constructorDescriptor = canonicalConstructor
                    ? java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void,
                            TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(
                                    effectClass.canonicalConstructorTypes().getFirst())))
                    : java.lang.constant.MethodTypeDesc.of(
                            TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(constructorType.returnType())),
                            TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(
                                    constructorType.parameters().getFirst())));
            mappings.add(new JavaCallTarget.ExceptionMapping(exceptionName, effectName,
                    mapping.constructorName().lexeme(), constructorDescriptor.descriptorString(),
                    canonicalConstructor));
        }
        if (javaMethod != null) {
            final var declaredChecked = javaMethod.exceptionTypes().stream()
                    .filter(exceptionType -> isCheckedJavaException(context, exceptionType))
                    .toList();
            for (final var declaredException : declaredChecked) {
                if (mappings.stream().noneMatch(mapping ->
                        context.javaClassPath.isSubtypeOf(declaredException, mapping.exceptionType()))) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, where,
                            "Declared checked Java exception '" + declaredException
                                    + "' must have an explicit Zeron effect mapping."));
                }
            }
            for (final var mapping : mappings) {
                if (declaredChecked.stream().noneMatch(declaredType ->
                        context.javaClassPath.isSubtypeOf(mapping.exceptionType(), declaredType))) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, where,
                            "Mapped Java exception type '" + mapping.exceptionType()
                                    + "' is not a declared exception type or its subtype."));
                }
            }
        }
        if (signature != null) {
            final var declaredEffectNames = signature.raisedEffects().stream()
                    .map(TypeDescriptor::name).collect(java.util.stream.Collectors.toSet());
            final var mappedEffectNames = mappings.stream()
                    .map(JavaCallTarget.ExceptionMapping::effectType)
                    .collect(java.util.stream.Collectors.toSet());
            if (!declaredEffectNames.equals(mappedEffectNames)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        where, "The declared raised effects must match the Java exception mappings."));
            }
        }
        mappings.sort((left, right) -> {
            if (context.javaClassPath.isSubtypeOf(left.exceptionType(), right.exceptionType())) return -1;
            if (context.javaClassPath.isSubtypeOf(right.exceptionType(), left.exceptionType())) return 1;
            return left.exceptionType().compareTo(right.exceptionType());
        });
        return List.copyOf(mappings);
    }

    private static boolean acceptsJavaException(final ResolutionContext context,
                                                final TypeDescriptor parameterType,
                                                final String exceptionName) {
        final var descriptor = TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(parameterType))
                .descriptorString();
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) return false;
        final var parameterName = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        return context.javaClassPath.isSubtypeOf(exceptionName, parameterName);
    }

    private static boolean isCheckedJavaException(final ResolutionContext context,
                                                  final String exceptionType) {
        return context.javaClassPath.isSubtypeOf(exceptionType, "java.lang.Throwable")
                && !context.javaClassPath.isSubtypeOf(exceptionType, "java.lang.RuntimeException")
                && !context.javaClassPath.isSubtypeOf(exceptionType, "java.lang.Error");
    }

    private static boolean javaTypeParameterBoundsSatisfied(
            final ResolutionContext context,
            final List<TypeParameterDescriptor> parameters,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        for (final var parameter : parameters) {
            final var actual = substitutions.get(parameter);
            if (actual == null) return false;
            for (final var bound : parameter.bounds()) {
                final var required = TypeSubstitution.substitute(bound, substitutions);
                if (!context.typeCompatibility.canAssign(required, actual)
                        && !javaBoxedTypeSatisfiesBound(context, required, actual)) return false;
            }
        }
        return true;
    }

    private static boolean javaBoxedTypeSatisfiesBound(final ResolutionContext context,
                                                       final TypeDescriptor required,
                                                       final TypeDescriptor actual) {
        var actualJavaType = TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(actual)).descriptorString();
        final var requiredJavaType = TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(required))
                .descriptorString();
        actualJavaType = switch (actualJavaType) {
            case "I" -> "Ljava/lang/Integer;";
            case "J" -> "Ljava/lang/Long;";
            case "B" -> "Ljava/lang/Byte;";
            case "D", "F" -> "Ljava/lang/Double;";
            case "Z" -> "Ljava/lang/Boolean;";
            default -> actualJavaType;
        };
        if (!actualJavaType.startsWith("L") || !requiredJavaType.startsWith("L")) return false;
        final var actualName = actualJavaType.substring(1, actualJavaType.length() - 1).replace('/', '.');
        final var requiredName = requiredJavaType.substring(1, requiredJavaType.length() - 1).replace('/', '.');
        return context.javaClassPath.isSubtypeOf(actualName, requiredName);
    }

    private static int javaConversionCost(final ResolutionContext context, final TypeDescriptor expected, final TypeDescriptor actual) {
        if (expected.equals(actual)) return 0;
        if (expected instanceof NullableDescriptor nullable && nullable.baseType().equals(actual)) return 1;
        return 2;
    }

    private static boolean isJavaObjectArray(final ClassDesc javaType) {
        return javaType.descriptorString().equals("[Ljava/lang/Object;");
    }

    private static boolean isZeronArrayType(final TypeDescriptor type) {
        return switch (type) {
            case ArrayDescriptor _ -> true;
            case NullableDescriptor nullable -> isZeronArrayType(nullable.baseType());
            case ReferenceDescriptor reference -> isZeronArrayType(reference.baseType());
            default -> false;
        };
    }

    private static int javaObjectArrayConversionCost(final TypeDescriptor expected, final TypeDescriptor actual) {
        return expected.equals(actual) ? 0 : 1;
    }

    private static TypeDescriptor resolveBoundedMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                                    final TypeParameterDescriptor parameter) {
        return resolveBoundedMemberCall(context, call, parameter, null);
    }

    private static TypeDescriptor resolveBoundedMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                                    final TypeParameterDescriptor parameter,
                                                    final TypeDescriptor requiredContractType) {
        return resolveBoundedMemberCall(context, call, parameter, requiredContractType, false);
    }

    private static TypeDescriptor resolveBoundedMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                                    final TypeParameterDescriptor parameter,
                                                    final TypeDescriptor requiredContractType,
                                                    final boolean behavioralOnly) {
        if (parameter.bounds().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "Member access on an unconstrained type parameter is not allowed."));
        }
        TypeDescriptor bound = null;
        Stmt.ContractMethod method = null;
        Map<TypeParameterDescriptor, TypeDescriptor> selectedContractSubstitutions = Map.of();
        int selectedBoundIndex = -1;
        int selectedMethodIndex = -1;
        final var matches = new LinkedHashMap<String, Map.Entry<Integer, ContractComposition.MethodSlot>>();
        final var requiredUse = requiredContractType == null
                ? null : ContractComposition.fromType(requiredContractType);
        for (int boundIndex = 0; boundIndex < parameter.bounds().size(); boundIndex++) {
            final var candidateBound = parameter.bounds().get(boundIndex);
            final var candidateUse = ContractComposition.fromType(candidateBound);
            if (candidateUse == null) continue;
            final var candidateDeclaration = context.contracts.get(candidateUse.name().lexeme());
            if (candidateDeclaration == null || behavioralOnly && candidateDeclaration.higherKinded()) continue;
            if (requiredUse != null && (candidateUse == null
                    || !ContractComposition.entails(context, candidateUse,
                            requiredUse.name().lexeme(), requiredUse.typeArguments()))) {
                continue;
            }
            final var candidateBoundIndex = boundIndex;
            final var methodSlot = ContractComposition.methods(context, candidateUse).stream()
                    .filter(slot -> slot.method().name().lexeme().equals(call.name.lexeme()))
                    .filter(slot -> requiredUse == null
                            || slot.applied().use().name().lexeme().equals(requiredUse.name().lexeme())
                            && slot.applied().use().typeArguments().equals(requiredUse.typeArguments()))
                    .findFirst().orElse(null);
            if (methodSlot != null) {
                matches.putIfAbsent(methodProviderKey(methodSlot), Map.entry(candidateBoundIndex, methodSlot));
            }
        }
        if (matches.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "Member is ambiguous across the type parameter's contract bounds."));
        }
        if (!matches.isEmpty()) {
            final var selected = matches.values().iterator().next();
            selectedBoundIndex = selected.getKey();
            final var candidateBound = parameter.bounds().get(selectedBoundIndex);
            final var methodSlot = selected.getValue();
            bound = candidateBound;
            method = methodSlot.method();
            selectedContractSubstitutions = methodSlot.applied().substitutions();
            selectedMethodIndex = methodSlot.index();
        }
        if (method == null) {
            final var functionPropertyResult = resolveFunctionPropertyCall(
                    context, call, call.receiver.getType());
            if (functionPropertyResult != null) return functionPropertyResult;
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "Method is not provided by any of the type parameter's contract bounds."));
        }
        if (method.isMutating() && !(call.receiver.getType() instanceof ReferenceDescriptor)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED, call.name,
                    "Mutating methods require a mutable reference receiver."));
        }
        final var ownerName = className(context, bound);

        final var descriptor = (FunctionDescriptor) TypeSubstitution.substitute(
                method.typeDescriptor(), selectedContractSubstitutions);
        final var evidenceMethodParameters = new ArrayList<TypeDescriptor>();
        if (!method.isStatic()) evidenceMethodParameters.add(call.receiver.getType());
        evidenceMethodParameters.addAll(descriptor.parameters());
        final var evidenceMethodType = TypeDescriptor.functionWithEffectsOf(
                method.name().lexeme(), descriptor.returnType(), evidenceMethodParameters,
                descriptor.typeParameters(), descriptor.raisedEffects());
        call.setWitnessMethodEvidence(Expr.methodEvidenceToken(parameter, selectedBoundIndex, selectedMethodIndex),
                evidenceMethodType, !method.isStatic());
        final var variadic = method.variadic();
        final var fixedArity = Stmt.fixedArity(method.parameters(), variadic);
        if (descriptor.isGeneric()) {
            final var result = resolveGenericMemberCall(context, call, descriptor,
                    method.minimumArity(), variadic, fixedArity);
            call.setResolvedOwnerName(ownerName);
            call.setReceiverRequiresCast(true);
            return result;
        }
        ensureMemberCallArity(call, descriptor, method.minimumArity(), variadic);
        if (variadic) {
            call.setVariadic(((ArrayDescriptor) descriptor.parameters().getLast()).elementType(), fixedArity);
        }
        for (int index = 0; index < call.arguments.size(); index++) {
            final var expected = memberParameterType(descriptor.parameters(), index, fixedArity, variadic);
            ensureAssignable(context, expected, resolveArgument(context, call.arguments.get(index), expected), call.name);
        }
        call.setResolvedOwnerName(ownerName);
        call.setReceiverRequiresCast(true);
        call.setResolvedDescriptor(descriptor);
        call.setType(descriptor.returnType());
        return descriptor.returnType();
    }

    static String resolveClassName(final ResolutionContext context, final String name, final Token where) {
        if (name.contains(".")) {
            if (context.classes.containsKey(name) || context.contracts.containsKey(name)) return name;
            return context.javaClassPath.find(name) != null ? name : null;
        }
        final var qualifiedName = context.packageName.isEmpty() ? name : context.packageName + "." + name;
        if (context.classes.containsKey(qualifiedName) || context.contracts.containsKey(qualifiedName)) {
            return qualifiedName;
        }
        final var importedName = context.currentImports.types().get(name);
        if (importedName != null
                && (context.classes.containsKey(importedName) || context.contracts.containsKey(importedName)
                    || context.javaClassPath.find(importedName) != null)) {
            return importedName;
        }
        if (context.packageName.isEmpty()
                && (context.classes.containsKey(name) || context.contracts.containsKey(name))) {
            return name;
        }
        final var starCandidates = starTypeCandidates(context, name);
        if (starCandidates.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT, where,
                    "Ambiguous type '" + name + "' from star imports; add an explicit import or alias."));
        }
        if (starCandidates.size() == 1) return starCandidates.getFirst();
        return null;
    }

    private static List<String> starTypeCandidates(final ResolutionContext context, final String simpleTypeName) {
        return context.currentImports.onDemandPackages().stream()
                .filter(importedPackage -> !importedPackage.equals(context.packageName))
                .map(importedPackage -> importedPackage + "." + simpleTypeName)
                .filter(name -> {
                    final var classDeclaration = context.classes.get(name);
                    final var contractDeclaration = context.contracts.get(name);
                    return classDeclaration != null && classDeclaration.isPublic()
                            || contractDeclaration != null && contractDeclaration.isPublic();
                })
                .distinct()
                .toList();
    }

    static void validateStarTypeAmbiguity(final ResolutionContext context, final String qualifiedName, final Token where) {
        final var importedTypeName = simpleName(qualifiedName);
        final var fromStarImport = context.currentImports.onDemandPackages().stream()
                .anyMatch(importedPackage -> qualifiedName.startsWith(importedPackage + "."));
        if (fromStarImport && !context.currentImports.types().containsValue(qualifiedName)
                && starTypeCandidates(context, importedTypeName).size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT, where,
                    "Ambiguous type '" + importedTypeName
                            + "' from star imports; add an explicit import or alias."));
        }
    }

    static String resolveFunctionName(final ResolutionContext context, final String name, final Token where) {
        context.skipInvalidImportAlias(name);
        if (context.currentNamespaceName != null) {
            final var namespaceName = qualifyNamespaceMember(context.packageName,
                    context.currentNamespaceName, name);
            if (hasOrdinaryFunction(context, namespaceName)) return namespaceName;
        }
        final var localName = qualify(context.packageName, name);
        if (hasOrdinaryFunction(context, localName)) return localName;
        final var explicitImport = context.currentImports.functions().get(name);
        if (explicitImport != null) return explicitImport;
        final var candidates = context.currentImports.onDemandPackages().stream()
                .filter(importedPackage -> !importedPackage.equals(context.packageName))
                .map(importedPackage -> qualify(importedPackage, name))
                .filter(functionName -> hasOrdinaryFunction(context, functionName))
                .filter(functionName -> context.functions.get(functionName).isPublic())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        context.currentImports.onDemandNamespaces().stream()
                .map(namespace -> qualifyNamespaceMember("", namespace, name))
                .filter(functionName -> hasOrdinaryFunction(context, functionName))
                .filter(functionName -> isNamespaceMemberAccessible(context, functionName,
                        context.functions.get(functionName).isPublic()))
                .forEach(candidates::add);
        if (candidates.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT, where,
                    "Ambiguous function '" + name + "' from star imports; add an explicit import or alias."));
        }
        return candidates.isEmpty() ? null : candidates.iterator().next();
    }

    private static boolean hasOrdinaryFunction(final ResolutionContext context, final String name) {
        final var function = context.functions.get(name);
        return function != null && !(function instanceof Stmt.ExtensionMethod);
    }

    static Token functionSymbolToken(
            final ResolutionContext context, final Token declarationName) {
        final var functionName = context.functionNamesByDeclaration.getOrDefault(
                declarationName, qualify(context.packageName, declarationName.lexeme()));
        return context.functionSymbolTokens.getOrDefault(functionName, declarationName);
    }

    static Token resolveTopLevelValueSymbol(final ResolutionContext context, final Token name) {
        context.skipInvalidImportAlias(name.lexeme());
        if (context.currentNamespaceName != null) {
            final var namespaceValueName = qualifyNamespaceMember(context.packageName,
                    context.currentNamespaceName, name.lexeme());
            if (context.topLevelValues.containsKey(namespaceValueName)) {
                return context.topLevelValueSymbols.get(namespaceValueName);
            }
        }
        final var localName = qualify(context.packageName, name.lexeme());
        if (context.topLevelValues.containsKey(localName)) return context.topLevelValueSymbols.get(localName);
        final var explicitImport = context.currentImports.values().get(name.lexeme());
        if (explicitImport != null) return context.topLevelValueSymbols.get(explicitImport);
        final var candidates = context.currentImports.onDemandPackages().stream()
                .filter(importedPackage -> !importedPackage.equals(context.packageName))
                .map(importedPackage -> qualify(importedPackage, name.lexeme()))
                .filter(context.topLevelValues::containsKey)
                .filter(valueName -> context.topLevelValues.get(valueName).isPublic())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        context.currentImports.onDemandNamespaces().stream()
                .map(namespace -> qualifyNamespaceMember("", namespace, name.lexeme()))
                .filter(context.topLevelValues::containsKey)
                .filter(valueName -> isNamespaceMemberAccessible(context, valueName,
                        context.topLevelValues.get(valueName).isPublic()))
                .forEach(candidates::add);
        if (candidates.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, name,
                    "Ambiguous value '" + name.lexeme()
                            + "' from star imports; add an explicit import or alias."));
        }
        return candidates.isEmpty() ? null : context.topLevelValueSymbols.get(candidates.iterator().next());
    }

    private static String namespaceMemberName(final ResolutionContext context, final Expr receiver,
                                              final String memberName) {
        final var path = expressionPath(receiver);
        if (path == null) return null;
        final var localName = qualifyNamespaceMember(context.packageName, path, memberName);
        if (context.functions.containsKey(localName) || context.topLevelValues.containsKey(localName)) {
            return localName;
        }
        final var importedTypeName = context.currentImports.types().get(path);
        if (importedTypeName != null) {
            final var importedNamespaceMember = importedTypeName + "." + memberName;
            if (context.functions.containsKey(importedNamespaceMember)
                    || context.topLevelValues.containsKey(importedNamespaceMember)) {
                return importedNamespaceMember;
            }
        }
        final var starNamespaceMembers = context.currentImports.onDemandPackages().stream()
                .filter(importedPackage -> !importedPackage.equals(context.packageName))
                .map(importedPackage -> importedPackage + "." + path + "." + memberName)
                .filter(qualifiedName -> context.functions.containsKey(qualifiedName)
                        || context.topLevelValues.containsKey(qualifiedName))
                .filter(qualifiedName -> isNamespaceMemberAccessible(context, qualifiedName,
                        context.functions.containsKey(qualifiedName)
                                ? context.functions.get(qualifiedName).isPublic()
                                : context.topLevelValues.get(qualifiedName).isPublic()))
                .distinct()
                .toList();
        if (starNamespaceMembers.size() > 1) {
            final var where = receiver instanceof Expr.Variable variable
                    ? variable.name : ((Expr.Property) receiver).name;
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                    where,
                    "Ambiguous namespace member '" + path + "." + memberName
                            + "' from star imports; add an explicit import or alias."));
        }
        if (starNamespaceMembers.size() == 1) return starNamespaceMembers.getFirst();
        final var qualifiedName = path + "." + memberName;
        if (context.functions.containsKey(qualifiedName) || context.topLevelValues.containsKey(qualifiedName)) {
            return qualifiedName;
        }
        return null;
    }

    private static String expressionPath(final Expr expression) {
        if (expression instanceof Expr.Variable variable) return variable.name.lexeme();
        if (expression instanceof Expr.Property property && !property.safeNavigation()) {
            final var prefix = expressionPath(property.receiver);
            return prefix == null ? null : prefix + "." + property.name.lexeme();
        }
        return null;
    }

    private static String qualifyNamespaceMember(final String ownerPackage, final String namespacePath,
                                                 final String memberName) {
        final var prefix = ownerPackage == null || ownerPackage.isEmpty()
                ? namespacePath
                : ownerPackage + "." + namespacePath;
        return prefix + "." + memberName;
    }

    private static boolean isNamespaceMemberAccessible(final ResolutionContext context,
                                                       final String qualifiedName,
                                                       final boolean isPublic) {
        return Objects.equals(context.namespaceMemberPackages.get(qualifiedName), context.packageName)
                || isPublic;
    }

    static TypeDescriptor resolveArgument(final ResolutionContext context, final Expr argument,
                                           final TypeDescriptor expectedType) {
        if (argument instanceof Expr.Literal literal
                || argument instanceof Expr.Grouping
                || argument instanceof Expr.Unary
                || argument instanceof Expr.Binary) {
            final var contextual = ExpressionFlowResolver.resolveExpectedExpression(
                    context, argument, expectedType);
            if (contextual != null) return contextual;
        }
        if (argument instanceof Expr.MemberCall call) {
            return resolveMemberCall(context, call, expectedType);
        }
        if (argument instanceof Expr.ArrayLiteral literal && literal.elements.isEmpty()) {
            return ExpressionFlowResolver.resolveEmptyArrayLiteral(context, literal, expectedType);
        }
        if (argument instanceof Expr.Variable variable && context.symbols.containsSymbol(variable.name)) {
            final var binding = context.symbols.getSymbol(variable.name);
            var bindingType = binding.type();
            if (bindingType instanceof ReferenceDescriptor reference) bindingType = reference.baseType();
            if (bindingType instanceof FunctionDescriptor scheme && scheme.isGeneric()) {
                final var expectedFunction = LambdaResolver.functionType(context, expectedType);
                if (expectedFunction != null) {
                    if (binding.declaration() instanceof Stmt.Var declaration) {
                        variable.setResolvedValue(binding.name(), declaration);
                    }
                    final var specialized = LambdaResolver.instantiateLambdaScheme(
                            context, scheme, expectedFunction, variable, variable.name);
                    variable.setStoredFunctionType(scheme);
                    variable.setType(specialized);
                    return specialized;
                }
            }
        }
        if (argument instanceof Expr.Variable variable
                && (variable.resolvedFunctionName() != null
                || !context.symbols.containsSymbol(variable.name)
                && resolveFunctionName(context, variable.name.lexeme(), variable.name) != null)) {
            return LambdaResolver.resolveFunctionReference(context, variable, expectedType);
        }
        if (argument instanceof Expr.Lambda lambda) {
            final var functionType = LambdaResolver.functionType(context, expectedType);
            if (functionType != null) return resolveLambda(context, lambda, functionType);
        }
        return ExpressionFlowResolver.resolveExpression(context, argument);
    }

    static Stmt.Field findField(final ResolutionContext context, final String className, final Token name) {
        final var owner = context.classes.get(className);
        if (owner == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    name, "Field receiver is not a class."));
        }
        return owner.fields().stream()
                .filter(field -> field.name().lexeme().equals(name.lexeme()))
                .findFirst()
                .orElseThrow(() -> new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        name, "Unknown field."));
    }

    static PropertyInfo findProperty(final ResolutionContext context, final String ownerName, final Token name) {
        final var owner = context.classes.get(ownerName);
        if (owner != null) {
            return owner.properties().stream()
                    .filter(property -> property.name().lexeme().equals(name.lexeme()))
                    .findFirst()
                    .map(property -> new PropertyInfo(property.name().lexeme(), property.name(),
                            property.type(), property.isPublic(), property.isMutating()))
                    .orElse(null);
        }
        final var contract = context.contracts.get(ownerName);
        if (contract == null) return null;
        for (final var applied : ContractComposition.closure(context,
                new Stmt.ContractUse(new Token(TokenType.IDENTIFIER, ownerName, null, name.span()),
                        nominalArguments(context, ownerName, name)))) {
            for (final var property : applied.declaration().properties()) {
                if (property.name().lexeme().equals(name.lexeme())) {
                    return new PropertyInfo(property.name().lexeme(), property.name(),
                            TypeSubstitution.substitute(property.type(), applied.substitutions()),
                            true, property.isMutating());
                }
            }
        }
        return null;
    }

    static TypeDescriptor resolvedPropertyType(final ResolutionContext context, final String ownerName,
                                                final PropertyInfo property,
                                                final TypeDescriptor receiverType) {
        final var owner = context.classes.get(ownerName);
        if (owner != null) {
            return TypeSubstitution.substitute(property.type(),
                    TypeResolver.substitutionsFor(context, owner.typeParameters(), receiverType));
        }
        final var applied = contractCompositionForType(context, ownerName, receiverType).stream()
                .filter(candidate -> candidate.declaration().properties().stream()
                        .anyMatch(required -> required.name().lexeme().equals(property.name())))
                .findFirst().orElse(null);
        return applied == null ? property.type()
                : TypeSubstitution.substitute(property.type(), applied.substitutions());
    }

    private static List<TypeDescriptor> nominalArguments(final ResolutionContext context,
                                                         final String ownerName, final Token where) {
        final var owner = context.contracts.get(ownerName);
        if (owner == null || owner.typeParameters().isEmpty()) return List.of();
        return owner.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList();
    }

    private static List<ContractComposition.Applied> contractCompositionForType(
            final ResolutionContext context, final String ownerName, final TypeDescriptor receiverType) {
        final var baseType = receiverType instanceof ReferenceDescriptor reference
                ? reference.baseType() : receiverType;
        if (baseType instanceof TypeParameterDescriptor parameter) {
            return parameter.bounds().stream()
                    .flatMap(bound -> contractCompositionForType(context, ownerName, bound).stream())
                    .toList();
        }
        final var arguments = baseType instanceof GenericDescriptor generic
                ? generic.typeParameters() : List.<TypeDescriptor>of();
        final var contract = context.contracts.get(ownerName);
        final var captureSubstitutions = contract == null
                ? Map.<TypeParameterDescriptor, TypeDescriptor>of()
                : TypeResolver.substitutionsFor(context, contract.typeParameters(), baseType);
        final var capturedArguments = contract == null
                ? arguments
                : contract.typeParameters().stream()
                        .map(captureSubstitutions::get)
                        .toList();
        final var token = new Token(TokenType.IDENTIFIER, ownerName, null,
                com.maruseron.zeron.diagnostic.SourceSpan.line(null, 1));
        return ContractComposition.closure(context, new Stmt.ContractUse(token, capturedArguments));
    }

    static void ensureFieldAccessible(final ResolutionContext context, final Token where, final String owner) {
        if (!Objects.equals(context.frame.currentClassName, owner)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                    where, "Field is private."));
        }
    }

}
