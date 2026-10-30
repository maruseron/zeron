package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.*;

final class ResolutionContext {
    final ResolutionDiagnostics diagnostics = new ResolutionDiagnostics();
    final SymbolTable symbols = new SymbolTable();
    final Set<String> types = new HashSet<>();
    final Map<String, Stmt.ClassDecl> classes = new LinkedHashMap<>();
    final Map<String, Stmt.ExternalClass> externalClasses = new LinkedHashMap<>();
    final Map<String, Stmt.ContractDecl> contracts = new LinkedHashMap<>();
    final Map<String, String> typePackages = new LinkedHashMap<>();
    final Map<String, Stmt.SumDecl> sums = new LinkedHashMap<>();
    final Map<String, Stmt.ProductDecl> products = new LinkedHashMap<>();
    final Map<String, Stmt.NewtypeDecl> newtypes = new LinkedHashMap<>();
    final List<TypeClassEvidence.WitnessEntry> typeClassWitnesses = new ArrayList<>();
    final Map<String, Stmt.FunctionDeclaration> functions = new LinkedHashMap<>();
    final Map<String, List<Stmt.ExtensionMethod>> extensionMethods = new LinkedHashMap<>();
    final Map<String, Stmt.Var> topLevelValues = new LinkedHashMap<>();
    final Map<String, Token> topLevelValueSymbols = new LinkedHashMap<>();
    final IdentityHashMap<Stmt.Var, Token> topLevelTokensByDeclaration = new IdentityHashMap<>();
    final Map<String, Token> functionSymbolTokens = new LinkedHashMap<>();
    final IdentityHashMap<Token, String> functionNamesByDeclaration = new IdentityHashMap<>();
    final IdentityHashMap<Stmt.ExternalFunction, FunctionBindingRegistry.Binding>
            externalFunctionBindings = new IdentityHashMap<>();
    final IdentityHashMap<Stmt.Function, String> higherKindedImplementationOwners = new IdentityHashMap<>();
    final Map<Token, TypeDescriptor> iterationElementTypes = new IdentityHashMap<>();
    final Map<Token, Resolver.IterationProtocol> iterationProtocols = new IdentityHashMap<>();
    final Map<Token, Resolver.SinkProtocol> sinkProtocols = new IdentityHashMap<>();
    final Map<Token, Token> referenceTargets = new IdentityHashMap<>();
    final Map<Token, TypeDescriptor> referenceTypes = new IdentityHashMap<>();
    final Map<Token, FunctionDescriptor> callSiteFunctionTypes = new IdentityHashMap<>();
    Map<TypeParameterDescriptor, TypeDescriptor> activeTypeSubstitutions = Map.of();
    final List<Expr.Lambda> resolvedLambdas = new ArrayList<>();
    private final Set<Expr.Lambda> trackedLambdas = Collections.newSetFromMap(new IdentityHashMap<>());
    final IdentityHashMap<Stmt, String> sourcePathsByDeclaration = new IdentityHashMap<>();
    final IdentityHashMap<Stmt, String> namespaceNamesByDeclaration = new IdentityHashMap<>();
    final Map<String, String> namespaceMemberPackages = new LinkedHashMap<>();
    final Set<String> namespaces = new LinkedHashSet<>();
    final Map<String, String> declarationPackages = new LinkedHashMap<>();
    final IdentityHashMap<Token, String> sourcePathsByToken = new IdentityHashMap<>();
    final TypeCompatibility typeCompatibility = new TypeCompatibility(classes, contracts);
    final IntrinsicRegistry intrinsics = IntrinsicRegistry.standard();
    final JavaClassPath javaClassPath;
    final ImportResolver importResolver;
    final TopLevelValuePlanner topLevelValuePlanner;
    final DeclarationRegistry declarationRegistrar;
    final FunctionResolutionFrame frame = new FunctionResolutionFrame();
    final StatementResolver statementResolver;
    final CallResolver callResolver;
    String packageName;
    String entrySourcePath;
    ImportResolver.ImportEnvironment currentImports =
            new ImportResolver.ImportEnvironment(
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of());
    Set<String> invalidImportAliases = Set.of();
    String currentSourcePath;
    String currentNamespaceName;

    ResolutionContext(final String packageName,
                      final JavaClassPath javaClassPath,
                      final FunctionBindingRegistry functionBindings) {
        this.packageName = packageName == null ? "" : packageName;
        this.javaClassPath = Objects.requireNonNull(javaClassPath);
        importResolver = new ImportResolver(types, classes, contracts, sums, functions, extensionMethods,
                topLevelValues, declarationPackages, namespaceMemberPackages, namespaces, javaClassPath);
        topLevelValuePlanner = new TopLevelValuePlanner(topLevelValues, namespaces);
        declarationRegistrar = new DeclarationRegistry(
                types, classes, externalClasses, contracts, sums, products, newtypes, functions, extensionMethods,
                topLevelValues, topLevelValueSymbols,
                topLevelTokensByDeclaration, functionSymbolTokens, functionNamesByDeclaration,
                externalFunctionBindings, symbols, javaClassPath, Objects.requireNonNull(functionBindings));
        statementResolver = new StatementResolver(this);
        callResolver = new CallResolver(this);
    }

    void recoverAfterDeclarationFailure() {
        symbols.unwindScopesTo(0);
        frame.flowWriteScopes.clear();
        frame.loopFlows.clear();
        frame.expectedReturnTypes.clear();
        frame.flowState = new FlowState();
        frame.loopDepth = 0;
        frame.currentClassName = null;
        frame.currentMethodOwner = null;
        frame.initializerVisibleFields = null;
        frame.dataInitializerOwner = null;
        frame.dataInitializerWritableFields = java.util.Map.of();
    }

    void skipInvalidImportAlias(final String name) {
        if (invalidImportAliases.contains(name)) throw new SkipResolutionUnit();
    }

    void trackLambda(final Expr.Lambda lambda) {
        if (trackedLambdas.add(lambda)) resolvedLambdas.add(lambda);
    }

    String sourcePath(final Stmt declaration) {
        return sourcePathsByDeclaration.get(declaration);
    }

    String sourcePath(final Token token) {
        return sourcePathsByToken.get(token);
    }

    void recordReference(final Token reference, final Token declaration, final TypeDescriptor type) {
        if (reference == null || declaration == null) return;
        referenceTargets.put(reference, declaration);
        if (type != null) referenceTypes.put(reference, type);
    }

    void recordDeclarationType(final Token declaration, final TypeDescriptor type) {
        if (declaration != null && type != null) referenceTypes.put(declaration, type);
    }

    void recordCallSiteFunctionType(final Token callSite, final FunctionDescriptor type) {
        if (callSite != null && type != null) callSiteFunctionTypes.put(callSite, type);
    }
}
