package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.NamespaceMembers;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.*;

final class DeclarationRegistry {
    private final Set<String> types;
    private final Map<String, Stmt.ClassDecl> classes;
    private final Map<String, Stmt.ExternalClass> externalClasses;
    private final Map<String, Stmt.ContractDecl> contracts;
    private final Map<String, Stmt.FunctionDeclaration> functions;
    private final Map<String, List<Stmt.FunctionDeclaration>> functionOverloads;
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
            final Map<String, Stmt.FunctionDeclaration> functions,
            final Map<String, List<Stmt.FunctionDeclaration>> functionOverloads,
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
        this.functions = functions;
        this.functionOverloads = functionOverloads;
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
                if (!declaration.javaBinaryName().equals(qualifiedName)
                        || !List.of("java.lang.System", "java.io.PrintStream").contains(qualifiedName)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                            declaration.name(),
                            "This external class target is not in the curated JDK facade set."));
                }
                validateExternalClass(declaration, qualifiedName);
                if (externalClasses.putIfAbsent(qualifiedName, declaration) != null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                            declaration.name(), "External class '" + qualifiedName + "' is already declared."));
                }
            } else if (statement instanceof Stmt.ContractDecl declaration) {
                registerType(declaration.name());
                contracts.put(declaration.name().lexeme(), declaration);
            }

        }
    }

    private void validateExternalClass(final Stmt.ExternalClass declaration, final String qualifiedName) {
        if (!declaration.isPublic()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                    declaration.name(), "Curated external classes must be public."));
        }
        final var javaClass = javaClassPath.find(qualifiedName);
        if (javaClass == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    declaration.name(), "The curated Java class is not available in this runtime."));
        }
        if (qualifiedName.equals("java.lang.System")) {
            final var outField = javaClass.fields().stream()
                    .filter(field -> field.name().equals("out") && field.isStatic()).findFirst().orElse(null);
            if (!declaration.methods().isEmpty() || declaration.staticProperties().size() != 1
                    || !declaration.staticProperties().getFirst().name().lexeme().equals("out")
                    || !declaration.staticProperties().getFirst().isPublic()
                    || outField == null
                    || !outField.descriptor().descriptorString().equals("Ljava/io/PrintStream;")
                    || !outField.descriptor().equals(TypeDescriptor.toJavaClassDesc(
                            declaration.staticProperties().getFirst().type()))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                        declaration.name(), "The System facade may expose only public static property 'out'."));
            }
        } else if (!declaration.staticProperties().isEmpty()
                || declaration.methods().size() != 2
                || !declaration.methods().stream().map(method -> method.name().lexeme()).collect(
                        java.util.stream.Collectors.toSet()).equals(Set.of("print", "println"))
                || declaration.methods().stream().anyMatch(method -> !method.isPublic()
                    || !method.isMutating()
                    || !Set.of("print", "println").contains(method.name().lexeme())
                    || method.typeDescriptor().parameters().size() != 1
                    || !method.typeDescriptor().parameters().getFirst().equals(
                            TypeDescriptor.ofAny().toNullable())
                    || !method.typeDescriptor().returnType().equals(TypeDescriptor.ofUnit())
                    || javaClass.methods().stream().noneMatch(javaMethod ->
                            javaMethod.name().equals(method.name().lexeme())
                                    && javaMethod.descriptor().descriptorString()
                                    .equals("(Ljava/lang/Object;)V")))) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                    declaration.name(),
                    "The PrintStream facade may expose only public mutating print/println(Any?): Unit."));
        }
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
        final var overloads = functionOverloads.computeIfAbsent(qualifiedName, _ -> new ArrayList<>());
        if (overloads.stream().anyMatch(existing ->
                canonicalParameterSignature(existing.typeDescriptor())
                        .equals(canonicalParameterSignature(function.typeDescriptor()))
                || !Collections.disjoint(jvmOverloadSignatures(existing), jvmOverloadSignatures(function)))) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                    function.name(), "Duplicate or JVM-colliding function parameter signature for '"
                            + qualifiedName + "'."));
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
            validateExternalFunctionBinding(function.name(), binding);
            externalBindings.put(externalFunction, binding);
        }
        final var symbolToken = new Token(function.name().type(), qualifiedName,
                function.name().literal(), function.name().span());
        overloads.add(function);
        if (functions.putIfAbsent(qualifiedName, function) == null) {
            functionSymbolTokens.put(qualifiedName, symbolToken);
            symbols.declareFunction(function, symbolToken, function.typeDescriptor());
        }
        functionNamesByDeclaration.put(function.name(), qualifiedName);
    }

    private void registerType(final Token name) {
        final var builtin = Set.of("Never", "Any", "Unit", "Int", "Float", "Boolean", "String", "Array");
        if (builtin.contains(name.lexeme()) || !types.add(name.lexeme())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME, name,
                    "Type name '" + name.lexeme() + "' is already declared."));
        }
    }

    private boolean sameFunctionSignature(final FunctionDescriptor left, final FunctionDescriptor right) {
        return left.typeParameters().equals(right.typeParameters())
                && left.parameters().equals(right.parameters())
                && left.returnType().equals(right.returnType());
    }

    private List<TypeDescriptor> canonicalParameterSignature(final FunctionDescriptor descriptor) {
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < descriptor.typeParameters().size(); i++) {
            substitutions.put(descriptor.typeParameters().get(i),
                    new TypeParameterDescriptor(Integer.MIN_VALUE, "T" + i));
        }
        return descriptor.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions)).toList();
    }

    private List<String> erasedParameterSignature(final FunctionDescriptor descriptor) {
        return descriptor.parameters().stream()
                .map(TypeSubstitution::erase)
                .map(type -> TypeDescriptor.toJavaClassDesc(type).descriptorString()).toList();
    }

    private Set<List<String>> jvmOverloadSignatures(final Stmt.FunctionDeclaration function) {
        final var signatures = new HashSet<List<String>>();
        final var descriptor = function.typeDescriptor();
        signatures.add(erasedParameterSignature(descriptor));
        if (function.defaultValues().isEmpty()) return signatures;
        final var fixedArity = Stmt.fixedArity(function.parameters(), function.variadic());
        final var lastWrapperArity = function.variadic() ? fixedArity : descriptor.arity() - 1;
        for (int arity = function.minimumArity(); arity <= lastWrapperArity; arity++) {
            signatures.add(descriptor.parameters().subList(0, arity).stream()
                    .map(TypeSubstitution::erase)
                    .map(type -> TypeDescriptor.toJavaClassDesc(type).descriptorString()).toList());
        }
        return signatures;
    }

    private void validateExternalFunctionBinding(final Token name,
                                                 final FunctionBindingRegistry.Binding binding) {
        if (binding.signature().isGeneric()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                    "External JVM functions cannot be generic."));
        }
        final var target = binding.target();
        final MethodTypeDesc methodDescriptor;
        if (target instanceof FunctionBindingRegistry.StaticMethod methodTarget) {
            methodDescriptor = methodTarget.descriptor();
            final var ownerName = binaryName(methodTarget.owner());
            final var javaClass = javaClassPath.find(ownerName);
            if (javaClass == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                        "External JVM owner '" + ownerName
                                + "' was not found on the configured --java-classpath roots."));
                return;
            }
            if (javaClass.hasGenericSignature()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                        "External JVM functions on generic classes are not supported."));
                return;
            }
            final var matches = javaClass.methods().stream()
                    .anyMatch(method -> method.name().equals(methodTarget.methodName())
                            && method.isStatic()
                            && !method.isConstructor()
                            && !method.isVarArgs()
                            && !method.hasGenericSignature()
                            && method.descriptor().equals(methodDescriptor));
            if (!matches) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                        "Registered JVM static method does not match public class-file metadata."));
            }
        } else if (target instanceof FunctionBindingRegistry.StaticFieldInstanceMethod methodTarget) {
            methodDescriptor = methodTarget.methodDescriptor();
        } else {
            throw new IllegalStateException("Unknown external JVM binding target.");
        }

        if (methodDescriptor.parameterCount() != binding.signature().arity()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                    "Registered JVM method arity does not match the external function signature."));
        }
        for (int index = 0; index < methodDescriptor.parameterCount(); index++) {
            if (!matchesExternalType(methodDescriptor.parameterType(index),
                    binding.signature().parameters().get(index))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                        "Registered JVM parameter descriptor does not match the external function signature."));
            }
        }
        if (!matchesExternalType(methodDescriptor.returnType(), binding.signature().returnType())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                    "Registered JVM return descriptor does not match the external function signature."));
        }
    }

    private boolean matchesExternalType(final ClassDesc javaType, final TypeDescriptor zeronType) {
        final var mappedType = JavaTypeMapping.toZeronType(javaType);
        if (mappedType != null) return mappedType.equals(zeronType);
        return zeronType instanceof ArrayDescriptor
                && javaType.descriptorString().equals("[Ljava/lang/Object;");
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
