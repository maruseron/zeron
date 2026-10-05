package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.CompilationUnit;
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
    private final Map<String, Stmt.ContractDecl> contracts;
    private final Map<String, Stmt.FunctionDeclaration> functions;
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
            final Map<String, Stmt.ContractDecl> contracts,
            final Map<String, Stmt.FunctionDeclaration> functions,
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
        this.contracts = contracts;
        this.functions = functions;
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

    void registerTypes(final List<Stmt> statements) {
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

    void registerTopLevelValue(final CompilationUnit unit, final Stmt.Var variable) {
        if (variable.initializer() == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    variable.name(),
                    "Top-level values require an initializer."));
        }
        if (variable.isPublic() && variable.mutability().isReassignable()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    variable.name(),
                    "Public top-level values must be immutable."));
        }
        final var qualifiedName = qualify(unit.packageName(), variable.name().lexeme());
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
        final var qualifiedName = qualify(ownerPackage, function.name().lexeme());
        if (functions.containsKey(qualifiedName)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                    function.name(),
                    "Function '" + qualifiedName + "' is already declared."));
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
        functions.put(qualifiedName, function);
        functionSymbolTokens.put(qualifiedName, symbolToken);
        functionNamesByDeclaration.put(function.name(), qualifiedName);
        symbols.declareFunction(function, symbolToken, function.typeDescriptor());
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
            final var mappedType = JavaTypeMapping.toZeronType(methodDescriptor.parameterType(index));
            if (mappedType == null || !mappedType.equals(binding.signature().parameters().get(index))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                        "Registered JVM parameter descriptor does not match the external function signature."));
            }
        }
        final var mappedReturnType = JavaTypeMapping.toZeronType(methodDescriptor.returnType());
        if (mappedReturnType == null || !mappedReturnType.equals(binding.signature().returnType())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP, name,
                    "Registered JVM return descriptor does not match the external function signature."));
        }
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
