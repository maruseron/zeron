package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.NamespaceMembers;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.*;

final class DeclarationRegistry {
    private static final ClassDesc HTTP_SUPPORT_OWNER = ClassDesc.of("zeron.runtime.HttpSupport");

    private final Set<String> types;
    private final Map<String, Stmt.ClassDecl> classes;
    private final Map<String, Stmt.ExternalClass> externalClasses;
    private final Map<String, Stmt.ContractDecl> contracts;
    private final Map<String, Stmt.SumDecl> sums;
    private final Map<String, Stmt.ProductDecl> products;
    private final Map<String, Stmt.NewtypeDecl> newtypes;
    private final Map<String, Stmt.FunctionDeclaration> functions;
    private final Map<String, List<Stmt.ExtensionMethod>> extensionMethods;
    private final Map<String, Stmt.Var> topLevelValues;
    private final Map<String, Token> topLevelValueSymbols;
    private final IdentityHashMap<Stmt.Var, Token> topLevelTokensByDeclaration;
    private final Map<String, Token> functionSymbolTokens;
    private final IdentityHashMap<Token, String> functionNamesByDeclaration;
    private final IdentityHashMap<Stmt.ExternalFunction, FunctionBindingRegistry.Binding> externalBindings;
    private final SymbolTable symbols;
    private final JavaClassPath javaClassPath;
    private final FunctionBindingRegistry functionBindings;

    DeclarationRegistry(
            final Set<String> types,
            final Map<String, Stmt.ClassDecl> classes,
            final Map<String, Stmt.ExternalClass> externalClasses,
            final Map<String, Stmt.ContractDecl> contracts,
            final Map<String, Stmt.SumDecl> sums,
            final Map<String, Stmt.ProductDecl> products,
            final Map<String, Stmt.NewtypeDecl> newtypes,
            final Map<String, Stmt.FunctionDeclaration> functions,
            final Map<String, List<Stmt.ExtensionMethod>> extensionMethods,
            final Map<String, Stmt.Var> topLevelValues,
            final Map<String, Token> topLevelValueSymbols,
            final IdentityHashMap<Stmt.Var, Token> topLevelTokensByDeclaration,
            final Map<String, Token> functionSymbolTokens,
            final IdentityHashMap<Token, String> functionNamesByDeclaration,
            final IdentityHashMap<Stmt.ExternalFunction, FunctionBindingRegistry.Binding> externalBindings,
            final SymbolTable symbols,
            final JavaClassPath javaClassPath,
            final FunctionBindingRegistry functionBindings) {
        this.types = types;
        this.classes = classes;
        this.externalClasses = externalClasses;
        this.contracts = contracts;
        this.sums = sums;
        this.products = products;
        this.newtypes = newtypes;
        this.functions = functions;
        this.extensionMethods = extensionMethods;
        this.topLevelValues = topLevelValues;
        this.topLevelValueSymbols = topLevelValueSymbols;
        this.topLevelTokensByDeclaration = topLevelTokensByDeclaration;
        this.functionSymbolTokens = functionSymbolTokens;
        this.functionNamesByDeclaration = functionNamesByDeclaration;
        this.externalBindings = externalBindings;
        this.symbols = symbols;
        this.javaClassPath = javaClassPath;
        this.functionBindings = functionBindings;
    }

    void registerTypes(final String ownerPackage, final List<Stmt> statements) {
        for (final var statement : statements) {
            if (statement instanceof Stmt.ClassDecl declaration) {
                registerType(declaration.name());
                classes.put(declaration.name().lexeme(), declaration);
            } else if (statement instanceof Stmt.ExternalClass declaration) {
                final var qualifiedName = qualify(ownerPackage, declaration.name().lexeme());
                if (!declaration.javaBinaryName().equals(qualifiedName)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE,
                            declaration.name(),
                            "An external class must use its Java binary name as its qualified source name."));
                }
                validateExternalClass(declaration, qualifiedName);
                if (externalClasses.putIfAbsent(qualifiedName, declaration) != null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                            declaration.name(), "External class '" + qualifiedName + "' is already declared."));
                }
            } else if (statement instanceof Stmt.ContractDecl declaration) {
                registerType(declaration.name());
                contracts.put(declaration.name().lexeme(), declaration);
            } else if (statement instanceof Stmt.SumDecl declaration) {
                registerSum(declaration);
            } else if (statement instanceof Stmt.ProductDecl declaration) {
                registerProduct(declaration);
            } else if (statement instanceof Stmt.NewtypeDecl declaration) {
                registerNewtype(declaration);
            }

        }
    }

    private void registerNewtype(final Stmt.NewtypeDecl newtype) {
        registerType(newtype.name());
        if (newtypes.putIfAbsent(newtype.name().lexeme(), newtype) != null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                    newtype.name(), "Newtype '" + newtype.name().lexeme() + "' is already declared."));
        }
        final var valueName = new Token(TokenType.IDENTIFIER, "value", null, newtype.name().span());
        final var property = new Stmt.Property(valueName, newtype.underlyingType(), null,
                newtype.isPublic(), false, null, null, null, true);
        final var constructor = new Stmt.Constructor(
                new Token(TokenType.IDENTIFIER, "new", null, newtype.name().span()),
                newtype.isPublic() && newtype.validator() == null);
        classes.put(newtype.name().lexeme(), new Stmt.ClassDecl(newtype.name(), List.of(), List.of(),
                List.of(), List.of(property), constructor, List.of(), List.of(), List.of(),
                newtype.isPublic(), false));
    }

    private void registerProduct(final Stmt.ProductDecl product) {
        registerType(product.name());
        if (products.putIfAbsent(product.name().lexeme(), product) != null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                    product.name(), "Product type '" + product.name().lexeme() + "' is already declared."));
        }
        if (product.components().size() < 2) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    product.name(), "A product type must declare at least two components."));
        }
        final var componentNames = new HashSet<String>();
        for (final var component : product.components()) {
            if (!componentNames.add(component.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        component.name(), "Duplicate product component name."));
            }
        }
        final var properties = product.components().stream()
                .map(component -> new Stmt.Property(component.name(), component.type(), null,
                        product.isPublic(), false, null, null, null, true))
                .toList();
        final var generatedClass = new Stmt.ClassDecl(product.name(), product.typeParameters(), List.of(),
                List.of(), properties,
                new Stmt.Constructor(new Token(TokenType.IDENTIFIER, "new", null,
                        product.name().span()), product.isPublic()),
                List.of(), List.of(), List.of(), product.isPublic(), false);
        classes.put(product.name().lexeme(), generatedClass);
    }

    private void registerSum(final Stmt.SumDecl sum) {
        registerType(sum.name());
        if (sums.putIfAbsent(sum.name().lexeme(), sum) != null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                    sum.name(), "Sum type '" + sum.name().lexeme() + "' is already declared."));
        }
        if (sum.cases().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                    sum.name(), "A sum type must declare at least one case."));
        }
        final var caseNames = new HashSet<String>();
        final var permitted = new ArrayList<Stmt.ContractUse>();
        for (final var sumCase : sum.cases()) {
            if (!caseNames.add(sumCase.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        sumCase.name(), "Duplicate sum case name."));
            }
            final var caseClassName = sum.generatedCaseName(sumCase);
            final var caseClassToken = new Token(sum.name().type(), caseClassName, null, sumCase.name().span());
            final var typeArguments = sum.typeParameters().stream()
                    .map(parameter -> (TypeDescriptor) parameter).toList();
            permitted.add(new Stmt.ContractUse(caseClassToken, typeArguments));
            final var fields = sumCase.payloads().stream()
                    .map(payload -> new Stmt.Field(payload.name(), payload.type()))
                    .toList();
            final var contractUse = new Stmt.ContractUse(sum.name(), typeArguments);
            final var caseClass = new Stmt.ClassDecl(caseClassToken, sum.typeParameters(), List.of(contractUse),
                    fields, List.of(), new Stmt.Constructor(new Token(TokenType.IDENTIFIER, "new", null,
                            sumCase.name().span()), true), List.of(), List.of(), List.of(), sum.isPublic(), false);
            registerType(caseClassToken);
            classes.put(caseClassName, caseClass);
        }
        final var sumContract = new Stmt.ContractDecl(sum.name(), sum.typeParameters(), List.of(),
                List.of(), List.of(), sum.isPublic(), true, false, permitted);
        contracts.put(sum.name().lexeme(), sumContract);
    }

    private void validateExternalClass(final Stmt.ExternalClass declaration, final String qualifiedName) {
        if (!declaration.isPublic()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                    declaration.name(), "External classes must be public."));
        }
        final var javaClass = javaClassPath.find(qualifiedName);
        if (javaClass == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    declaration.name(), "The external Java class is not available in the configured classpath or JDK."));
        }
        final var constructorDescriptors = new HashSet<MethodTypeDesc>();
        for (final var constructor : declaration.constructors()) {
            final var descriptor = externalConstructorDescriptor(constructor);
            final var javaConstructor = javaClass.methods().stream()
                    .filter(method -> matchesExternalConstructor(constructor, method))
                    .findFirst().orElse(null);
            if (!constructor.isPublic() || descriptor == null || !constructorDescriptors.add(descriptor)
                    || javaConstructor == null) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        constructor.name(), "External constructor does not match a unique public Java constructor."));
                continue;
            }
            validateExternalExceptionMappings(constructor.name(), constructor.exceptionMappings(), javaConstructor);
        }
        final var methodDescriptors = new HashSet<String>();
        for (final var method : declaration.methods()) {
            final var descriptor = externalMethodDescriptor(method);
            final var signatureKey = method.name().lexeme() + (descriptor == null ? "" : descriptor.descriptorString());
            final var matchingMethods = javaClass.methods().stream()
                    .filter(candidate -> matchesExternalMethod(method, candidate))
                    .toList();
            final var exactMethods = matchingMethods.stream()
                    .filter(candidate -> descriptor != null && descriptor.equals(candidate.descriptor()))
                    .toList();
            final var preferredMethods = exactMethods.isEmpty() ? matchingMethods : exactMethods;
            if (!method.isPublic() || descriptor == null || !methodDescriptors.add(signatureKey)
                    || preferredMethods.size() != 1) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        method.name(), "External method does not match a unique public Java method."));
                continue;
            }
            validateExternalExceptionMappings(method.name(), method.exceptionMappings(), preferredMethods.getFirst());
        }
        final var propertyNames = new HashSet<String>();
        for (final var property : declaration.staticProperties()) {
            final var javaField = javaClass.fields().stream()
                    .filter(field -> field.name().equals(property.name().lexeme()))
                    .findFirst().orElse(null);
            if (!property.isPublic() || !propertyNames.add(property.name().lexeme())
                    || javaField == null || !javaField.isStatic() || javaField.hasGenericSignature()
                    || !javaField.descriptor().equals(TypeDescriptor.toJavaClassDesc(property.type()))) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        property.name(), "External static property does not match a unique public Java static field."));
            }
        }
    }

    private void validateExternalExceptionMappings(final Token where,
                                                   final List<Stmt.ExternalExceptionMapping> exceptionMappings,
                                                   final JavaClassPath.JavaMethod javaMethod) {
        final var mappingsByType = new HashMap<String, Stmt.ExternalExceptionMapping>();
        for (final var mapping : exceptionMappings) {
            if (!(mapping.javaExceptionType() instanceof NominalDescriptor exceptionType)) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        mapping.constructorName(), "Mapped Java exceptions must use a nominal Java class type."));
                continue;
            }
            final var exceptionName = exceptionType.name();
            if (javaClassPath.find(exceptionName) == null
                    || !javaClassPath.isSubtypeOf(exceptionName, "java.lang.Throwable")
                    || !isCheckedException(exceptionName)) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        mapping.constructorName(),
                        "Exception mappings must name checked Java Throwable subtypes."));
                continue;
            }
            if (mappingsByType.putIfAbsent(exceptionName, mapping) != null) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        mapping.constructorName(), "A Java exception type can be mapped only once per method."));
            }
        }

        final var declaredChecked = javaMethod.exceptionTypes().stream()
                .filter(this::isCheckedException)
                .toList();
        for (final var declaredException : declaredChecked) {
            if (mappingsByType.keySet().stream()
                    .noneMatch(mappedType -> javaClassPath.isSubtypeOf(declaredException, mappedType))) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        where, "Declared checked Java exception '" + declaredException
                                + "' must have an explicit Zeron effect mapping."));
            }
        }
        for (final var mappedType : mappingsByType.keySet()) {
            if (declaredChecked.stream()
                    .noneMatch(declaredType -> javaClassPath.isSubtypeOf(mappedType, declaredType))) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        mappingsByType.get(mappedType).constructorName(),
                        "Mapped Java exception type '" + mappedType
                                + "' is not a declared exception type or its subtype."));
            }
        }
    }

    private boolean isCheckedException(final String exceptionType) {
        return javaClassPath.isSubtypeOf(exceptionType, "java.lang.Throwable")
                && !javaClassPath.isSubtypeOf(exceptionType, "java.lang.RuntimeException")
                && !javaClassPath.isSubtypeOf(exceptionType, "java.lang.Error");
    }

    static boolean matchesExternalConstructor(final Stmt.ExternalConstructor declaration,
                                              final JavaClassPath.JavaMethod method) {
        if (!declaration.isPublic() || !method.isConstructor() || method.isStatic()
                || method.isVarArgs() || method.hasGenericSignature()
                || declaration.parameterTypes().size() != method.descriptor().parameterCount()) return false;
        for (int index = 0; index < declaration.parameterTypes().size(); index++) {
            if (!matchesExternalParameterType(method.descriptor().parameterType(index),
                    declaration.parameterTypes().get(index))) return false;
        }
        return true;
    }

    static boolean matchesExternalMethod(final Stmt.ExternalMethod declaration,
                                         final JavaClassPath.JavaMethod method) {
        if (!declaration.isPublic() || method.isConstructor()
                || declaration.isStatic() != method.isStatic()
                || !declaration.name().lexeme().equals(method.name())) return false;
        if (method.hasGenericSignature() && !method.isVarArgs()) return false;
        final var javaDescriptor = method.descriptor();
        if (!method.isVarArgs()) {
            if (declaration.typeDescriptor().arity() != javaDescriptor.parameterCount()) return false;
            for (int index = 0; index < javaDescriptor.parameterCount(); index++) {
                if (!matchesExternalParameterType(javaDescriptor.parameterType(index),
                        declaration.typeDescriptor().parameters().get(index))) return false;
            }
            return matchesExternalReturnType(javaDescriptor.returnType(),
                    declaration.typeDescriptor().returnType());
        }
        if (declaration.typeDescriptor().arity() != javaDescriptor.parameterCount() - 1
                || !matchesExternalReturnType(javaDescriptor.returnType(),
                        declaration.typeDescriptor().returnType())) return false;
        for (int index = 0; index < declaration.typeDescriptor().arity(); index++) {
            if (!matchesExternalParameterType(javaDescriptor.parameterType(index),
                    declaration.typeDescriptor().parameters().get(index))) return false;
        }
        return true;
    }

    private static MethodTypeDesc externalConstructorDescriptor(final Stmt.ExternalConstructor constructor) {
        final var parameters = constructor.parameterTypes().stream()
                .map(TypeDescriptor::toJavaClassDesc)
                .toArray(ClassDesc[]::new);
        return MethodTypeDesc.of(ConstantDescs.CD_void, parameters);
    }

    private static MethodTypeDesc externalMethodDescriptor(final Stmt.ExternalMethod method) {
        final var parameters = method.typeDescriptor().parameters().stream()
                .map(TypeDescriptor::toJavaClassDesc)
                .toArray(ClassDesc[]::new);
        final var returnType = method.typeDescriptor().returnType();
        final var returnDescriptor = returnType instanceof UnitDescriptor
                ? ConstantDescs.CD_void : TypeDescriptor.toJavaClassDesc(returnType);
        return MethodTypeDesc.of(returnDescriptor, parameters);
    }

    void registerTopLevelValue(final CompilationUnit unit, final Stmt.Var variable) {
        registerTopLevelValue(unit, variable, null);
    }

    void registerTopLevelValue(final CompilationUnit unit, final Stmt.Var variable,
                               final String namespaceName) {
        if (variable.initializer() == null && !unit.metadataOnly()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    variable.name(),
                    "Top-level values require an initializer."));
        }
        if (variable.isPublic() && variable.mutability().isReassignable()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    variable.name(),
                    "Public top-level values must be immutable."));
        }
        final var qualifiedName = namespaceName == null
                ? qualify(unit.packageName(), variable.name().lexeme())
                : NamespaceMembers.qualifiedName(unit.packageName(), namespaceName, variable.name().lexeme());
        if (topLevelValues.putIfAbsent(qualifiedName, variable) != null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                    variable.name(),
                    "Top-level value '" + qualifiedName + "' is already declared."));
            return;
        }
        final var symbol = new Token(variable.name().type(), qualifiedName,
                variable.name().literal(), variable.name().span());
        topLevelValueSymbols.put(qualifiedName, symbol);
        topLevelTokensByDeclaration.put(variable, symbol);
    }

    void registerFunction(final String ownerPackage, final Stmt.FunctionDeclaration function) {
        registerFunction(ownerPackage, null, function);
    }

    void registerFunction(final String ownerPackage, final String namespaceName,
                          final Stmt.FunctionDeclaration function) {
        final var qualifiedName = namespaceName == null
                ? qualify(ownerPackage, function.name().lexeme())
                : NamespaceMembers.qualifiedName(ownerPackage, namespaceName, function.name().lexeme());
        final var extension = function instanceof Stmt.ExtensionMethod method ? method : null;
        if (extension == null && extensionMethods.containsKey(qualifiedName)
                || extension != null && functions.containsKey(qualifiedName)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                    function.name(), "Function and extension names cannot collide for '" + qualifiedName + "'."));
            return;
        }
        if (extension != null) {
            final var declarations = extensionMethods.computeIfAbsent(qualifiedName, _ -> new ArrayList<>());
            if (declarations.stream().anyMatch(existing ->
                    canonicalReceiverType(existing).equals(canonicalReceiverType(extension)))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        function.name(), "An extension for this receiver and name is already declared."));
                return;
            }
            if (declarations.stream().anyMatch(existing ->
                    hasErasedJvmSignatureConflict(existing, extension))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        function.name(), "This extension has the same erased JVM signature as another '"
                                + function.name().lexeme() + "' extension."));
                return;
            }
            declarations.add(extension);
            functionNamesByDeclaration.put(function.name(), qualifiedName);
            return;
        }
        if (functions.containsKey(qualifiedName)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                    function.name(), "Function name '" + qualifiedName + "' is already declared."));
            return;
        }
        if (function instanceof Stmt.ExternalFunction externalFunction) {
            final var binding = functionBindings.find(qualifiedName);
            if (binding == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        function.name(),
                        "No implementation binding is registered for external function '" + qualifiedName + "'."));
            }
            if (!sameFunctionSignature(function.typeDescriptor(), binding.signature())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        function.name(),
                        "External function signature does not match its registered implementation binding."));
            }
            validateExternalFunctionBinding(externalFunction, binding);
            externalBindings.put(externalFunction, binding);
        }
        final var symbolToken = new Token(function.name().type(), qualifiedName,
                function.name().literal(), function.name().span());
        functions.put(qualifiedName, function);
        functionSymbolTokens.put(qualifiedName, symbolToken);
        symbols.declareFunction(function, symbolToken, function.typeDescriptor());
        functionNamesByDeclaration.put(function.name(), qualifiedName);
    }

    private TypeDescriptor canonicalReceiverType(final Stmt.ExtensionMethod extension) {
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < extension.receiverTypeParameters().size(); i++) {
            substitutions.put(extension.receiverTypeParameters().get(i),
                    new TypeParameterDescriptor(Integer.MIN_VALUE, "Receiver" + i));
        }
        return TypeSubstitution.substitute(extension.receiverType(), substitutions);
    }

    private boolean hasErasedJvmSignatureConflict(
            final Stmt.ExtensionMethod left, final Stmt.ExtensionMethod right) {
        final var leftSignatures = erasedJvmSignatures(left);
        final var rightSignatures = erasedJvmSignatures(right);
        return leftSignatures.stream().anyMatch(rightSignatures::contains);
    }

    private Set<String> erasedJvmSignatures(final Stmt.ExtensionMethod extension) {
        final var erased = (FunctionDescriptor) TypeSubstitution.erase(extension.typeDescriptor());
        final var signatures = new HashSet<String>();
        final var fullArity = erased.parameters().size();
        if (!extension.defaultValues().isEmpty()) {
            final var lastWrapperArity = extension.variadic()
                    ? extension.fixedCallArity() + 1 : fullArity - 1;
            for (int arity = extension.minimumArity(); arity <= lastWrapperArity; arity++) {
                signatures.add(erasedJvmSignature(extension, erased, arity));
            }
        }
        signatures.add(erasedJvmSignature(extension, erased, fullArity));
        return signatures;
    }

    private String erasedJvmSignature(final Stmt.ExtensionMethod extension,
                                      final FunctionDescriptor erased, final int arity) {
        final var parameters = new ArrayList<String>();
        erased.parameters().subList(0, arity).stream()
                .map(type -> TypeDescriptor.toJavaClassDesc(type).descriptorString())
                .forEach(parameters::add);
        final var returnType = TypeDescriptor.toJavaClassDesc(erased.returnType()).descriptorString();
        return String.join("", parameters) + "->" + returnType;
    }

    private void registerType(final Token name) {
        if (BuiltinTypes.NAMES.contains(name.lexeme()) || !types.add(name.lexeme())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME, name,
                    "Type name '" + name.lexeme() + "' is already declared."));
        }
    }

    private boolean sameFunctionSignature(final FunctionDescriptor left, final FunctionDescriptor right) {
        return left.typeParameters().equals(right.typeParameters())
                && left.parameters().equals(right.parameters())
                && left.returnType().equals(right.returnType());
    }

    private void validateExternalFunctionBinding(final Stmt.ExternalFunction externalFunction,
                                                 final FunctionBindingRegistry.Binding binding) {
        final var name = externalFunction.name();
        if (binding.signature().isGeneric()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE, name,
                    "External JVM functions cannot be generic."));
        }
        final var target = binding.target();
        if (target instanceof FunctionBindingRegistry.IntrinsicBinding intrinsicBinding) {
            final var signature = IntrinsicRegistry.standard().require(intrinsicBinding.id()).signature();
            if (!signature.typeParameters().isEmpty() || signature.variadic()
                    || !signature.parameterTypes().equals(binding.signature().parameters())
                    || !signature.returnType().equals(binding.signature().returnType())
                    || !binding.signature().raisedEffects().isEmpty()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        name, "External function signature does not match its registered intrinsic."));
            }
            return;
        }
        final MethodTypeDesc methodDescriptor;
        JavaClassPath.JavaMethod boundMethod = null;
        if (target instanceof FunctionBindingRegistry.StaticMethod methodTarget) {
            methodDescriptor = methodTarget.descriptor();
            final var ownerName = binaryName(methodTarget.owner());
            if (!HTTP_SUPPORT_OWNER.equals(methodTarget.owner())) {
                final var javaClass = javaClassPath.find(ownerName);
                if (javaClass == null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                            "External JVM owner '" + ownerName
                                    + "' was not found on the configured --java-classpath roots."));
                    return;
                }
                if (javaClass.hasTypeParameters()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE, name,
                            "External JVM functions on generic classes are not supported."));
                    return;
                }
                boundMethod = javaClass.methods().stream()
                        .filter(method -> method.name().equals(methodTarget.methodName())
                                && method.isStatic()
                                && !method.isConstructor()
                                && !method.isVarArgs()
                                && !method.hasGenericSignature()
                                && method.descriptor().equals(methodDescriptor))
                        .findFirst().orElse(null);
                if (boundMethod == null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                            "Registered JVM static method does not match public class-file metadata."));
                }
            }
        } else if (target instanceof FunctionBindingRegistry.InstanceMethod methodTarget) {
            methodDescriptor = methodTarget.descriptor();
            final var ownerName = binaryName(methodTarget.owner());
            final var javaClass = javaClassPath.find(ownerName);
            if (javaClass == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                        "External JVM owner '" + ownerName
                                + "' was not found on the configured --java-classpath roots."));
                return;
            }
            boundMethod = javaClass.methods().stream()
                    .filter(method -> method.name().equals(methodTarget.methodName())
                            && !method.isStatic()
                            && !method.isConstructor()
                            && !method.isVarArgs()
                            && !method.hasGenericSignature()
                            && method.descriptor().equals(methodDescriptor))
                    .findFirst().orElse(null);
            if (boundMethod == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                        "Registered JVM instance method does not match public class-file metadata."));
            }
            if (binding.signature().arity() != methodDescriptor.parameterCount() + 1
                    || !matchesExternalParameterType(
                            methodTarget.owner(), binding.signature().parameters().getFirst())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        name, "Registered JVM receiver does not match the external function signature."));
            }
        } else if (target instanceof FunctionBindingRegistry.StaticFieldInstanceMethod methodTarget) {
            methodDescriptor = methodTarget.methodDescriptor();
            final var javaClass = javaClassPath.find(binaryName(methodTarget.methodOwner()));
            if (javaClass != null) {
                boundMethod = javaClass.methods().stream()
                        .filter(method -> method.name().equals(methodTarget.methodName())
                                && !method.isStatic() && !method.isConstructor()
                                && method.descriptor().equals(methodDescriptor))
                        .findFirst().orElse(null);
            }
        } else {
            throw new IllegalStateException("Unknown external JVM binding target.");
        }

        final var instanceMethod = target instanceof FunctionBindingRegistry.InstanceMethod;
        if (methodDescriptor.parameterCount() != binding.signature().arity() - (instanceMethod ? 1 : 0)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                    "Registered JVM method arity does not match the external function signature."));
            return;
        }
        for (int index = 0; index < methodDescriptor.parameterCount(); index++) {
            if (!matchesExternalParameterType(methodDescriptor.parameterType(index),
                    binding.signature().parameters().get(index + (instanceMethod ? 1 : 0)))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                        "Registered JVM parameter descriptor does not match the external function signature."));
            }
        }
        if (!matchesExternalReturnType(methodDescriptor.returnType(), binding.signature().returnType())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                    "Registered JVM return descriptor does not match the external function signature."));
        }
        if (boundMethod == null && !externalFunction.exceptionMappings().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    name, "Java exception mappings require public class-file method metadata."));
        } else if (boundMethod != null) {
            validateExternalExceptionMappings(name, externalFunction.exceptionMappings(), boundMethod);
        }
    }

    private static boolean matchesExternalParameterType(final ClassDesc javaType,
                                                        final TypeDescriptor zeronType) {
        return matchesExternalType(JavaTypeMapping.toZeronParameterType(javaType), javaType, zeronType);
    }

    private static boolean matchesExternalReturnType(final ClassDesc javaType,
                                                     final TypeDescriptor zeronType) {
        return matchesExternalType(JavaTypeMapping.toZeronReturnType(javaType), javaType, zeronType);
    }

    private static boolean matchesExternalType(final TypeDescriptor mappedType, final ClassDesc javaType,
                                               final TypeDescriptor zeronType) {
        if (mappedType != null) {
            if (javaType.descriptorString().equals("[Ljava/lang/Object;")
                    && externalCarrierType(zeronType) instanceof ArrayDescriptor) return true;
            return externalCarrierType(mappedType).equals(externalCarrierType(zeronType));
        }
        return false;
    }

    private static TypeDescriptor externalCarrierType(final TypeDescriptor type) {
        return switch (type) {
            case NullableDescriptor nullable -> externalCarrierType(nullable.baseType());
            case ReferenceDescriptor reference -> externalCarrierType(reference.baseType());
            default -> type;
        };
    }

    private String binaryName(final ClassDesc classDescriptor) {
        final var descriptor = classDescriptor.descriptorString();
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) {
            throw new IllegalArgumentException("External JVM owner must be a reference class descriptor.");
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static String qualify(final String ownerPackage, final String simpleName) {
        return ownerPackage == null || ownerPackage.isEmpty() ? simpleName : ownerPackage + "." + simpleName;
    }
}
