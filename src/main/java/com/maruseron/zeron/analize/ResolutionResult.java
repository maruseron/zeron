package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.*;

public record ResolutionResult(
        SymbolTable globalSymbolTable,
        Set<String> types,
        List<Diagnostic> errors,
        Map<String, Stmt.ClassDecl> classes,
        Map<String, Stmt.ContractDecl> contracts,
        Map<Stmt.Var, Token> topLevelValueSymbols,
        Map<Stmt.ExternalFunction, FunctionBindingRegistry.Binding> externalFunctionBindings,
        Map<String, Token> functionSymbolTokens,
        Map<Token, String> functionNamesByDeclaration,
        Map<Token, TypeDescriptor> iterationElementTypes,
        Map<Token, Resolver.IterationProtocol> iterationProtocols,
        String packageName) {

    public ResolutionResult {
        Objects.requireNonNull(globalSymbolTable);
        types = Set.copyOf(types);
        errors = List.copyOf(errors);
        classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        contracts = Collections.unmodifiableMap(new LinkedHashMap<>(contracts));
        topLevelValueSymbols = immutableIdentityMap(topLevelValueSymbols);
        externalFunctionBindings = immutableIdentityMap(externalFunctionBindings);
        functionSymbolTokens = Collections.unmodifiableMap(new LinkedHashMap<>(functionSymbolTokens));
        functionNamesByDeclaration = immutableIdentityMap(functionNamesByDeclaration);
        iterationElementTypes = immutableIdentityMap(iterationElementTypes);
        iterationProtocols = immutableIdentityMap(iterationProtocols);
        Objects.requireNonNull(packageName);
    }

    private static <K, V> Map<K, V> immutableIdentityMap(final Map<K, V> map) {
        final var copy = new IdentityHashMap<K, V>();
        copy.putAll(map);
        return Collections.unmodifiableMap(copy);
    }

    public Token topLevelValueSymbol(final Stmt.Var declaration) {
        return topLevelValueSymbols.get(declaration);
    }

    public FunctionBindingRegistry.Binding externalFunctionBinding(final Stmt.ExternalFunction function) {
        final var binding = externalFunctionBindings.get(function);
        if (binding == null) {
            throw new IllegalStateException("External function was not resolved: " + function.name().lexeme());
        }
        return binding;
    }

    public String functionName(final Token declarationName) {
        return functionNamesByDeclaration.getOrDefault(declarationName,
                packageName.isEmpty() ? declarationName.lexeme()
                        : packageName + "." + declarationName.lexeme());
    }

    public Token functionSymbolToken(final Token declarationName) {
        return functionSymbolTokens.getOrDefault(functionName(declarationName), declarationName);
    }

    public Token functionSymbolToken(final String qualifiedName) {
        return functionSymbolTokens.get(qualifiedName);
    }

    public TypeDescriptor iterationElementType(final Token iterationBind) {
        return iterationElementTypes.get(iterationBind);
    }

    public Resolver.IterationProtocol iterationProtocol(final Token iterationBind) {
        return iterationProtocols.get(iterationBind);
    }

    public Resolver.DefaultMethodSelection defaultMethodFor(final Stmt.ClassDecl declaration,
                                                            final Stmt.ContractUse requiredUse,
                                                            final Stmt.ContractMethod required) {
        final var substitutions = contractSubstitutions(requiredUse);
        final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                required.typeDescriptor(), substitutions);
        final var matches = defaultMethodsFor(declaration, requiredType);
        return matches.size() == 1
                && matches.getFirst().method().isMutating() == required.isMutating()
                ? matches.getFirst()
                : null;
    }

    private List<Resolver.DefaultMethodSelection> defaultMethodsFor(final Stmt.ClassDecl declaration,
                                                                    final FunctionDescriptor requiredType) {
        final var matches = new ArrayList<Resolver.DefaultMethodSelection>();
        for (final var contractUse : declaration.contractUses()) {
            final var candidateContract = contracts.get(contractUse.name().lexeme());
            final var substitutions = contractSubstitutions(contractUse);
            for (final var candidate : candidateContract.methods()) {
                if (!candidate.isDefault()
                        || !candidate.name().lexeme().equals(requiredType.name())) continue;
                final var candidateType = (FunctionDescriptor) TypeSubstitution.substitute(
                        candidate.typeDescriptor(), substitutions);
                if (compatibleMethodSignatures(requiredType, candidateType)) {
                    matches.add(new Resolver.DefaultMethodSelection(candidateContract.name().lexeme(),
                            candidate, candidateType));
                }
            }
        }
        return List.copyOf(matches);
    }

    private LinkedHashMap<TypeParameterDescriptor, TypeDescriptor> contractSubstitutions(
            final Stmt.ContractUse contractUse) {
        final var contract = contracts.get(contractUse.name().lexeme());
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < contract.typeParameters().size(); i++) {
            substitutions.put(contract.typeParameters().get(i), contractUse.typeArguments().get(i));
        }
        return substitutions;
    }

    private boolean compatibleMethodSignatures(final FunctionDescriptor required,
                                               final FunctionDescriptor implementation) {
        if (required.typeParameters().size() != implementation.typeParameters().size()
                || required.arity() != implementation.arity()) return false;
        final var methodSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < required.typeParameters().size(); i++) {
            methodSubstitutions.put(required.typeParameters().get(i),
                    implementation.typeParameters().get(i));
        }
        for (int i = 0; i < required.arity(); i++) {
            if (!TypeSubstitution.substitute(required.parameters().get(i), methodSubstitutions)
                    .equals(implementation.parameters().get(i))) return false;
        }
        final var requiredReturn = TypeSubstitution.substitute(required.returnType(), methodSubstitutions);
        return new TypeCompatibility(classes, contracts).canAssign(requiredReturn, implementation.returnType());
    }

    public boolean isContractProjection(final TypeDescriptor expectedType,
                                       final TypeDescriptor resolvedType) {
        return new TypeCompatibility(classes, contracts).isContractProjection(expectedType, resolvedType);
    }
}
