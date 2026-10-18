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
        Map<String, Stmt.SumDecl> sums,
        Map<String, Stmt.ProductDecl> products,
        Map<String, Stmt.NewtypeDecl> newtypes,
        Map<Stmt.Var, Token> topLevelValueSymbols,
        Map<Stmt.ExternalFunction, FunctionBindingRegistry.Binding> externalFunctionBindings,
        Map<String, String> typePackages,
        Map<String, Token> functionSymbolTokens,
        Map<Token, String> functionNamesByDeclaration,
        Map<Token, TypeDescriptor> iterationElementTypes,
        Map<Token, Resolver.IterationProtocol> iterationProtocols,
        String packageName,
        List<Stmt.ExtensionMethod> extensions,
        Map<Token, Token> referenceTargets,
        Map<Token, TypeDescriptor> referenceTypes,
        Map<Token, FunctionDescriptor> callSiteFunctionTypes) {

    public ResolutionResult {
        Objects.requireNonNull(globalSymbolTable);
        types = Set.copyOf(types);
        errors = List.copyOf(errors);
        classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        contracts = Collections.unmodifiableMap(new LinkedHashMap<>(contracts));
        sums = Collections.unmodifiableMap(new LinkedHashMap<>(sums));
        products = Collections.unmodifiableMap(new LinkedHashMap<>(products));
        newtypes = Collections.unmodifiableMap(new LinkedHashMap<>(newtypes));
        topLevelValueSymbols = immutableIdentityMap(topLevelValueSymbols);
        externalFunctionBindings = immutableIdentityMap(externalFunctionBindings);
        typePackages = Collections.unmodifiableMap(new LinkedHashMap<>(typePackages));
        functionSymbolTokens = Collections.unmodifiableMap(new LinkedHashMap<>(functionSymbolTokens));
        functionNamesByDeclaration = immutableIdentityMap(functionNamesByDeclaration);
        iterationElementTypes = immutableIdentityMap(iterationElementTypes);
        iterationProtocols = immutableIdentityMap(iterationProtocols);
        referenceTargets = immutableIdentityMap(referenceTargets);
        referenceTypes = immutableIdentityMap(referenceTypes);
        callSiteFunctionTypes = immutableIdentityMap(callSiteFunctionTypes);
        Objects.requireNonNull(packageName);
        extensions = List.copyOf(extensions);
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

    public Token referenceTarget(final Token reference) {
        return referenceTargets.get(reference);
    }

    public TypeDescriptor referenceType(final Token reference) {
        return referenceTypes.get(reference);
    }

    public FunctionDescriptor callSiteFunctionType(final Token callSite) {
        return callSiteFunctionTypes.get(callSite);
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

    public Stmt.Method implementationFor(final Stmt.ClassDecl declaration,
                                         final Stmt.ContractUse requiredUse,
                                         final Stmt.ContractMethod required) {
        final var substitutions = contractSubstitutions(requiredUse);
        final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                required.typeDescriptor(), substitutions);
        return declaration.methods().stream()
                .filter(method -> method.name().lexeme().equals(required.name().lexeme())
                        && method.isPublic() && method.isMutating() == required.isMutating()
                        && method.variadic() == required.variadic()
                        && compatibleMethodSignatures(requiredType, method.typeDescriptor()))
                .findFirst().orElse(null);
    }

    private List<Resolver.DefaultMethodSelection> defaultMethodsFor(final Stmt.ClassDecl declaration,
                                                                    final FunctionDescriptor requiredType) {
        final var matches = new ArrayList<Resolver.DefaultMethodSelection>();
        final var seen = new HashSet<String>();
        for (final var root : declaration.contractUses()) {
            for (final var applied : ContractComposition.closure(contracts, root)) {
                final var candidateContract = applied.declaration();
                final var substitutions = applied.substitutions();
                for (final var candidate : candidateContract.methods()) {
                    if (ContractComposition.isShadowedByAncestor(
                            contracts, root, applied.use(), candidate.name().lexeme())) continue;
                    final var key = applied.use().name().lexeme() + applied.use().typeArguments()
                            + "#" + candidate.name().lexeme();
                    if (!seen.add(key)) continue;
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
        final var requiredEffects = required.raisedEffects().stream()
                .map(effect -> TypeSubstitution.substitute(effect, methodSubstitutions)).toList();
        return requiredEffects.containsAll(implementation.raisedEffects())
                && new TypeCompatibility(classes, contracts).canAssign(requiredReturn, implementation.returnType());
    }

    public boolean isContractProjection(final TypeDescriptor expectedType,
                                       final TypeDescriptor resolvedType) {
        return new TypeCompatibility(classes, contracts).isContractProjection(expectedType, resolvedType);
    }
}
