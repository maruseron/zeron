package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.ast.SyntheticNames;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.BuiltinTypes;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.GenericDescriptor;
import com.maruseron.zeron.domain.NullableDescriptor;
import com.maruseron.zeron.domain.NominalDescriptor;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeFormatter;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.domain.TypeSubstitution;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Resolves `Type::property` expressions: the owner type, the property declaration, the reader
/// closure, and the implicit conversion to the property's reader function.
final class PropertyReferenceResolver {
    private PropertyReferenceResolver() {}

    static TypeDescriptor resolve(final ResolutionContext context, final Expr.PropertyReference reference,
                                  final TypeDescriptor expectedType) {
        if (!reference.resolved()) resolveReference(context, reference, expectedType);
        if (reference.isCallableReference()) {
            final var callableType = reference.callable().getType();
            if (reference.converted()) return reference.getType();
            if (matchesExpectedFunction(context, reference, callableType, expectedType)) {
                reference.markConverted();
                reference.setType(callableType);
                return callableType;
            }
            return reference.getType();
        }
        final var readerType = reference.reader().getType();
        if (reference.converted()) return reference.getType();
        if (matchesExpectedFunction(context, reference, readerType, expectedType)) {
            reference.markConverted();
            reference.setType(readerType);
            return readerType;
        }
        return reference.getType();
    }

    /// Resolves a property-reference literal used as a `CodeModel<F>` argument. The reader lambda the
    /// reference already carries is the equivalent of the explicit selector lambda, so it becomes the
    /// model's source and is validated against the code-model expression subset.
    static TypeDescriptor resolveCodeModel(final ResolutionContext context,
                                           final Expr.PropertyReference reference,
                                           final TypeDescriptor expectedType) {
        if (!reference.resolved()) resolveReference(context, reference, expectedType);
        if (reference.isCallableReference()) return reference.getType();
        final var reader = reference.reader();
        if (reader == null || !(reader.getType() instanceof FunctionDescriptor readerType)) {
            return reference.getType();
        }
        final var expectedArgument = MemberInteropResolver.codeModelArgument(expectedType);
        if (expectedArgument instanceof FunctionDescriptor expectedFunction) {
            if (expectedFunction.arity() != readerType.arity()) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE, reference.operator,
                        "The property reader takes " + readerType.arity()
                                + " parameter(s), but the code model expects "
                                + expectedFunction.arity() + "."));
            }
            for (int index = 0; index < expectedFunction.arity()
                    && index < readerType.arity(); index++) {
                final var expectedParameter = expectedFunction.parameters().get(index);
                if (!TypeSubstitution.containsTypeParameter(expectedParameter)) {
                    Resolver.ensureAssignable(context, expectedParameter,
                            readerType.parameters().get(index), reference.operator);
                }
            }
            if (!TypeSubstitution.containsTypeParameter(expectedFunction.returnType())) {
                Resolver.ensureAssignable(context, expectedFunction.returnType(),
                        readerType.returnType(), reference.operator);
            }
        }
        final var modelType = TypeDescriptor.genericOf(
                TypeDescriptor.ofName("zeron.compiler.CodeModel"), readerType);
        reader.setCodeModelType(modelType);
        LambdaResolver.validateCodeModelLambda(context, reader,
                reader.parameterTokens(readerType.arity()));
        reference.setCodeModelType(modelType);
        reference.setType(modelType);
        return modelType;
    }

    private static void resolveReference(final ResolutionContext context,
                                         final Expr.PropertyReference reference,
                                         final TypeDescriptor expectedType) {
        final var owner = unwrapOwner(context, reference);
        requireTypeOperand(context, reference, owner);
        final var ownerName = Resolver.className(context, owner);
        final var classDeclaration = context.classes.get(ownerName);
        final var contractDeclaration = context.contracts.get(ownerName);
        final var rawStaticName = owner instanceof NominalDescriptor
                && classDeclaration != null && !classDeclaration.typeParameters().isEmpty()
                ? ownerName + "." + reference.propertyName.lexeme() : null;
        final var rawStatic = rawStaticName != null
                && context.functions.containsKey(rawStaticName)
                && !(context.functions.get(rawStaticName) instanceof Stmt.ExtensionMethod);
        if (!rawStatic) TypeResolver.validateType(context, owner, reference.operator);
        if (classDeclaration == null && contractDeclaration == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    reference.operator,
                    "The left side of '::' must name a class or contract type; found '"
                            + TypeFormatter.format(owner) + "'."));
            reference.markResolved();
            reference.setType(TypeDescriptor.ofInfer());
            return;
        }
        final var property = rawStatic ? null : MemberInteropResolver.findProperty(
                context, ownerName, reference.propertyName);
        if (property == null) {
            if (resolveCallable(context, reference, owner, ownerName, classDeclaration,
                    contractDeclaration, expectedType)) {
                return;
            }
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    reference.propertyName,
                    "Type '" + TypeFormatter.format(owner) + "' has no property or callable '"
                            + reference.propertyName.lexeme() + "'."));
            reference.markResolved();
            reference.setType(TypeDescriptor.ofInfer());
            return;
        }
        if (!reference.explicitTypeArguments().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    reference.operator, "Expected 0 callable type arguments, found "
                            + reference.explicitTypeArguments().size() + "."));
        }
        if (!property.isPublic()
                && !Objects.equals(context.frame.currentClassName, ownerName)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                    reference.propertyName, "Property '" + reference.propertyName.lexeme()
                            + "' is not accessible at this reference site."));
        } else if (classDeclaration != null && !hasReadableGetter(classDeclaration, property.name())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    reference.propertyName, "Property '" + reference.propertyName.lexeme()
                            + "' has no getter, so it cannot be referenced."));
        }

        final var propertyType = MemberInteropResolver.resolvedPropertyType(
                context, ownerName, property, owner);
        reference.setOwnerType(owner);
        reference.setDeclaringTypeName(ownerName);
        reference.setIdentity(ownerName + "#" + property.name());
        reference.setPropertyToken(property.nameToken());
        context.recordReference(reference.propertyName, property.nameToken(), propertyType);

        final var parameter = new Token(TokenType.IDENTIFIER, "$zeron$propertyRef$receiver", null,
                reference.propertyName.span());
        final var receiver = new Expr.Variable(parameter, owner);
        final var read = new Expr.Property(receiver, reference.propertyName,
                TypeDescriptor.ofInfer());
        final var reader = new Expr.Lambda(reference.operator, List.of(parameter),
                List.of(new Stmt.Return(read, reference.operator)), TypeDescriptor.ofInfer());
        final var readerType = TypeDescriptor.functionOf("", propertyType, owner);
        LambdaResolver.resolveLambda(context, reader, readerType);
        context.trackLambda(reader);
        reference.setReader(reader);
        if (property.isMutating() && (property.isPublic()
                || Objects.equals(context.frame.currentClassName, ownerName))) {
            final var writerOwner = new ReferenceDescriptor(owner);
            final var ownerParameter = new Token(TokenType.IDENTIFIER,
                    "$zeron$propertyRef$owner", null, reference.propertyName.span());
            final var valueParameter = new Token(TokenType.IDENTIFIER,
                    "$zeron$propertyRef$value", null, reference.propertyName.span());
            final var target = new Expr.Property(
                    new Expr.Variable(ownerParameter, writerOwner),
                    reference.propertyName, TypeDescriptor.ofInfer());
            final var assignment = new Expr.PropertyAssignment(target,
                    new Expr.Variable(valueParameter, propertyType), TypeDescriptor.ofInfer());
            final var writer = new Expr.Lambda(reference.operator,
                    List.of(ownerParameter, valueParameter),
                    List.of(writerOwner, propertyType),
                    List.of(new Stmt.Expression(assignment)), TypeDescriptor.ofInfer());
            LambdaResolver.resolveLambda(context, writer,
                    TypeDescriptor.functionOf("", TypeDescriptor.ofUnit(),
                            writerOwner, propertyType));
            context.trackLambda(writer);
            reference.setWriter(writer);
        }
        reference.setType(TypeDescriptor.genericOf(
                TypeDescriptor.ofName(SyntheticNames.PROPERTY_REF_TYPE), owner, propertyType));
        reference.markResolved();
    }

    private record MethodCandidate(Stmt.ContractMethod method, String declaringName,
                                   Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {}

    private static boolean resolveCallable(final ResolutionContext context,
                                           final Expr.PropertyReference reference,
                                           final TypeDescriptor owner,
                                           final String ownerName,
                                           final Stmt.ClassDecl classDeclaration,
                                           final Stmt.ContractDecl contractDeclaration,
                                           final TypeDescriptor expectedType) {
        final var classSubstitutions = classDeclaration == null
                ? Map.<TypeParameterDescriptor, TypeDescriptor>of()
                : TypeResolver.substitutionsFor(context, classDeclaration.typeParameters(), owner);
        final var name = reference.propertyName.lexeme();

        if (classDeclaration != null) {
            final var method = classDeclaration.methods().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(name))
                    .findFirst().orElse(null);
            if (method != null) {
                if (!method.isPublic()
                        && !Objects.equals(context.frame.currentClassName, ownerName)) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.INACCESSIBLE_DECLARATION, reference.propertyName,
                            "Method '" + name + "' is not accessible at this reference site."));
                }
                final var descriptor = specialize(context, reference, method.typeDescriptor(),
                        classSubstitutions, methodExpectedType(context, expectedType));
                if (descriptor == null) return true;
                final var parameters = new ArrayList<TypeDescriptor>();
                parameters.add(method.isMutating() ? new ReferenceDescriptor(owner) : owner);
                parameters.addAll(descriptor.parameters());
                return finishCallable(context, reference, owner,
                        Expr.CallableReferenceKind.METHOD, ownerName, method.name(), null,
                        TypeDescriptor.functionWithEffectsOf(name, descriptor.returnType(),
                                parameters, descriptor.typeParameters(), descriptor.raisedEffects()));
            }
            final var contractMethod = findContractMethod(context, classDeclaration, name);
            if (contractMethod != null) {
                return finishMethod(context, reference, owner, ownerName, contractMethod,
                        classSubstitutions, expectedType);
            }
            final var constructorName = reference.propertyName.lexeme();
            if (constructorName.equals("new")) {
                if (!reference.explicitTypeArguments().isEmpty()) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                            reference.operator, "Expected 0 callable type arguments, found "
                                    + reference.explicitTypeArguments().size() + "."));
                }
                if (!classDeclaration.constructor().isPublic()
                        && !Objects.equals(context.frame.currentClassName, ownerName)) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.INACCESSIBLE_DECLARATION, reference.propertyName,
                            "Constructor is private."));
                }
                final var parameterTypes = classDeclaration.canonicalConstructorTypes().stream()
                        .map(type -> TypeSubstitution.substitute(type, classSubstitutions))
                        .toList();
                return finishCallable(context, reference, owner,
                        Expr.CallableReferenceKind.CONSTRUCTOR, ownerName, reference.propertyName,
                        reference.propertyName,
                        TypeDescriptor.functionWithEffectsOf(name, new ReferenceDescriptor(owner),
                                parameterTypes, List.of(), List.of()));
            }
            final var namedConstructor = classDeclaration.namedConstructors().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(name))
                    .findFirst().orElse(null);
            if (namedConstructor != null) {
                if (!namedConstructor.isPublic()
                        && !Objects.equals(context.frame.currentClassName, ownerName)) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.INACCESSIBLE_DECLARATION, reference.propertyName,
                            "Named constructor '" + name + "' is private."));
                }
                final var descriptor = specialize(context, reference,
                        namedConstructor.typeDescriptor(), classSubstitutions, expectedType);
                if (descriptor == null) return true;
                return finishCallable(context, reference, owner,
                        Expr.CallableReferenceKind.CONSTRUCTOR, ownerName,
                        namedConstructor.name(), namedConstructor.name(), descriptor);
            }
        } else if (contractDeclaration != null) {
            final var contractMethod = findContractMethod(context, ownerName,
                    contractDeclaration.typeParameters(), owner, name);
            if (contractMethod != null) {
                return finishMethod(context, reference, owner, ownerName, contractMethod,
                        Map.of(), expectedType);
            }
        }

        final var staticName = ownerName + "." + name;
        final var staticFunction = context.functions.get(staticName);
        if (staticFunction != null && !(staticFunction instanceof Stmt.ExtensionMethod)) {
            if (!Objects.equals(context.namespaceMemberPackages.get(staticName), context.packageName)
                    && !staticFunction.isPublic()) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.INACCESSIBLE_DECLARATION, reference.propertyName,
                        "Namespace function '" + staticName + "' is private."));
            }
            final var descriptor = specialize(context, reference,
                    staticFunction.typeDescriptor(), Map.of(), expectedType);
            if (descriptor == null) return true;
            return finishCallable(context, reference, owner,
                    Expr.CallableReferenceKind.STATIC, ownerName, staticFunction.name(), null,
                    descriptor);
        }
        return false;
    }

    private static boolean finishMethod(final ResolutionContext context,
                                        final Expr.PropertyReference reference,
                                        final TypeDescriptor owner,
                                        final String ownerName,
                                        final MethodCandidate candidate,
                                        final Map<TypeParameterDescriptor, TypeDescriptor> classSubstitutions,
                                        final TypeDescriptor expectedType) {
        final var substitutions = new LinkedHashMap<>(candidate.substitutions());
        substitutions.replaceAll((_, value) -> TypeSubstitution.substitute(value, classSubstitutions));
        substitutions.putAll(classSubstitutions);
        final var descriptor = specialize(context, reference,
                candidate.method().typeDescriptor(), substitutions,
                methodExpectedType(context, expectedType));
        if (descriptor == null) return true;
        final var parameters = new ArrayList<TypeDescriptor>();
        parameters.add(candidate.method().isMutating() ? new ReferenceDescriptor(owner) : owner);
        parameters.addAll(descriptor.parameters());
        return finishCallable(context, reference, owner,
                Expr.CallableReferenceKind.METHOD, candidate.declaringName(), candidate.method().name(),
                null, TypeDescriptor.functionWithEffectsOf(candidate.method().name().lexeme(),
                        descriptor.returnType(), parameters, descriptor.typeParameters(),
                        descriptor.raisedEffects()));
    }

    private static MethodCandidate findContractMethod(final ResolutionContext context,
                                                      final Stmt.ClassDecl declaration,
                                                      final String name) {
        for (final var applied : ContractComposition.forClass(context, declaration)) {
            final var method = applied.declaration().methods().stream()
                    .filter(candidate -> !candidate.isStatic()
                            && candidate.name().lexeme().equals(name))
                    .findFirst().orElse(null);
            if (method != null) {
                return new MethodCandidate(method, applied.declaration().name().lexeme(),
                        applied.substitutions());
            }
        }
        return null;
    }

    private static MethodCandidate findContractMethod(final ResolutionContext context,
                                                      final String ownerName,
                                                      final List<TypeParameterDescriptor> typeParameters,
                                                      final TypeDescriptor owner,
                                                      final String name) {
        final var substitutions = TypeResolver.substitutionsFor(context, typeParameters, owner);
        final var arguments = typeParameters.stream().map(substitutions::get).toList();
        final var use = new Stmt.ContractUse(referenceToken(ownerName), arguments);
        for (final var applied : ContractComposition.closure(context, use)) {
            final var method = applied.declaration().methods().stream()
                    .filter(candidate -> !candidate.isStatic()
                            && candidate.name().lexeme().equals(name))
                    .findFirst().orElse(null);
            if (method != null) {
                return new MethodCandidate(method, applied.declaration().name().lexeme(),
                        applied.substitutions());
            }
        }
        return null;
    }

    private static Token referenceToken(final String name) {
        return new Token(TokenType.IDENTIFIER, name, null,
                com.maruseron.zeron.diagnostic.SourceSpan.line(null, 1));
    }

    /// A method reference's function type includes the receiver as its first parameter, while
    /// the method declaration's own descriptor does not. Generic inference compares like with
    /// like by dropping that leading expected parameter.
    private static TypeDescriptor methodExpectedType(final ResolutionContext context,
                                                     final TypeDescriptor expectedType) {
        if (expectedType == null) return null;
        final var base = expectedType instanceof ReferenceDescriptor referenceType
                ? referenceType.baseType() : expectedType;
        final var function = LambdaResolver.functionType(context, base);
        if (function == null || function.receiverType() != null || function.arity() == 0) {
            return expectedType;
        }
        return TypeDescriptor.functionWithEffectsOf("", function.returnType(),
                function.parameters().subList(1, function.parameters().size()),
                function.typeParameters(), function.raisedEffects());
    }

    private static FunctionDescriptor specialize(final ResolutionContext context,
                                                 final Expr.PropertyReference reference,
                                                 final FunctionDescriptor original,
                                                 final Map<TypeParameterDescriptor, TypeDescriptor> classSubstitutions,
                                                 final TypeDescriptor expectedType) {
        var descriptor = (FunctionDescriptor) TypeSubstitution.substitute(original, classSubstitutions);
        final var explicit = reference.explicitTypeArguments();
        final var explicitTypeParameters = descriptor.explicitTypeParameters();
        if (explicitTypeParameters.isEmpty()) {
            if (!explicit.isEmpty()) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        reference.operator, "Expected 0 callable type arguments, found "
                                + explicit.size() + "."));
                reference.markResolved();
                reference.setType(TypeDescriptor.ofInfer());
                return null;
            }
            return descriptor;
        }
        if (!explicit.isEmpty()) {
            if (explicit.size() != explicitTypeParameters.size()) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        reference.operator, "Expected " + explicitTypeParameters.size()
                                + " callable type arguments, found " + explicit.size() + "."));
                reference.markResolved();
                reference.setType(TypeDescriptor.ofInfer());
                return null;
            }
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            for (int index = 0; index < explicit.size(); index++) {
                substitutions.put(explicitTypeParameters.get(index), explicit.get(index));
            }
            final var substituted = (FunctionDescriptor) TypeSubstitution.substitute(
                    descriptor, substitutions);
            if (!substituted.anonymousExistentialTypeParameters().isEmpty()) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        reference.operator, "An anonymous existential in a callable reference "
                                + "must be determined by the expected function type."));
                reference.markResolved();
                reference.setType(TypeDescriptor.ofInfer());
                return null;
            }
            return substituted;
        }
        final var expected = expectedType == null ? null : LambdaResolver.functionType(context,
                expectedType instanceof ReferenceDescriptor referenceType
                        ? referenceType.baseType() : expectedType);
        if (expected == null || expected.receiverType() != null
                || expected.arity() != descriptor.arity()) {
            Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, reference.operator,
                    "The generic callable reference '" + reference.propertyName.lexeme()
                            + "' needs type arguments; use '::<...>' or a fully constraining "
                            + "function type."));
            reference.markResolved();
            reference.setType(TypeDescriptor.ofInfer());
            return null;
        }
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < descriptor.arity(); index++) {
            TypeUnifier.unify(descriptor.parameters().get(index), expected.parameters().get(index),
                    substitutions, reference.operator, context.typeCompatibility);
        }
        TypeUnifier.unify(descriptor.returnType(), expected.returnType(), substitutions,
                reference.operator, context.typeCompatibility);
        if (!substitutions.keySet().containsAll(descriptor.typeParameters())) {
            Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, reference.operator,
                    "The expected function type does not determine every type argument of '"
                            + reference.propertyName.lexeme() + "'."));
            reference.markResolved();
            reference.setType(TypeDescriptor.ofInfer());
            return null;
        }
        return (FunctionDescriptor) TypeSubstitution.substitute(descriptor, substitutions);
    }

    private static boolean finishCallable(final ResolutionContext context,
                                          final Expr.PropertyReference reference,
                                          final TypeDescriptor owner,
                                          final Expr.CallableReferenceKind kind,
                                          final String declaringName,
                                          final Token nameToken,
                                          final Token explicitName,
                                          final FunctionDescriptor descriptor) {
        reference.setOwnerType(owner);
        reference.setDeclaringTypeName(declaringName);
        final var kindText = switch (kind) {
            case METHOD -> "method";
            case STATIC -> "static";
            case CONSTRUCTOR -> "constructor";
        };
        reference.setIdentity(declaringName + "#" + kindText + "#" + nameToken.lexeme());
        reference.setPropertyToken(nameToken);
        reference.setCallableKind(kind);
        reference.setCallableToken(nameToken);
        context.recordReference(reference.propertyName, nameToken, descriptor);
        final var callable = buildCallableLambda(context, reference, owner, kind, nameToken,
                explicitName, descriptor);
        reference.setCallable(callable);
        final var wrapper = switch (kind) {
            case METHOD -> SyntheticNames.METHOD_REF_TYPE;
            case STATIC -> SyntheticNames.STATIC_METHOD_REF_TYPE;
            case CONSTRUCTOR -> SyntheticNames.CONSTRUCTOR_REF_TYPE;
        };
        reference.setType(TypeDescriptor.genericOf(TypeDescriptor.ofName(wrapper), descriptor));
        reference.markResolved();
        return true;
    }

    private static Expr.Lambda buildCallableLambda(final ResolutionContext context,
                                                   final Expr.PropertyReference reference,
                                                   final TypeDescriptor owner,
                                                   final Expr.CallableReferenceKind kind,
                                                   final Token nameToken,
                                                   final Token explicitName,
                                                   final FunctionDescriptor descriptor) {
        final var parameterTokens = new ArrayList<Token>();
        final var arguments = new ArrayList<Expr>();
        for (int index = 0; index < descriptor.parameters().size(); index++) {
            final var parameter = new Token(TokenType.IDENTIFIER,
                    "$zeron$callableRef$arg" + index, null, reference.operator.span());
            parameterTokens.add(parameter);
            arguments.add(new Expr.Variable(parameter, descriptor.parameters().get(index)));
        }
        final Expr call;
        if (kind == Expr.CallableReferenceKind.METHOD) {
            final var receiverParameter = arguments.getFirst();
            final var methodArguments = arguments.subList(1, arguments.size());
            call = new Expr.MemberCall(receiverParameter, nameToken, reference.operator,
                    List.copyOf(methodArguments), List.of(), TypeDescriptor.ofInfer());
        } else {
            final var typeName = reference.declaringTypeName();
            final var receiver = new Expr.Variable(referenceToken(typeName), owner);
            final var memberCall = new Expr.MemberCall(receiver,
                    explicitName == null ? nameToken : explicitName, reference.operator,
                    List.copyOf(arguments), reference.explicitTypeArguments(),
                    TypeDescriptor.ofInfer());
            if (owner instanceof GenericDescriptor generic) {
                memberCall.setExplicitOwnerTypeArguments(generic.typeParameters());
            }
            call = memberCall;
        }
        final var lambda = new Expr.Lambda(reference.operator, List.copyOf(parameterTokens),
                List.of(new Stmt.Return(call, reference.operator)), TypeDescriptor.ofInfer());
        LambdaResolver.resolveLambda(context, lambda, descriptor);
        context.trackLambda(lambda);
        return lambda;
    }

    private static void requireTypeOperand(final ResolutionContext context,
                                           final Expr.PropertyReference reference,
                                           final TypeDescriptor owner) {
        if (!(owner instanceof NominalDescriptor nominal)) return;
        if (BuiltinTypes.NAMES.contains(nominal.name())) return;
        if (context.types.contains(nominal.name())
                || context.externalClasses.containsKey(nominal.name())
                || context.javaClassPath.find(nominal.name()) != null) {
            return;
        }
        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                reference.operator,
                "The left side of '::' must name a type, and '" + nominal.name()
                        + "' is not a type in scope. Bound references such as 'value::property' "
                        + "are not supported; write a lambda instead."));
    }

    private static TypeDescriptor unwrapOwner(final ResolutionContext context,
                                              final Expr.PropertyReference reference) {
        final var owner = reference.ownerType();
        TypeResolver.resolveTypeNames(context, owner, reference.operator);
        final var dereferenced = owner instanceof ReferenceDescriptor referenceType
                ? referenceType.baseType() : owner;
        if (dereferenced instanceof NullableDescriptor nullable) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    reference.operator,
                    "The left side of '::' must name a type, not a nullable value."));
            return nullable.baseType();
        }
        return dereferenced;
    }

    private static boolean hasReadableGetter(final Stmt.ClassDecl declaration,
                                             final String propertyName) {
        return declaration.properties().stream()
                .filter(property -> property.name().lexeme().equals(propertyName))
                .findFirst()
                .map(property -> !property.isCustom() || property.getterBody() != null)
                .orElse(true);
    }

    private static boolean matchesExpectedFunction(final ResolutionContext context,
                                                   final Expr.PropertyReference reference,
                                                   final TypeDescriptor readerType,
                                                   final TypeDescriptor expectedType) {
        if (expectedType == null || expectedType.isNullable()) return false;
        final var expectedFunction = LambdaResolver.functionType(
                context, expectedType instanceof ReferenceDescriptor referenceType
                        ? referenceType.baseType() : expectedType);
        if (expectedFunction == null || expectedFunction.receiverType() != null) return false;
        if (!(readerType instanceof FunctionDescriptor reader)) return false;
        // A function-shaped expectation converts the reference even when its own types are not yet
        // concrete, such as the `(T) -> B` parameter of a generic selector; mismatches surface in
        // the ordinary assignability check instead of being rejected here.
        return expectedFunction.arity() == reader.arity()
                && expectedFunction.isHandler() == reader.isHandler();
    }
}
