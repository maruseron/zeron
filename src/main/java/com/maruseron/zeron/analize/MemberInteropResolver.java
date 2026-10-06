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

final class MemberInteropResolver {
    record PropertyInfo(TypeDescriptor type, boolean isPublic, boolean isMutating) {}
    private record SafeNavigationFlows(FlowState nonNull, FlowState nullPath) {}
    private record JavaCandidate(JavaClassPath.JavaMethod method, List<TypeDescriptor> parameterTypes,
                                 TypeDescriptor returnType, int cost, boolean varArgs) {}
    static TypeDescriptor resolveImplicitFieldRead(final ResolutionContext context, final Expr.Variable variable) {
        if (context.frame.currentMethodOwner == null) return null;
        final var availableFields = context.frame.initializerVisibleFields == null
                ? context.frame.currentMethodOwner.fields()
                : context.frame.initializerVisibleFields;
        final var field = availableFields.stream()
                .filter(candidate -> candidate.name().lexeme().equals(variable.name.lexeme()))
                .findFirst()
                .orElse(null);
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
        return resolvedType;
    }

    static TypeDescriptor resolveProperty(final ResolutionContext context, final Expr.Property property) {
        if (property.receiver instanceof Expr.Variable typeName) {
            final var ownerName = resolveClassName(context, typeName.name.lexeme(), typeName.name);
            final var externalClass = ownerName == null ? null : context.externalClasses.get(ownerName);
            if (externalClass != null || "java.lang.System".equals(ownerName)) {
                if (property.safeNavigation()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                            property.name, "Safe navigation cannot be used for a static property."));
                }
                final var declaredProperty = externalClass == null ? null : externalClass.staticProperties().stream()
                        .filter(candidate -> candidate.name().lexeme().equals(property.name.lexeme()))
                        .findFirst().orElse(null);
                final var javaClass = context.javaClassPath.find(ownerName);
                final var javaField = javaClass == null ? null : javaClass.fields().stream()
                        .filter(candidate -> candidate.name().equals(property.name.lexeme()) && candidate.isStatic())
                        .findFirst().orElse(null);
                final var fieldType = javaField == null ? null : JavaTypeMapping.toZeronType(javaField.descriptor());
                if (javaField == null || fieldType == null
                        || externalClass != null && (declaredProperty == null || !declaredProperty.isPublic()
                            || !fieldType.equals(declaredProperty.type()))
                        || externalClass == null && !property.name.lexeme().equals("out")) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                            property.name, "Unknown or unsupported external static property."));
                }
                property.setJavaFieldTarget(new JavaFieldTarget(ownerName, javaField.name(),
                        javaField.descriptor().descriptorString()));
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
        final var receiverType = ExpressionFlowResolver.resolveExpression(context, property.receiver);
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
        final var ownerName = className(context, baseType);
        final var owner = context.classes.get(ownerName);
        final var propertyDeclaration = findProperty(context, ownerName, property.name);
        if (propertyDeclaration != null) {
            if (!propertyDeclaration.isPublic()
                    && !Objects.equals(context.frame.currentClassName, ownerName)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                        property.name, "Property is private."));
            }
            final var propertyType = resolvedPropertyType(context, ownerName, propertyDeclaration, memberReceiverType);
            property.setResolvedOwnerName(ownerName);
            property.setResolvedAsProperty(true);
            final var resultType = property.safeNavigation()
                    ? propertyType.toNullable()
                    : propertyType;
            property.setType(resultType);
            finishSafeNavigation(context, safeFlows);
            return resultType;
        }
        if (owner == null) Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                property.name, "Unknown property."));
        final var field = owner.fields().stream()
                .filter(candidate -> candidate.name().lexeme().equals(property.name.lexeme()))
                .findFirst()
                .orElse(null);
        if (field == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    property.name, "Unknown field."));
        }
        ensureFieldAccessible(context, property.name, ownerName);
        final var fieldType = TypeSubstitution.substitute(field.type(),
                TypeResolver.substitutionsFor(context, owner.typeParameters(), memberReceiverType));
        final var resultType = property.safeNavigation() ? fieldType.toNullable() : fieldType;
        property.setType(resultType);
        finishSafeNavigation(context, safeFlows);
        return resultType;
    }

    static TypeDescriptor resolveMemberCall(final ResolutionContext context, final Expr.MemberCall call) {
        if (call.safeNavigation()) {
            final var receiverType = ExpressionFlowResolver.resolveExpression(context, call.receiver);
            final var safeFlows = beginSafeNavigation(context, call.receiver, call.name, receiverType);
            final var memberReceiverType = receiverType instanceof NullableDescriptor nullable
                    ? nullable.baseType()
                    : receiverType;
            context.frame.flowState = safeFlows.nonNull().copy();
            final var resultType = resolveMemberCall(context, call, memberReceiverType);
            finishSafeNavigation(context, safeFlows);
            final var nullableResult = resultType.toNullable();
            call.setType(nullableResult);
            return nullableResult;
        }
        return resolveMemberCall(context, call, null);
    }

    private static TypeDescriptor resolveMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                             final TypeDescriptor alreadyResolvedReceiverType) {
        Zeron.debug("resolving member call       " + call.name.lexeme() + " for " + call.receiver);
        if (!call.safeNavigation()) {
            final var namespaceFunctionName = namespaceMemberName(context, call.receiver, call.name.lexeme());
            final var namespaceFunction = namespaceFunctionName == null
                    ? null : context.functions.get(namespaceFunctionName);
            if (namespaceFunction != null) {
                if (!isNamespaceMemberAccessible(context, namespaceFunctionName, namespaceFunction.isPublic())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                            call.name, "Namespace function '" + namespaceFunctionName + "' is private."));
                }
                return context.callResolver.resolveNamespaceCall(call, namespaceFunctionName);
            }
        }
        final var classOwnerName = call.receiver instanceof Expr.Variable typeName
            ? resolveClassName(context, typeName.name.lexeme(), typeName.name)
            : null;
        if (call.safeNavigation() && classOwnerName != null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "Safe navigation cannot be used for constructors or static calls."));
        }
        if (classOwnerName != null) {
            final var declaration = context.classes.get(classOwnerName);
            if (declaration == null) {
                final var javaClass = context.javaClassPath.find(classOwnerName);
                if (javaClass != null) return resolveJavaTypeCall(context, call, javaClass);
            }
            call.setResolvedClassName(classOwnerName);
            if (declaration.typeParameters().size() != call.explicitTypeArguments.size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        call.name,
                        "Expected " + declaration.typeParameters().size() + " class type arguments, found "
                                + call.explicitTypeArguments.size() + "."));
            }
            call.explicitTypeArguments.forEach(type -> TypeResolver.validateType(context, type, call.name));
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            for (int i = 0; i < declaration.typeParameters().size(); i++) {
                substitutions.put(declaration.typeParameters().get(i), call.explicitTypeArguments.get(i));
            }
                if (call.name.lexeme().equals("new")) {
                if (!declaration.constructor().isPublic()
                    && !Objects.equals(context.frame.currentClassName, declaration.name().lexeme())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                            call.name, "Constructor is private."));
                }
                final var constructorTypes = declaration.canonicalConstructorTypes();
                if (call.arguments.size() != constructorTypes.size()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                            call.name,
                        "Expected " + constructorTypes.size() + " constructor arguments, found "
                            + call.arguments.size() + "."));
                }
                for (int i = 0; i < call.arguments.size(); i++) {
                    final var expectedType = TypeSubstitution.substitute(
                        constructorTypes.get(i), substitutions);
                    ensureAssignable(context, expectedType,
                        resolveArgument(context, call.arguments.get(i), expectedType), call.name);
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
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        call.name, "Unknown named constructor."));
                }
                if (!namedConstructor.isPublic()
                    && !Objects.equals(context.frame.currentClassName, declaration.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                        call.name, "Named constructor is private."));
                }
                final var instantiatedFactory = (FunctionDescriptor) TypeSubstitution.substitute(
                    namedConstructor.typeDescriptor(), substitutions);
                if (instantiatedFactory.arity() != call.arguments.size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        call.name,
                    "Expected " + instantiatedFactory.arity() + " arguments, found "
                        + call.arguments.size() + "."));
                }
                for (int i = 0; i < call.arguments.size(); i++) {
                final var expectedType = instantiatedFactory.parameters().get(i);
                ensureAssignable(context, expectedType,
                    resolveArgument(context, call.arguments.get(i), expectedType), call.name);
                }
                call.setResolvedDescriptor(instantiatedFactory);
                call.setType(instantiatedFactory.returnType());
                return instantiatedFactory.returnType();
        }

        final var receiverType = alreadyResolvedReceiverType == null
                ? ExpressionFlowResolver.resolveExpression(context, call.receiver)
                : alreadyResolvedReceiverType;
        if (receiverType instanceof TypeParameterDescriptor parameter) {
            return resolveBoundedMemberCall(context, call, parameter);
        }
        final var ownerName = className(context, receiverType);
        final var owner = context.classes.get(ownerName);
        final var classMethod = owner == null ? null : owner.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .findFirst()
                .orElse(null);
        final var contract = context.contracts.get(ownerName);
        final var contractMethod = contract == null ? null : contract.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .findFirst()
                .orElse(null);
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
                    final var descriptor = defaultMethod.instantiatedType();
                    if (defaultMethod.method().isMutating()
                            && !(receiverType instanceof ReferenceDescriptor)) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                                call.name,
                                "Mutating method requires a mutable reference."));
                    }
                    call.setResolvedOwnerName(defaultMethod.ownerName());
                    call.setReceiverRequiresCast(true);
                    if (descriptor.isGeneric()) return resolveGenericMemberCall(context, call, descriptor);
                    if (!call.explicitTypeArguments.isEmpty()) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                                call.name,
                                "This method does not declare type parameters."));
                    }
                    call.setResolvedDescriptor(descriptor);
                    if (descriptor.arity() != call.arguments.size()) {
                        Zeron.resolutionError(new ResolutionError(
                                DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH, call.name,
                                "Expected " + descriptor.arity() + " arguments, found "
                                        + call.arguments.size() + "."));
                    }
                    for (int i = 0; i < call.arguments.size(); i++) {
                        ensureAssignable(context, descriptor.parameters().get(i),
                                resolveArgument(context, call.arguments.get(i), descriptor.parameters().get(i)),
                                call.name);
                    }
                    call.setType(descriptor.returnType());
                    return descriptor.returnType();
                }
            }
            final var javaClass = context.javaClassPath.find(ownerName);
            if (javaClass != null) return resolveJavaInstanceCall(context, call, receiverType, javaClass);
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    call.name, "Unknown method: '" + call.name.lexeme() + "'"));
        }

        final var descriptor = classMethod != null
                ? classMethod.typeDescriptor()
                : contractMethod.typeDescriptor();
        final var typeParameters = classMethod != null
            ? owner.typeParameters()
            : contract.typeParameters();
        final var instantiatedDescriptor = (FunctionDescriptor) TypeSubstitution.substitute(
            descriptor, TypeResolver.substitutionsFor(context, typeParameters, receiverType));
        final var isMutating = classMethod != null
                ? classMethod.isMutating()
                : contractMethod.isMutating();
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
            return resolveGenericMemberCall(context, call, instantiatedDescriptor);
        }
        if (!call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                    call.name,
                    "This method does not declare type parameters."));
        }
        call.setResolvedDescriptor(instantiatedDescriptor);
        if (instantiatedDescriptor.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name,
                    "Expected " + instantiatedDescriptor.arity() + " arguments, found "
                            + call.arguments.size() + "."));
        }
        for (int i = 0; i < call.arguments.size(); i++) {
            ensureAssignable(context, instantiatedDescriptor.parameters().get(i),
                    resolveArgument(context, call.arguments.get(i), instantiatedDescriptor.parameters().get(i)),
                    call.name);
        }
        call.setType(instantiatedDescriptor.returnType());
        return instantiatedDescriptor.returnType();
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

    private static TypeDescriptor resolveGenericMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                                    final FunctionDescriptor genericType) {
        final var typeParameters = genericType.typeParameters();
        if (!call.explicitTypeArguments.isEmpty()
                && call.explicitTypeArguments.size() != typeParameters.size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name,
                    "Expected " + typeParameters.size() + " type arguments, found "
                            + call.explicitTypeArguments.size() + "."));
        }
        if (genericType.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name,
                    "Expected " + genericType.arity() + " arguments, found " + call.arguments.size() + "."));
        }

        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
            final var explicitType = call.explicitTypeArguments.get(i);
            TypeResolver.validateType(context, explicitType, call.name);
            substitutions.put(typeParameters.get(i), explicitType);
        }

        final var resolvedArguments = new TypeDescriptor[call.arguments.size()];
        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (argument instanceof Expr.Lambda || LambdaResolver.isFunctionReferenceCandidate(context, argument)) continue;
            resolvedArguments[i] = ExpressionFlowResolver.resolveExpression(context, argument);
            TypeUnifier.unify(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.name);
        }

        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (!(argument instanceof Expr.Lambda) && !LambdaResolver.isFunctionReferenceCandidate(context, argument)) continue;
            final var expected = TypeSubstitution.substitute(genericType.parameters().get(i), substitutions);
            if (LambdaResolver.functionType(context, expected) == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.name,
                        "A function value argument requires a function parameter type."));
            }
            resolvedArguments[i] = resolveArgument(context, argument, expected);
            TypeUnifier.unify(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.name);
        }

        for (final var parameter : typeParameters) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.name,
                        "Cannot infer type parameter '" + parameter.name()
                                + "'; provide an explicit type argument."));
            }
            for (final var bound : parameter.bounds()) {
                final var requiredBound = TypeSubstitution.substitute(bound, substitutions);
                ensureAssignable(context, requiredBound, substitutions.get(parameter), call.name);
            }
        }

        final var instantiatedParameters = genericType.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                .toList();
        for (int i = 0; i < resolvedArguments.length; i++) {
            ensureAssignable(context, instantiatedParameters.get(i), resolvedArguments[i], call.name);
        }
        final var instantiatedReturn = TypeSubstitution.substitute(genericType.returnType(), substitutions);
        call.setResolvedDescriptor(TypeDescriptor.functionOf(genericType.name(), instantiatedReturn,
                instantiatedParameters.toArray(TypeDescriptor[]::new)));
        call.setType(instantiatedReturn);
        return instantiatedReturn;
    }

    private static TypeDescriptor resolveJavaTypeCall(final ResolutionContext context, final Expr.MemberCall call,
                                               final JavaClassPath.JavaClass javaClass) {
        if (!call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    call.name,
                    "Java generic type arguments are not supported by the current interop slice."));
        }
        final var constructor = call.name.lexeme().equals("new");
        if (constructor && (javaClass.isInterface() || javaClass.isAbstract())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    call.name,
                    "Cannot construct an abstract Java class or interface."));
        }
        final var methodName = constructor ? "<init>" : call.name.lexeme();
        return resolveJavaCall(context, call, javaClass, methodName, constructor, !constructor);
    }

    private static TypeDescriptor resolveJavaInstanceCall(final ResolutionContext context, final Expr.MemberCall call,
                                                   final TypeDescriptor receiverType,
                                                   final JavaClassPath.JavaClass javaClass) {
        if (!(receiverType instanceof ReferenceDescriptor)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    call.name,
                    "Java instance method calls require a mutable & receiver."));
        }
        if (!call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    call.name,
                    "Java generic method type arguments are not supported by the current interop slice."));
        }
        return resolveJavaCall(context, call, javaClass, call.name.lexeme(), false, false);
    }

    private static TypeDescriptor resolveJavaCall(final ResolutionContext context, final Expr.MemberCall call,
                                           final JavaClassPath.JavaClass javaClass,
                                           final String methodName,
                                           final boolean constructor,
                                           final boolean staticCall) {
        if (call.arguments.stream().anyMatch(Expr.Lambda.class::isInstance)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    call.name,
                    "Java functional-interface callback conversion is not supported by this interop slice."));
        }
        final var actualTypes = call.arguments.stream().map(expression -> ExpressionFlowResolver.resolveExpression(context, expression)).toList();
        final var namedCandidates = javaClass.methods().stream()
                .filter(method -> method.name().equals(methodName))
                .filter(method -> method.isConstructor() == constructor)
                .filter(method -> constructor || method.isStatic() == staticCall)
                .toList();
        final var candidates = new ArrayList<JavaCandidate>();
        var hasUnsupportedShape = false;
        for (final var method : namedCandidates) {
            if (method.hasGenericSignature()) {
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
            final var parameterTypes = new ArrayList<TypeDescriptor>();
            var cost = 0;
            var applicable = true;
            for (int i = 0; i < actualTypes.size(); i++) {
                final var javaParameterType = method.isVarArgs() && i >= fixedCount
                        ? method.descriptor().parameterType(formalCount - 1).componentType()
                        : method.descriptor().parameterType(i);
                final var parameterType = JavaTypeMapping.toZeronType(javaParameterType);
                if (parameterType == null || !context.typeCompatibility.canAssign(parameterType, actualTypes.get(i))) {
                    applicable = false;
                    break;
                }
                parameterTypes.add(parameterType);
                cost += javaConversionCost(context, parameterType, actualTypes.get(i));
            }
            if (!applicable) continue;

            final var returnType = constructor
                    ? new ReferenceDescriptor(TypeDescriptor.ofName(javaClass.binaryName()))
                    : JavaTypeMapping.toZeronType(method.descriptor().returnType());
            if (returnType == null) {
                hasUnsupportedShape = true;
                continue;
            }
            if (!constructor) TypeResolver.validateType(context, returnType, call.name);
                candidates.add(new JavaCandidate(method, List.copyOf(parameterTypes), returnType, cost,
                    method.isVarArgs()));
        }

        if (candidates.isEmpty()) {
            final var reason = hasUnsupportedShape
                    ? "Matching Java generic, varargs, or unsupported type signatures are not supported."
                    : "No applicable public Java overload was found.";
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    call.name, reason));
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
            ensureAssignable(context, selected.parameterTypes().get(i), actualTypes.get(i), call.name);
        }
        final var invocationKind = constructor
                ? JavaCallTarget.InvocationKind.CONSTRUCTOR
                : staticCall ? JavaCallTarget.InvocationKind.STATIC
                : javaClass.isInterface() ? JavaCallTarget.InvocationKind.INTERFACE
                : JavaCallTarget.InvocationKind.VIRTUAL;
        call.setJavaCallTarget(new JavaCallTarget(javaClass.binaryName(), methodName,
            selected.method().descriptor().descriptorString(), invocationKind, selected.varArgs()));
        call.setResolvedDescriptor(TypeDescriptor.functionOf(methodName, selected.returnType(),
                selected.parameterTypes().toArray(TypeDescriptor[]::new)));
        call.setType(selected.returnType());
        return selected.returnType();
    }

    private static int javaConversionCost(final ResolutionContext context, final TypeDescriptor expected, final TypeDescriptor actual) {
        if (expected.equals(actual)) return 0;
        if (expected instanceof NullableDescriptor nullable && nullable.baseType().equals(actual)) return 1;
        return 2;
    }

    private static TypeDescriptor resolveBoundedMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                                    final TypeParameterDescriptor parameter) {
        if (parameter.bounds().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "Member access on an unconstrained type parameter is not allowed."));
        }
        TypeDescriptor bound = null;
        Stmt.ContractDecl contract = null;
        Stmt.ContractMethod method = null;
        var hasMutatingMethod = false;
        for (final var candidateBound : parameter.bounds()) {
            final var candidateName = className(context, candidateBound);
            final var candidateContract = context.contracts.get(candidateName);
            final var candidateMethod = candidateContract == null ? null : candidateContract.methods().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(call.name.lexeme()))
                    .findFirst().orElse(null);
            if (candidateMethod != null) {
                if (candidateMethod.isMutating()) {
                    hasMutatingMethod = true;
                    continue;
                }
                bound = candidateBound;
                contract = candidateContract;
                method = candidateMethod;
                break;
            }
        }
        if (method == null) {
            if (hasMutatingMethod) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED, call.name,
                        "Mutating methods are not available through a generic contract bound."));
            }
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "Method is not provided by any of the type parameter's contract bounds."));
        }
        final var ownerName = className(context, bound);

        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        final var boundBase = bound instanceof ReferenceDescriptor reference ? reference.baseType() : bound;
        if (boundBase instanceof GenericDescriptor genericBound) {
            if (genericBound.typeParameters().size() != contract.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.name,
                        "Invalid generic contract bound '" + bound + "'."));
            }
            for (int index = 0; index < contract.typeParameters().size(); index++) {
                substitutions.put(contract.typeParameters().get(index), genericBound.typeParameters().get(index));
            }
        }
        final var descriptor = (FunctionDescriptor) TypeSubstitution.substitute(
                method.typeDescriptor(), substitutions);
        if (descriptor.isGeneric()) {
            final var result = resolveGenericMemberCall(context, call, descriptor);
            call.setResolvedOwnerName(ownerName);
            call.setReceiverRequiresCast(true);
            return result;
        }
        if (descriptor.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name,
                    "Expected " + descriptor.arity() + " arguments, found " + call.arguments.size() + "."));
        }
        for (int index = 0; index < call.arguments.size(); index++) {
            final var expected = descriptor.parameters().get(index);
            ensureAssignable(context, expected, resolveArgument(context, call.arguments.get(index), expected), call.name);
        }
        call.setResolvedOwnerName(ownerName);
        call.setReceiverRequiresCast(true);
        call.setResolvedDescriptor(descriptor);
        call.setType(descriptor.returnType());
        return descriptor.returnType();
    }

    private static String resolveClassName(final ResolutionContext context, final String name, final Token where) {
        if (context.classes.containsKey(name)) return name;
        final var importedName = context.currentImports.types().get(name);
        if (importedName != null && (context.classes.containsKey(importedName) || context.javaClassPath.find(importedName) != null)) {
            return importedName;
        }
        final var qualifiedName = context.packageName.isEmpty() ? name : context.packageName + "." + name;
        if (context.classes.containsKey(qualifiedName) || context.javaClassPath.find(qualifiedName) != null) return qualifiedName;
        final var starCandidates = starTypeCandidates(context, name);
        if (starCandidates.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT, where,
                    "Ambiguous type '" + name + "' from star imports; add an explicit import or alias."));
        }
        if (starCandidates.size() == 1) return starCandidates.getFirst();
        return context.javaClassPath.find(name) != null ? name : null;
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
            if (context.functions.containsKey(namespaceName)) return namespaceName;
        }
        final var localName = qualify(context.packageName, name);
        if (context.functions.containsKey(localName)) return localName;
        final var explicitImport = context.currentImports.functions().get(name);
        if (explicitImport != null) return explicitImport;
        final var candidates = context.currentImports.onDemandPackages().stream()
                .filter(importedPackage -> !importedPackage.equals(context.packageName))
                .map(importedPackage -> qualify(importedPackage, name))
                .filter(context.functions::containsKey)
                .filter(functionName -> ((Stmt.FunctionDeclaration) context.functions.get(functionName)).isPublic())
                .distinct()
                .toList();
        if (candidates.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT, where,
                    "Ambiguous function '" + name + "' from star imports; add an explicit import or alias."));
        }
        return candidates.isEmpty() ? null : candidates.getFirst();
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
                .distinct()
                .toList();
        if (candidates.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, name,
                    "Ambiguous value '" + name.lexeme()
                            + "' from star imports; add an explicit import or alias."));
        }
        return candidates.isEmpty() ? null : context.topLevelValueSymbols.get(candidates.getFirst());
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
        if (argument instanceof Expr.ArrayLiteral literal && literal.elements.isEmpty()) {
            return ExpressionFlowResolver.resolveEmptyArrayLiteral(context, literal, expectedType);
        }
        if (argument instanceof Expr.Variable variable && context.symbols.containsSymbol(variable.name)) {
            var bindingType = context.symbols.getSymbol(variable.name).type();
            if (bindingType instanceof ReferenceDescriptor reference) bindingType = reference.baseType();
            if (bindingType instanceof FunctionDescriptor scheme && scheme.isGeneric()) {
                final var expectedFunction = LambdaResolver.functionType(context, expectedType);
                if (expectedFunction != null) {
                    final var specialized = LambdaResolver.instantiateLambdaScheme(context, scheme, expectedFunction, variable.name);
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
                    .map(property -> new PropertyInfo(property.type(), property.isPublic(),
                            property.isMutating()))
                    .orElse(null);
        }
        final var contract = context.contracts.get(ownerName);
        if (contract == null) return null;
        return contract.properties().stream()
                .filter(property -> property.name().lexeme().equals(name.lexeme()))
                .findFirst()
                .map(property -> new PropertyInfo(property.type(), true, property.isMutating()))
                .orElse(null);
    }

    static TypeDescriptor resolvedPropertyType(final ResolutionContext context, final String ownerName,
                                                final PropertyInfo property,
                                                final TypeDescriptor receiverType) {
        final var owner = context.classes.get(ownerName);
        final var parameters = owner != null
                ? owner.typeParameters()
                : context.contracts.get(ownerName).typeParameters();
        return TypeSubstitution.substitute(property.type(), TypeResolver.substitutionsFor(context, parameters, receiverType));
    }

    static void ensureFieldAccessible(final ResolutionContext context, final Token where, final String owner) {
        if (!Objects.equals(context.frame.currentClassName, owner)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                    where, "Field is private."));
        }
    }

}
