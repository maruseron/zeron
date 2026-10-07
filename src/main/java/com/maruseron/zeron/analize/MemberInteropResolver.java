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
        if (owner != null) {
            final var field = owner.fields().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(property.name.lexeme()))
                    .findFirst()
                    .orElse(null);
            if (field != null) {
                ensureFieldAccessible(context, property.name, ownerName);
                final var fieldType = TypeSubstitution.substitute(field.type(),
                        TypeResolver.substitutionsFor(context, owner.typeParameters(), memberReceiverType));
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
        if (owner == null) Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                property.name, "Unknown property."));
        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                property.name, "Unknown field."));
        throw new IllegalStateException("Unreachable");
    }

    static TypeDescriptor resolveMemberCall(final ResolutionContext context, final Expr.MemberCall call) {
        return resolveMemberCall(context, call, null);
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

    private static TypeDescriptor resolveMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                             final TypeDescriptor alreadyResolvedReceiverType,
                                             final TypeDescriptor expectedType) {
        Zeron.debug("resolving member call       " + call.name.lexeme() + " for " + call.receiver);
        final var classOwnerName = call.receiver instanceof Expr.Variable typeName
                ? resolveClassName(context, typeName.name.lexeme(), typeName.name)
                : null;
        final var classDeclaration = classOwnerName == null ? null : context.classes.get(classOwnerName);
        final var classFactory = classDeclaration == null ? null
                : findClassFactory(classDeclaration, call.name.lexeme());
        final var namespaceFunctionName = !call.safeNavigation()
                ? namespaceMemberName(context, call.receiver, call.name.lexeme())
                : null;
        final var namespaceFunction = namespaceFunctionName == null
                ? null : context.functions.get(namespaceFunctionName);
        if (classFactory != null) {
            List<TypeDescriptor> priorArgumentTypes = null;
            if (namespaceFunction != null && !(namespaceFunction instanceof Stmt.ExtensionMethod)) {
                priorArgumentTypes = resolvePotentialCallArgumentTypes(context, call);
                final var namespaceApplicable =
                        namespaceCallIsApplicable(context, call, namespaceFunctionName, priorArgumentTypes);
                final var classApplicable = classFactoryIsApplicable(
                        context, call, classDeclaration, classFactory, expectedType, priorArgumentTypes);
                if (namespaceApplicable && classApplicable) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                            call.name, "Ambiguous call: both a class constructor and namespace function match."));
                }
                if (namespaceApplicable && !classApplicable) {
                    if (!isNamespaceMemberAccessible(
                            context, namespaceFunctionName, namespaceFunction.isPublic())) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                                call.name, "Namespace function '" + namespaceFunctionName + "' is private."));
                    }
                    return context.callResolver.resolveNamespaceCall(call, namespaceFunctionName);
                }
            }
            return resolveClassFactoryCall(context, call, classDeclaration, classOwnerName,
                    classFactory, expectedType, priorArgumentTypes);
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
                if (javaClass != null) return resolveJavaTypeCall(context, call, javaClass);
            }
            if (declaration != null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                        call.name, "Unknown named constructor."));
            }
        }

        final var receiverType = alreadyResolvedReceiverType == null
                ? ExpressionFlowResolver.resolveExpression(context, call.receiver)
                : alreadyResolvedReceiverType;
        if (receiverType instanceof TypeParameterDescriptor parameter) {
            return resolveBoundedMemberCall(context, call, parameter);
        }
        final var ownerName = className(context, receiverType);
        final var owner = context.classes.get(ownerName);
        final var classMethods = owner == null ? List.<Stmt.Method>of() : owner.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .filter(method -> method.isPublic() || Objects.equals(context.frame.currentClassName, ownerName))
                .filter(method -> !method.isMutating() || receiverType instanceof ReferenceDescriptor).toList();
        final var contract = context.contracts.get(ownerName);
        final var contractMethods = contract == null ? List.<Stmt.ContractMethod>of() : contract.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .filter(method -> !method.isMutating() || receiverType instanceof ReferenceDescriptor).toList();
        final var sourceCandidates = new ArrayList<SourceMethodCandidate>();
        if (!classMethods.isEmpty()) {
            final var substitutions = TypeResolver.substitutionsFor(context, owner.typeParameters(), receiverType);
            for (final var method : classMethods) {
                sourceCandidates.add(new SourceMethodCandidate(method, null,
                        (FunctionDescriptor) TypeSubstitution.substitute(method.typeDescriptor(), substitutions),
                        method.minimumArity(), method.variadic(), method.isMutating()));
            }
        } else if (!contractMethods.isEmpty()) {
            final var substitutions = TypeResolver.substitutionsFor(context, contract.typeParameters(), receiverType);
            for (final var method : contractMethods) {
                sourceCandidates.add(new SourceMethodCandidate(null, method,
                        (FunctionDescriptor) TypeSubstitution.substitute(method.typeDescriptor(), substitutions),
                        method.minimumArity(), method.variadic(), method.isMutating()));
            }
        }
        final var selectedSource = sourceCandidates.isEmpty() ? null : selectSourceMethod(context, call, sourceCandidates);
        final var classMethod = selectedSource == null ? null : selectedSource.classMethod();
        final var contractMethod = selectedSource == null ? null : selectedSource.contractMethod();
        call.setResolvedSourceMethod(classMethod);
        call.setResolvedContractMethod(contractMethod);
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
                final var descriptor = defaultMethod.instantiatedType();
                if (defaultMethod.method().isMutating()
                        && !(receiverType instanceof ReferenceDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                            call.name,
                            "Mutating method requires a mutable reference."));
                }
                call.setResolvedOwnerName(defaultMethod.ownerName());
                call.setResolvedContractMethod(defaultMethod.method());
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
                call.setType(descriptor.returnType());
                return descriptor.returnType();
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
                    final var descriptor = defaultMethod.instantiatedType();
                    if (defaultMethod.method().isMutating()
                            && !(receiverType instanceof ReferenceDescriptor)) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED,
                                call.name,
                                "Mutating method requires a mutable reference."));
                    }
                    call.setResolvedOwnerName(defaultMethod.ownerName());
                    call.setResolvedContractMethod(defaultMethod.method());
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
                    call.setType(descriptor.returnType());
                    return descriptor.returnType();
                }
            }
            final var extensionResult = resolveImportedExtension(context, call, receiverType, false);
            if (extensionResult != null) return extensionResult;
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
        final var minimumArity = classMethod != null
                ? classMethod.minimumArity()
                : contractMethod.minimumArity();
        final var variadic = classMethod != null
                ? classMethod.variadic()
                : contractMethod.variadic();
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
        call.setType(instantiatedDescriptor.returnType());
        return instantiatedDescriptor.returnType();
    }

    private record SourceMethodCandidate(Stmt.Method classMethod, Stmt.ContractMethod contractMethod,
                                         FunctionDescriptor descriptor, int minimumArity,
                                         boolean variadic, boolean mutating) {}

    private static SourceMethodCandidate selectSourceMethod(final ResolutionContext context,
                                                            final Expr.MemberCall call,
                                                            final List<SourceMethodCandidate> candidates) {
        final var actualTypes = call.arguments.stream().map(argument ->
                argument instanceof Expr.Lambda || LambdaResolver.isFunctionReferenceCandidate(context, argument)
                        ? null : ExpressionFlowResolver.resolveExpression(context, argument)).toList();
        final var applicable = new ArrayList<SourceMethodCandidate>();
        for (final var candidate : candidates) {
            final var signature = candidate.descriptor();
            final var parameters = candidate.classMethod() != null
                    ? candidate.classMethod().parameters() : candidate.contractMethod().parameters();
            final var fixedArity = Stmt.fixedArity(parameters, candidate.variadic());
            if ((!call.explicitTypeArguments.isEmpty()
                    && call.explicitTypeArguments.size() != signature.typeParameters().size())
                    || call.arguments.size() < candidate.minimumArity()
                    || !candidate.variadic() && call.arguments.size() > signature.arity()) continue;
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            try {
                for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
                    TypeResolver.validateType(context, call.explicitTypeArguments.get(i), call.name);
                    substitutions.put(signature.typeParameters().get(i), call.explicitTypeArguments.get(i));
                }
                for (int i = 0; i < actualTypes.size(); i++) {
                    if (actualTypes.get(i) != null) {
                        final var pattern = memberParameterType(signature.parameters(), i, fixedArity,
                                candidate.variadic());
                        if (TypeSubstitution.containsTypeParameter(pattern)) {
                            TypeUnifier.unify(pattern, actualTypes.get(i), substitutions, call.name);
                        }
                    }
                }
            } catch (final ResolutionError _) {
                continue;
            }
            final var hasUninferredTypeParameters = signature.typeParameters().stream()
                    .anyMatch(parameter -> !substitutions.containsKey(parameter));
            var hasLambdaForInference = false;
            for (int i = 0; i < actualTypes.size(); i++) {
                if (actualTypes.get(i) == null
                        && memberParameterType(signature.parameters(), i, fixedArity, candidate.variadic())
                                instanceof FunctionDescriptor) {
                    hasLambdaForInference = true;
                    break;
                }
            }
            if (hasUninferredTypeParameters && !hasLambdaForInference) {
                continue;
            }
            final var instantiatedParameters = signature.parameters().stream()
                    .map(parameter -> TypeSubstitution.substitute(parameter, substitutions)).toList();
            var matches = true;
            for (int i = 0; i < actualTypes.size(); i++) {
                final var expected = memberParameterType(instantiatedParameters, i, fixedArity, candidate.variadic());
                if (actualTypes.get(i) != null && !context.typeCompatibility.canAssign(expected, actualTypes.get(i))
                        || actualTypes.get(i) == null && !(expected instanceof FunctionDescriptor)) {
                    matches = false;
                    break;
                }
            }
            if (matches) applicable.add(candidate);
        }
        if (applicable.isEmpty()) {
            return null;
        }
        var best = applicable.stream().filter(candidate -> applicable.stream().noneMatch(other ->
                other != candidate && sourceMethodMoreSpecific(context, other, candidate))).toList();
        if (best.size() > 1) {
            final var minimumOmitted = best.stream().mapToInt(candidate ->
                    Math.max(0, Stmt.fixedArity(candidate.classMethod() != null
                                    ? candidate.classMethod().parameters() : candidate.contractMethod().parameters(),
                            candidate.variadic()) - call.arguments.size())).min().orElse(0);
            best = best.stream().filter(candidate -> Math.max(0, Stmt.fixedArity(
                    candidate.classMethod() != null
                            ? candidate.classMethod().parameters() : candidate.contractMethod().parameters(),
                    candidate.variadic()) - call.arguments.size()) == minimumOmitted).toList();
            if (best.stream().anyMatch(SourceMethodCandidate::variadic)
                    && best.stream().anyMatch(candidate -> !candidate.variadic())) {
                best = best.stream().filter(candidate -> !candidate.variadic()).toList();
            }
            if (best.stream().anyMatch(candidate -> !candidate.descriptor().isGeneric())
                    && best.stream().anyMatch(candidate -> candidate.descriptor().isGeneric())) {
                best = best.stream().filter(candidate -> !candidate.descriptor().isGeneric()).toList();
            }
        }
        if (best.size() != 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "Ambiguous call to method '" + call.name.lexeme() + "'."));
        }
        return best.getFirst();
    }

    private record ExtensionCandidate(Stmt.ExtensionMethod method,
                                      FunctionDescriptor descriptor,
                                      List<TypeDescriptor> comparisonTypes,
                                      int omittedDefaults,
                                      boolean variadic,
                                      TypeDescriptor receiverMatchType) {}

    private static TypeDescriptor resolveImportedExtension(final ResolutionContext context,
                                                            final Expr.MemberCall call,
                                                            final TypeDescriptor receiverType,
                                                            final boolean propertyAccess) {
        final var extensionName = context.currentImports.extensions().get(call.name.lexeme());
        final var explicitDeclarations = extensionName == null
                ? List.<Stmt.ExtensionMethod>of()
                : extensionDeclarations(context, List.of(extensionName));
        final var starDeclarations = starExtensionDeclarations(context, call.name.lexeme());
        final var matchingExplicitDeclarations = explicitDeclarations.stream()
                .filter(method -> method.property() == propertyAccess).toList();
        final var matchingStarDeclarations = starDeclarations.stream()
                .filter(method -> method.property() == propertyAccess).toList();
        if (matchingExplicitDeclarations.isEmpty() && matchingStarDeclarations.isEmpty()) return null;
        final var mutableReceiver = receiverType instanceof ReferenceDescriptor;
        var receiverBase = receiverType instanceof ReferenceDescriptor reference
                ? reference.baseType() : receiverType;
        final var normalizedReceiverBase = receiverBase;
        final var actualArguments = call.arguments.stream().map(argument ->
                argument instanceof Expr.Lambda || LambdaResolver.isFunctionReferenceCandidate(context, argument)
                        ? null : ExpressionFlowResolver.resolveExpression(context, argument)).toList();
        var applicable = findApplicableExtensions(
                context, call, receiverType, receiverBase, mutableReceiver, actualArguments,
                matchingExplicitDeclarations);
        var consideredDeclarations = matchingExplicitDeclarations;
        if (applicable.isEmpty() && !matchingStarDeclarations.isEmpty()) {
            applicable = findApplicableExtensions(
                    context, call, receiverType, receiverBase, mutableReceiver, actualArguments,
                    matchingStarDeclarations);
            consideredDeclarations = matchingStarDeclarations;
        }
        if (applicable.isEmpty() && consideredDeclarations.isEmpty()) return null;
        if (applicable.isEmpty()) {
            if (!mutableReceiver && consideredDeclarations.stream().anyMatch(method -> method.isMutating()
                    && context.typeCompatibility.canAssign(method.receiverType(), normalizedReceiverBase))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.MUTATION_NOT_PERMITTED, call.name,
                        "Mutating extension method requires a mutable receiver."));
            }
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name, "No imported extension overload of '" + call.name.lexeme()
                            + "' matches this receiver and these arguments."));
        }
        final var selectedExtensions = applicable;
        var best = selectedExtensions.stream().filter(candidate -> selectedExtensions.stream().noneMatch(other ->
                other != candidate && extensionMoreSpecific(context, other, candidate))).toList();
        if (best.size() > 1) {
            final var minOmitted = best.stream().mapToInt(ExtensionCandidate::omittedDefaults).min().orElse(0);
            best = best.stream().filter(candidate -> candidate.omittedDefaults() == minOmitted).toList();
            if (best.stream().anyMatch(ExtensionCandidate::variadic)
                    && best.stream().anyMatch(candidate -> !candidate.variadic())) {
                best = best.stream().filter(candidate -> !candidate.variadic()).toList();
            }
            if (best.stream().anyMatch(candidate -> !candidate.descriptor().isGeneric())
                    && best.stream().anyMatch(candidate -> candidate.descriptor().isGeneric())) {
                best = best.stream().filter(candidate -> !candidate.descriptor().isGeneric()).toList();
            }
        }
        if (best.size() != 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, call.name,
                    "Ambiguous imported extension call '" + call.name.lexeme() + "'."));
        }
        final var selected = best.getFirst();
        final var method = selected.method();
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        TypeUnifier.unify(method.receiverType(), selected.receiverMatchType(), substitutions, call.name);
        for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
            final var explicitType = call.explicitTypeArguments.get(i);
            TypeResolver.validateType(context, explicitType, call.name);
            substitutions.put(method.methodTypeParameters().get(i), explicitType);
        }
        final var resolvedArguments = new TypeDescriptor[call.arguments.size()];
        final var signature = method.typeDescriptor();
        final var fixedArity = method.fixedCallArity();
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
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.name, "Cannot infer type parameter '" + parameter.name()
                                + "' for imported extension method '" + call.name.lexeme()
                                + "'; provide an explicit type argument."));
            }
            for (final var bound : parameter.bounds()) {
                ensureAssignable(context, TypeSubstitution.substitute(bound, substitutions),
                        substitutions.get(parameter), call.name);
            }
        }
        final var instantiatedDescriptor = (FunctionDescriptor) TypeSubstitution.substitute(signature, substitutions);
        call.setResolvedExtensionMethod(method);
        call.setResolvedDescriptor(instantiatedDescriptor);
        if (selected.variadic()) {
            call.setVariadic(((ArrayDescriptor) instantiatedDescriptor.parameters().getLast())
                    .elementType(), fixedArity);
        }
        for (int i = 0; i < call.arguments.size(); i++) {
            final var expected = extensionArgumentType(instantiatedDescriptor.parameters(), i,
                    fixedArity, selected.variadic());
            ensureAssignable(context, expected, resolvedArguments[i], call.name);
        }
        call.setType(instantiatedDescriptor.returnType());
        return instantiatedDescriptor.returnType();
    }

    private static List<Stmt.ExtensionMethod> extensionDeclarations(
            final ResolutionContext context, final List<String> qualifiedNames) {
        return qualifiedNames.stream()
                .flatMap(name -> context.functionOverloads.getOrDefault(name, List.of()).stream())
                .filter(Stmt.ExtensionMethod.class::isInstance)
                .map(Stmt.ExtensionMethod.class::cast)
                .toList();
    }

    private static List<Stmt.ExtensionMethod> starExtensionDeclarations(
            final ResolutionContext context, final String methodName) {
        return context.functionOverloads.values().stream()
                .flatMap(Collection::stream)
                .filter(Stmt.ExtensionMethod.class::isInstance)
                .map(Stmt.ExtensionMethod.class::cast)
                .filter(method -> method.name().lexeme().equals(methodName))
                .filter(method -> context.currentImports.onDemandPackages().contains(
                        context.namespaceMemberPackages.get(context.functionNamesByDeclaration.get(method.name()))))
                .filter(method -> method.isPublic() || Objects.equals(
                        context.namespaceMemberPackages.get(context.functionNamesByDeclaration.get(method.name())),
                        context.packageName))
                .distinct()
                .toList();
    }

    private static ArrayList<ExtensionCandidate> findApplicableExtensions(
            final ResolutionContext context, final Expr.MemberCall call, final TypeDescriptor receiverType,
            final TypeDescriptor receiverBase,
            final boolean mutableReceiver,
            final List<TypeDescriptor> actualArguments, final List<Stmt.ExtensionMethod> extensionDeclarations) {
        final var applicable = new ArrayList<ExtensionCandidate>();
        final var receiverProjections = extensionReceiverProjections(context, receiverBase);
        for (final var method : extensionDeclarations) {
            if (method.isMutating() && !mutableReceiver) continue;
            final var signature = method.typeDescriptor();
            final var variadic = method.variadic();
            final var fixedArity = method.fixedCallArity();
            if ((!call.explicitTypeArguments.isEmpty()
                    && call.explicitTypeArguments.size() != method.methodTypeParameters().size())
                    || call.arguments.size() < method.minimumCallArity()
                    || !variadic && call.arguments.size() > fixedArity) continue;
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            TypeDescriptor receiverMatchType = null;
            try {
                for (final var projection : receiverProjections) {
                    try {
                        TypeUnifier.unify(method.receiverType(), projection,
                                new LinkedHashMap<>(substitutions), call.name);
                        receiverMatchType = projection;
                        break;
                    } catch (final ResolutionError _) {
                        // Try the next receiver projection.
                    }
                }
                if (receiverMatchType == null) continue;
                TypeUnifier.unify(method.receiverType(), receiverMatchType, substitutions, call.name);
                for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
                    final var explicitType = call.explicitTypeArguments.get(i);
                    TypeResolver.validateType(context, explicitType, call.name);
                    substitutions.put(method.methodTypeParameters().get(i), explicitType);
                }
                for (int i = 0; i < actualArguments.size(); i++) {
                    if (actualArguments.get(i) == null) continue;
                    final var formal = extensionArgumentType(signature.parameters(), i, fixedArity, variadic);
                    if (TypeSubstitution.containsTypeParameter(formal)) {
                        TypeUnifier.unify(formal, actualArguments.get(i), substitutions, call.name);
                    }
                }
            } catch (final ResolutionError _) {
                continue;
            }
            var hasLambdaForInference = false;
            for (int i = 0; i < actualArguments.size(); i++) {
                if (actualArguments.get(i) == null
                        && extensionArgumentType(signature.parameters(), i, fixedArity, variadic)
                                instanceof FunctionDescriptor) {
                    hasLambdaForInference = true;
                    break;
                }
            }
            if (signature.typeParameters().stream().anyMatch(parameter -> !substitutions.containsKey(parameter))
                    && !hasLambdaForInference) {
                continue;
            }
            var boundsMatch = true;
            for (final var parameter : signature.typeParameters()) {
                for (final var bound : parameter.bounds()) {
                    if (!context.typeCompatibility.canAssign(
                            TypeSubstitution.substitute(bound, substitutions), substitutions.get(parameter))) {
                        boundsMatch = false;
                        break;
                    }
                }
                if (!boundsMatch) break;
            }
            if (!boundsMatch) continue;
            final var instantiated = (FunctionDescriptor) TypeSubstitution.substitute(signature, substitutions);
            final var receiverExpected = instantiated.parameters().getFirst();
            if (!context.typeCompatibility.canAssign(receiverExpected, receiverType)) continue;
            var matches = true;
            final var comparison = new ArrayList<TypeDescriptor>();
            comparison.add(TypeSubstitution.substitute(method.receiverType(), substitutions));
            for (int i = 0; i < actualArguments.size(); i++) {
                final var expected = extensionArgumentType(instantiated.parameters(), i, fixedArity, variadic);
                comparison.add(expected);
                if (actualArguments.get(i) != null
                        && !context.typeCompatibility.canAssign(expected, actualArguments.get(i))
                        || actualArguments.get(i) == null && !(expected instanceof FunctionDescriptor)) {
                    matches = false;
                    break;
                }
            }
            if (!matches) continue;
            applicable.add(new ExtensionCandidate(method, instantiated, List.copyOf(comparison),
                    Math.max(0, fixedArity - call.arguments.size()), variadic, receiverMatchType));
        }
        return applicable;
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

    private static boolean extensionMoreSpecific(final ResolutionContext context,
                                                  final ExtensionCandidate left,
                                                  final ExtensionCandidate right) {
        if (left.comparisonTypes().size() != right.comparisonTypes().size()) return false;
        var strict = false;
        for (int i = 0; i < left.comparisonTypes().size(); i++) {
            final var leftType = left.comparisonTypes().get(i);
            final var rightType = right.comparisonTypes().get(i);
            if (!context.typeCompatibility.canAssign(rightType, leftType)) return false;
            strict |= !context.typeCompatibility.canAssign(leftType, rightType);
        }
        return strict;
    }

    private static boolean sourceMethodMoreSpecific(final ResolutionContext context,
                                                    final SourceMethodCandidate left,
                                                    final SourceMethodCandidate right) {
        final var leftTypes = left.descriptor().parameters();
        final var rightTypes = right.descriptor().parameters();
        if (leftTypes.size() != rightTypes.size()) return false;
        var strict = false;
        for (int i = 0; i < leftTypes.size(); i++) {
            if (!context.typeCompatibility.canAssign(rightTypes.get(i), leftTypes.get(i))) return false;
            strict |= !context.typeCompatibility.canAssign(leftTypes.get(i), rightTypes.get(i));
        }
        return strict;
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
        if (!call.explicitTypeArguments.isEmpty()
                && typeParameters.size() != call.explicitTypeArguments.size()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    call.name, "Expected " + typeParameters.size() + " class type arguments, found "
                            + call.explicitTypeArguments.size() + "."));
        }
        for (final var explicitType : call.explicitTypeArguments) {
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
        for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
            substitutions.put(typeParameters.get(i), call.explicitTypeArguments.get(i));
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

        inferClassArgumentsFromExpectedType(declaration, substitutions, expectedType, call.name);

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
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        call.name, "Cannot infer class type parameter '" + parameter.name()
                                + "'; provide an explicit type argument or an expected type."));
            }
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
        final TypeDescriptor result;
        if (factory.canonical()) {
            result = new ReferenceDescriptor(constructedType);
        } else {
            final var instantiatedFactory = (FunctionDescriptor) TypeSubstitution.substitute(signature, substitutions);
            call.setResolvedDescriptor(instantiatedFactory);
            result = instantiatedFactory.returnType();
        }
        call.setType(result);
        return result;
    }

    private static FunctionDescriptor canonicalFactoryDescriptor(final Stmt.ClassDecl declaration) {
        final var constructorTypes = declaration.canonicalConstructorTypes();
        return TypeDescriptor.functionOf("new", new ReferenceDescriptor(classType(declaration)),
                constructorTypes.toArray(TypeDescriptor[]::new));
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

    private static List<TypeDescriptor> resolvePotentialCallArgumentTypes(
            final ResolutionContext context, final Expr.MemberCall call) {
        final var actualTypes = new ArrayList<TypeDescriptor>(call.arguments.size());
        for (final var argument : call.arguments) {
            actualTypes.add(isDeferredConstructorArgument(context, argument)
                    ? null : ExpressionFlowResolver.resolveExpression(context, argument));
        }
        return actualTypes;
    }

    private static boolean namespaceCallIsApplicable(final ResolutionContext context,
                                                      final Expr.MemberCall call,
                                                      final String functionName,
                                                      final List<TypeDescriptor> actualTypes) {
        for (final var candidate : context.functionOverloads.getOrDefault(functionName, List.of())) {
            if (candidate instanceof Stmt.ExtensionMethod
                    || !isNamespaceMemberAccessible(context, functionName, candidate.isPublic())) continue;
            final var signature = candidate.typeDescriptor();
            final var variadic = candidate.variadic();
            final var fixedArity = Stmt.fixedArity(candidate.parameters(), variadic);
            if ((!call.explicitTypeArguments.isEmpty()
                    && call.explicitTypeArguments.size() != signature.typeParameters().size())
                    || call.arguments.size() < candidate.minimumArity()
                    || !variadic && call.arguments.size() > signature.arity()) continue;
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            var applicable = true;
            try {
                for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
                    substitutions.put(signature.typeParameters().get(i), call.explicitTypeArguments.get(i));
                }
                for (int i = 0; i < actualTypes.size(); i++) {
                    if (actualTypes.get(i) == null) continue;
                    final var formal = memberParameterType(signature.parameters(), i, fixedArity, variadic);
                    if (TypeSubstitution.containsTypeParameter(formal)) {
                        TypeUnifier.unify(formal, actualTypes.get(i), substitutions, call.name);
                    }
                }
            } catch (final ResolutionError _) {
                applicable = false;
            }
            if (!applicable || signature.typeParameters().stream()
                    .anyMatch(parameter -> !substitutions.containsKey(parameter))) continue;
            final var parameters = signature.parameters().stream()
                    .map(parameter -> TypeSubstitution.substitute(parameter, substitutions)).toList();
            for (int i = 0; i < actualTypes.size(); i++) {
                final var expected = memberParameterType(parameters, i, fixedArity, variadic);
                if (actualTypes.get(i) != null && !context.typeCompatibility.canAssign(expected, actualTypes.get(i))
                        || actualTypes.get(i) == null
                                && !acceptsDeferredArrayArgument(call.arguments.get(i), expected)
                                && LambdaResolver.functionType(context, expected) == null) {
                    applicable = false;
                    break;
                }
            }
            if (applicable) return true;
        }
        return false;
    }

    private static boolean classFactoryIsApplicable(final ResolutionContext context,
                                                     final Expr.MemberCall call,
                                                     final Stmt.ClassDecl declaration,
                                                     final ClassFactory factory,
                                                     final TypeDescriptor expectedType,
                                                     final List<TypeDescriptor> actualTypes) {
        final var typeParameters = declaration.typeParameters();
        if (!call.explicitTypeArguments.isEmpty()
                && call.explicitTypeArguments.size() != typeParameters.size()
                || !classFactoryArityMatches(factory, declaration, call)) return false;
        if (factory.canonical() && !declaration.constructor().isPublic()
                && !Objects.equals(context.frame.currentClassName, declaration.name().lexeme())
                || !factory.canonical() && !factory.named().isPublic()
                && !Objects.equals(context.frame.currentClassName, declaration.name().lexeme())) return false;
        final var signature = factory.canonical()
                ? canonicalFactoryDescriptor(declaration)
                : factory.named().typeDescriptor();
        final var variadic = !factory.canonical() && factory.named().variadic();
        final var fixedArity = factory.canonical()
                ? signature.arity() : Stmt.fixedArity(factory.named().parameters(), variadic);
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        try {
            for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
                substitutions.put(typeParameters.get(i), call.explicitTypeArguments.get(i));
            }
            for (int i = 0; i < actualTypes.size(); i++) {
                final var actual = actualTypes.get(i);
                if (actual == null) continue;
                final var formal = classFactoryParameterType(signature, i, fixedArity, variadic);
                if (containsUninferredClassTypeParameter(formal, typeParameters, substitutions)) {
                    TypeUnifier.unify(formal, actual, substitutions, call.name);
                }
            }
            inferClassArgumentsFromExpectedType(declaration, substitutions, expectedType, call.name);
        } catch (final ResolutionError _) {
            return false;
        }
        final var hasDeferredFunctionArgument = call.arguments.stream().anyMatch(argument ->
                isDeferredConstructorArgument(context, argument));
        if (typeParameters.stream().anyMatch(parameter -> !substitutions.containsKey(parameter))
                && !hasDeferredFunctionArgument) return false;
        final var parameters = signature.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions)).toList();
        for (int i = 0; i < actualTypes.size(); i++) {
            final var actual = actualTypes.get(i);
            final var expected = classFactoryParameterType(parameters, i, fixedArity, variadic);
            if (actual != null && !TypeSubstitution.containsTypeParameter(expected)
                    && !context.typeCompatibility.canAssign(expected, actual)) return false;
            if (actual == null && !(call.arguments.get(i) instanceof Expr.MemberCall)
                    && !acceptsDeferredArrayArgument(call.arguments.get(i), expected)
                    && LambdaResolver.functionType(context, expected) == null) return false;
        }
        return true;
    }

    private static TypeDescriptor resolveGenericMemberCall(final ResolutionContext context, final Expr.MemberCall call,
                                                    final FunctionDescriptor genericType, final int minimumArity,
                                                    final boolean variadic, final int fixedArity) {
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
            ensureAssignable(context, memberParameterType(instantiatedParameters, i, fixedArity, variadic),
                    resolvedArguments[i], call.name);
        }
        final var instantiatedReturn = TypeSubstitution.substitute(genericType.returnType(), substitutions);
        call.setResolvedDescriptor(TypeDescriptor.functionOf(genericType.name(), instantiatedReturn,
                instantiatedParameters.toArray(TypeDescriptor[]::new)));
        if (variadic) {
            call.setVariadic(((ArrayDescriptor) instantiatedParameters.getLast()).elementType(), fixedArity);
        }
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
                .filter(functionName -> context.functionOverloads.getOrDefault(functionName, List.of()).stream()
                        .anyMatch(candidate -> !(candidate instanceof Stmt.ExtensionMethod)
                                && candidate.isPublic()))
                .distinct()
                .toList();
        if (candidates.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT, where,
                    "Ambiguous function '" + name + "' from star imports; add an explicit import or alias."));
        }
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private static boolean hasOrdinaryFunction(final ResolutionContext context, final String name) {
        return context.functionOverloads.getOrDefault(name, List.of()).stream()
                .anyMatch(candidate -> !(candidate instanceof Stmt.ExtensionMethod));
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
        final var starNamespaceMembers = context.currentImports.onDemandPackages().stream()
                .filter(importedPackage -> !importedPackage.equals(context.packageName))
                .map(importedPackage -> importedPackage + "." + path + "." + memberName)
                .filter(qualifiedName -> context.functions.containsKey(qualifiedName)
                        || context.topLevelValues.containsKey(qualifiedName))
                .filter(qualifiedName -> isNamespaceMemberAccessible(context, qualifiedName,
                        context.functions.containsKey(qualifiedName)
                                ? context.functionOverloads.getOrDefault(qualifiedName, List.of()).stream()
                                    .anyMatch(Stmt.FunctionDeclaration::isPublic)
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
        if (argument instanceof Expr.MemberCall call) {
            return resolveMemberCall(context, call, expectedType);
        }
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
