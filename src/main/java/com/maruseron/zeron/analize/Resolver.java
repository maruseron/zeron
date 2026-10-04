package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.*;
import java.nio.file.Path;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.concurrent.atomic.AtomicInteger;

public final class Resolver {
    private static final AtomicInteger LAMBDA_TYPE_SCOPES = new AtomicInteger(Integer.MIN_VALUE + 1_000);

    public record IterationProtocol(String iterableName, String iteratorName) {}

    private record FlowFact(boolean mayBeNull, Set<TypeDescriptor> nonNullAlternatives) {
        FlowFact {
            if (nonNullAlternatives != null) nonNullAlternatives = Set.copyOf(nonNullAlternatives);
        }

    }
    private static final class FlowState {
        private final IdentityHashMap<Token, FlowFact> facts = new IdentityHashMap<>();
        private boolean reachable = true;

        FlowState copy() {
            final var copy = new FlowState();
            copy.facts.putAll(facts);
            copy.reachable = reachable;
            return copy;
        }

        static FlowState unreachable() {
            final var state = new FlowState();
            state.reachable = false;
            return state;
        }

        boolean isReachable() {
            return reachable;
        }

        void markUnreachable() {
            reachable = false;
        }

        FlowFact get(final Token bindingName) {
            return facts.get(bindingName);
        }

        void put(final Token bindingName, final FlowFact fact) {
            facts.put(bindingName, fact);
        }

        void remove(final Token bindingName) {
            facts.remove(bindingName);
        }

        static FlowState join(final FlowState left, final FlowState right) {
            if (!left.reachable) return right.copy();
            if (!right.reachable) return left.copy();
            final var joined = new FlowState();
            for (final var entry : left.facts.entrySet()) {
                final var rightFact = right.facts.get(entry.getKey());
                if (rightFact == null) continue;
                final var leftFact = entry.getValue();
                final Set<TypeDescriptor> alternatives;
                if (leftFact.nonNullAlternatives() == null || rightFact.nonNullAlternatives() == null) {
                    alternatives = null;
                } else {
                    final var union = new LinkedHashSet<>(leftFact.nonNullAlternatives());
                    union.addAll(rightFact.nonNullAlternatives());
                    alternatives = union;
                }
                joined.put(entry.getKey(), new FlowFact(
                        leftFact.mayBeNull() || rightFact.mayBeNull(), alternatives));
            }
            return joined;
        }

        boolean sameAs(final FlowState other) {
            if (reachable != other.reachable || facts.size() != other.facts.size()) return false;
            for (final var entry : facts.entrySet()) {
                if (!Objects.equals(entry.getValue(), other.facts.get(entry.getKey()))) return false;
            }
            return true;
        }
    }

    private record ConditionFlows(FlowState whenTrue, FlowState whenFalse) {}
    private record SafeNavigationFlows(FlowState nonNull, FlowState nullPath) {}
    private record PropertyInfo(TypeDescriptor type, boolean isPublic, boolean isMutating) {}

    private static final class LoopFlow {
        private final List<FlowState> breakStates = new ArrayList<>();
        private final List<FlowState> continueStates = new ArrayList<>();
    }

    private record JavaCandidate(JavaClassPath.JavaMethod method,
                                 List<TypeDescriptor> parameterTypes,
                                 TypeDescriptor returnType,
                                 int cost,
                                 boolean varArgs) {}

    // this table stores every name related to a type to avoid name collisions
    public final SymbolTable symbols = new SymbolTable();
    public final Set<String> types   = new HashSet<>();
    private final Map<String, Stmt.ClassDecl> classes = new LinkedHashMap<>();
    private final Map<String, Stmt.ContractDecl> contracts = new LinkedHashMap<>();
        private final Map<String, Stmt.FunctionDeclaration> functions = new LinkedHashMap<>();
    private final Map<String, Token> functionSymbolTokens = new LinkedHashMap<>();
    private final IdentityHashMap<Token, String> functionNamesByDeclaration = new IdentityHashMap<>();
        private final IdentityHashMap<Stmt.ExternalFunction, FunctionBindingRegistry.Binding> externalFunctionBindings =
                new IdentityHashMap<>();
    private final Map<Token, TypeDescriptor> iterationElementTypes = new IdentityHashMap<>();
    private final Map<Token, IterationProtocol> iterationProtocols = new IdentityHashMap<>();
    private final TypeCompatibility typeCompatibility = new TypeCompatibility(classes, contracts);
    private final IntrinsicRegistry intrinsics = IntrinsicRegistry.standard();
    private final JavaClassPath javaClassPath;
    private final FunctionBindingRegistry functionBindings;
    private String packageName;
    private Map<String, String> currentTypeImports = Map.of();
    private Map<String, String> currentFunctionImports = Map.of();
    private List<String> currentOnDemandImports = List.of();
    private final Deque<Set<Token>> flowWriteScopes = new ArrayDeque<>();
    private final Deque<LoopFlow> loopFlows = new ArrayDeque<>();
    private final Deque<TypeDescriptor> expectedReturnTypes = new ArrayDeque<>();
    private FlowState flowState = new FlowState();
    private int loopDepth;
    private String currentClassName;
    private Stmt.ClassDecl currentMethodOwner;
    private List<Stmt.Field> initializerVisibleFields;

    public Resolver() {
        this("", List.of(), FunctionBindingRegistry.standard());
    }

    public Resolver(final String packageName) {
        this(packageName, List.of(), FunctionBindingRegistry.standard());
    }

    public Resolver(final String packageName, final List<Path> javaClassPathRoots) {
        this(packageName, javaClassPathRoots, FunctionBindingRegistry.standard());
    }

    public Resolver(final String packageName,
                    final List<Path> javaClassPathRoots,
                    final FunctionBindingRegistry functionBindings) {
        this.packageName = packageName == null ? "" : packageName;
        javaClassPath = new JavaClassPath(javaClassPathRoots);
        this.functionBindings = Objects.requireNonNull(functionBindings);
    }

    public static final Token SYNTHETIC_IDENTIFIER = new Token(
            TokenType.IDENTIFIER,"<synthetic>", null, -1);
    public static final Stmt SYNTHETIC_VAR = new Stmt.Var(
            SYNTHETIC_IDENTIFIER,
            TypeDescriptor.ofNever(),
            null,
            BindingMutability.IMMUTABLE);
    public static final Stmt SYNTHETIC_FUN = new Stmt.Function(
            SYNTHETIC_IDENTIFIER,
            List.of(),
            TypeDescriptor.functionOf("<synthetic>", TypeDescriptor.ofUnit()),
            List.of(),
            false);

    public void resolve(final List<Stmt> statements) {
        resolveUnits(List.of(new CompilationUnit(null, packageName, statements)));
    }

    public void resolveUnits(final List<CompilationUnit> units) {
        final var statements = units.stream().flatMap(unit -> unit.declarations().stream()).toList();
        registerTypes(statements);
        for (var unitIndex = 0; unitIndex < units.size(); unitIndex++) {
            final var unit = units.get(unitIndex);
            for (final var declaration : unit.declarations()) {
                if (unitIndex > 0 && declaration instanceof Stmt.Var variable) {
                    Zeron.resolutionError(new ResolutionError(variable.name(),
                            "Top-level values outside the entry source unit are deferred until initialization order is specified."));
                }
                if (!(declaration instanceof Stmt.FunctionDeclaration function)) continue;
                if (unitIndex > 0 && function.typeDescriptor().returnType() instanceof InferDescriptor) {
                    Zeron.resolutionError(new ResolutionError(function.name(),
                            "Non-entry compilation-unit functions require an explicit return type."));
                }
                if (!(function.typeDescriptor().returnType() instanceof InferDescriptor)) {
                    registerFunction(unit.packageName(), function);
                }
            }
        }
        for (final var unit : units) {
            packageName = unit.packageName();
            if (unit.metadataOnly()) continue;
            final var imports = validateImports(unit);
            currentTypeImports = imports.types();
            currentFunctionImports = imports.functions();
            currentOnDemandImports = imports.onDemandPackages();
            for (final var statement : unit.declarations()) resolve(statement);
        }
        Zeron.debug("resolution finished successfully with symbol table: \n" + symbols);
    }

    private record ImportEnvironment(Map<String, String> types, Map<String, String> functions,
                                     List<String> onDemandPackages) {}

    private ImportEnvironment validateImports(final CompilationUnit unit) {
        final var importedTypes = new LinkedHashMap<String, String>();
        final var importedFunctions = new LinkedHashMap<String, String>();
        final var onDemandPackages = new ArrayList<String>();
        final var localTypeNames = new HashSet<String>();
        final var localValueNames = new HashSet<String>();
        classes.keySet().stream().filter(name -> packageOf(name).equals(unit.packageName()))
                .map(Resolver::simpleName).forEach(localTypeNames::add);
        contracts.keySet().stream().filter(name -> packageOf(name).equals(unit.packageName()))
                .map(Resolver::simpleName).forEach(localTypeNames::add);
        functions.keySet().stream().filter(name -> packageOf(name).equals(unit.packageName()))
            .map(Resolver::simpleName).forEach(localValueNames::add);
        unit.declarations().stream().filter(Stmt.Var.class::isInstance).map(Stmt.Var.class::cast)
            .map(variable -> variable.name().lexeme()).forEach(localValueNames::add);

        for (final var importDeclaration : unit.imports()) {
            final var target = importDeclaration.qualifiedName();
            if (importDeclaration.onDemand()) {
                if (onDemandPackages.contains(target)) {
                    Zeron.resolutionError(new ResolutionError(importDeclaration.location(),
                            "Duplicate star import for package '" + target + "'."));
                }
                final var packageExists = classes.keySet().stream().anyMatch(name -> packageOf(name).equals(target))
                        || contracts.keySet().stream().anyMatch(name -> packageOf(name).equals(target))
                        || functions.keySet().stream().anyMatch(name -> packageOf(name).equals(target));
                if (!packageExists) {
                    Zeron.resolutionError(new ResolutionError(importDeclaration.location(),
                            "Unknown Zeron package '" + target + "' in star import."));
                }
                onDemandPackages.add(target);
                continue;
            }
            final var classDeclaration = classes.get(target);
            final var contractDeclaration = contracts.get(target);
            final var functionDeclaration = functions.get(target);
            final var javaClass = javaClassPath.find(target);
            if (classDeclaration == null && contractDeclaration == null
                    && functionDeclaration == null && javaClass == null) {
                Zeron.resolutionError(new ResolutionError(importDeclaration.location(),
                        "Unknown import target '" + target + "'."));
            }
            if (packageOf(target).isEmpty() && !unit.packageName().isEmpty()) {
                Zeron.resolutionError(new ResolutionError(importDeclaration.location(),
                        "Default-package types cannot be imported into a named package."));
            }
            final var isPublic = classDeclaration != null ? classDeclaration.isPublic()
                    : contractDeclaration != null ? contractDeclaration.isPublic()
                    : functionDeclaration != null ? functionDeclaration.isPublic()
                    : true;
            if (!packageOf(target).equals(unit.packageName()) && !isPublic) {
                Zeron.resolutionError(new ResolutionError(importDeclaration.location(),
                        "Type '" + target + "' is not public."));
            }
            if (classDeclaration != null || contractDeclaration != null || javaClass != null) {
                if (javaClass != null && javaClass.hasGenericSignature()) {
                    Zeron.resolutionError(new ResolutionError(importDeclaration.location(),
                            "Generic Java classes are not supported by the current interop slice."));
                }
                if (Set.of("Never", "Any", "Infer", "Unit", "Int", "Float", "Boolean", "String", "Array")
                        .contains(importDeclaration.localName())) {
                    Zeron.resolutionError(new ResolutionError(importDeclaration.location(),
                            "Type import alias cannot shadow a built-in type."));
                }
                if (localTypeNames.contains(importDeclaration.localName())) {
                    Zeron.resolutionError(new ResolutionError(importDeclaration.location(),
                            "Import alias conflicts with a type in the current package."));
                }
                if (importedTypes.putIfAbsent(importDeclaration.localName(), target) != null) {
                    Zeron.resolutionError(new ResolutionError(importDeclaration.location(),
                            "Duplicate or ambiguous type import '" + importDeclaration.localName() + "'."));
                }
            } else {
                if (localValueNames.contains(importDeclaration.localName())) {
                    Zeron.resolutionError(new ResolutionError(importDeclaration.location(),
                            "Import alias conflicts with a value in the current package."));
                }
                if (importedFunctions.putIfAbsent(importDeclaration.localName(), target) != null) {
                    Zeron.resolutionError(new ResolutionError(importDeclaration.location(),
                            "Duplicate or ambiguous function import '" + importDeclaration.localName() + "'."));
                }
            }
        }
        return new ImportEnvironment(Map.copyOf(importedTypes), Map.copyOf(importedFunctions),
                List.copyOf(onDemandPackages));
    }

    private void registerFunction(final String ownerPackage, final Stmt.FunctionDeclaration function) {
        final var qualifiedName = qualify(ownerPackage, function.name().lexeme());
        if (functions.containsKey(qualifiedName)) {
            Zeron.resolutionError(new ResolutionError(function.name(),
                    "Function '" + qualifiedName + "' is already declared."));
        }
        if (function instanceof Stmt.ExternalFunction externalFunction) {
            final var binding = functionBindings.find(qualifiedName);
            if (binding == null) {
                Zeron.resolutionError(new ResolutionError(function.name(),
                        "No implementation binding is registered for external function '" + qualifiedName + "'."));
            }
            if (!sameFunctionSignature(function.typeDescriptor(), binding.signature())) {
                Zeron.resolutionError(new ResolutionError(function.name(),
                        "External function signature does not match its registered implementation binding."));
            }
            validateExternalFunctionBinding(function.name(), binding);
            externalFunctionBindings.put(externalFunction, binding);
        }
        final var symbolToken = new Token(function.name().type(), qualifiedName,
                function.name().literal(), function.name().line());
        functions.put(qualifiedName, function);
        functionSymbolTokens.put(qualifiedName, symbolToken);
        functionNamesByDeclaration.put(function.name(), qualifiedName);
        symbols.declareFunction(function, symbolToken, function.typeDescriptor());
    }

    private boolean sameFunctionSignature(final FunctionDescriptor left, final FunctionDescriptor right) {
        return left.typeParameters().equals(right.typeParameters())
                && left.parameters().equals(right.parameters())
                && left.returnType().equals(right.returnType());
    }

    private void validateExternalFunctionBinding(final Token name,
                                                 final FunctionBindingRegistry.Binding binding) {
        if (binding.signature().isGeneric()) {
            Zeron.resolutionError(new ResolutionError(name,
                    "External JVM functions cannot be generic."));
        }
        final var target = binding.target();
        final MethodTypeDesc methodDescriptor;
        if (target instanceof FunctionBindingRegistry.StaticMethod methodTarget) {
            methodDescriptor = methodTarget.descriptor();
            final var ownerName = binaryName(methodTarget.owner());
            final var javaClass = javaClassPath.find(ownerName);
            if (javaClass == null) {
                Zeron.resolutionError(new ResolutionError(name,
                "External JVM owner '" + ownerName
                    + "' was not found on the configured --java-classpath roots."));
            return;
            }
            if (javaClass.hasGenericSignature()) {
            Zeron.resolutionError(new ResolutionError(name,
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
                Zeron.resolutionError(new ResolutionError(name,
                        "Registered JVM static method does not match public class-file metadata."));
            }
        } else if (target instanceof FunctionBindingRegistry.StaticFieldInstanceMethod methodTarget) {
            methodDescriptor = methodTarget.methodDescriptor();
        } else {
            throw new IllegalStateException("Unknown external JVM binding target.");
        }

        if (methodDescriptor.parameterCount() != binding.signature().arity()) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Registered JVM method arity does not match the external function signature."));
        }
        for (int index = 0; index < methodDescriptor.parameterCount(); index++) {
            final var mappedType = JavaTypeMapping.toZeronType(methodDescriptor.parameterType(index));
            if (mappedType == null || !mappedType.equals(binding.signature().parameters().get(index))) {
                Zeron.resolutionError(new ResolutionError(name,
                        "Registered JVM parameter descriptor does not match the external function signature."));
            }
        }
        final var mappedReturnType = JavaTypeMapping.toZeronType(methodDescriptor.returnType());
        if (mappedReturnType == null || !mappedReturnType.equals(binding.signature().returnType())) {
            Zeron.resolutionError(new ResolutionError(name,
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

    public FunctionBindingRegistry.Binding externalFunctionBinding(final Stmt.ExternalFunction function) {
        final var binding = externalFunctionBindings.get(function);
        if (binding == null) {
            throw new IllegalStateException("External function was not resolved: " + function.name().lexeme());
        }
        return binding;
    }

    private static String qualify(final String ownerPackage, final String simpleName) {
        return ownerPackage == null || ownerPackage.isEmpty() ? simpleName : ownerPackage + "." + simpleName;
    }

    public String functionName(final Token declarationName) {
        return functionNamesByDeclaration.getOrDefault(declarationName,
                qualify(packageName, declarationName.lexeme()));
    }

    public Token functionSymbolToken(final Token declarationName) {
        return functionSymbolTokens.getOrDefault(functionName(declarationName), declarationName);
    }

    public Token functionSymbolToken(final String qualifiedName) {
        return functionSymbolTokens.get(qualifiedName);
    }

    public Map<String, Stmt.ClassDecl> classes() {
        return Collections.unmodifiableMap(classes);
    }

    public Map<String, Stmt.ContractDecl> contracts() {
        return Collections.unmodifiableMap(contracts);
    }

    public TypeDescriptor iterationElementType(final Token iterationBind) {
        return iterationElementTypes.get(iterationBind);
    }

    public IterationProtocol iterationProtocol(final Token iterationBind) {
        return iterationProtocols.get(iterationBind);
    }

    private void registerTypes(final List<Stmt> statements) {
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

    private void registerType(final Token name) {
        final var builtin = Set.of("Never", "Any", "Unit", "Int", "Float", "Boolean", "String", "Array");
        if (builtin.contains(name.lexeme()) || !types.add(name.lexeme())) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Type name '" + name.lexeme() + "' is already declared."));
        }
    }

    private void resolveContract(final Stmt.ContractDecl contract) {
        validateSealedContract(contract);
        final var methodNames = new HashSet<String>();
        for (final var method : contract.methods()) {
            if (!methodNames.add(method.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(method.name(), "Duplicate contract method."));
            }
            validateFunctionTypes(method.typeDescriptor(), method.name());
            if (method.isDefault()) {
                resolveContractMethod(contract, method);
            }
        }
        for (final var property : contract.properties()) {
            if (!methodNames.add(property.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(property.name(), "Duplicate contract member."));
            }
            validateType(property.type(), property.name());
        }
    }

    private void validateSealedContract(final Stmt.ContractDecl contract) {
        if (!contract.isSealed()) {
            if (!contract.permittedClasses().isEmpty()) {
                Zeron.resolutionError(new ResolutionError(contract.name(),
                        "Only sealed contracts may declare permitted classes."));
            }
            return;
        }
        if (contract.permittedClasses().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(contract.name(),
                    "A sealed contract must declare at least one permitted class."));
        }

        final var permittedNames = new HashSet<String>();
        for (final var permitted : contract.permittedClasses()) {
            final var className = permitted.name().lexeme();
            if (!permittedNames.add(className)) {
                Zeron.resolutionError(new ResolutionError(permitted.name(),
                        "Duplicate permitted class."));
            }
            final var implementation = classes.get(className);
            if (implementation == null) {
                Zeron.resolutionError(new ResolutionError(permitted.name(),
                        "Unknown permitted class."));
            }
            if (!packageOf(className).equals(packageOf(contract.name().lexeme()))) {
                Zeron.resolutionError(new ResolutionError(permitted.name(),
                        "A permitted class must be in the sealed contract's package."));
            }
            if (contract.isPublic() && !implementation.isPublic()) {
                Zeron.resolutionError(new ResolutionError(permitted.name(),
                        "A public sealed contract can only permit public classes."));
            }
            if (implementation.typeParameters().size() != contract.typeParameters().size()
                    || permitted.typeArguments().size() != contract.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(permitted.name(),
                        "A permitted class must use the sealed contract's type parameters in order."));
            }
            for (int i = 0; i < contract.typeParameters().size(); i++) {
                if (!permitted.typeArguments().get(i).equals(contract.typeParameters().get(i))) {
                    Zeron.resolutionError(new ResolutionError(permitted.name(),
                            "A permitted class must use the sealed contract's type parameters in order."));
                }
            }
            final var conformance = implementation.contractUses().stream()
                    .filter(use -> use.name().lexeme().equals(contract.name().lexeme()))
                    .findFirst()
                    .orElse(null);
            if (conformance == null || conformance.typeArguments().size() != implementation.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(permitted.name(),
                        "A permitted class must directly conform to the sealed contract."));
            }
            for (int i = 0; i < implementation.typeParameters().size(); i++) {
                if (!conformance.typeArguments().get(i).equals(implementation.typeParameters().get(i))) {
                    Zeron.resolutionError(new ResolutionError(permitted.name(),
                            "A permitted class must conform using its type parameters in order."));
                }
            }
        }
    }

    private void resolveContractMethod(final Stmt.ContractDecl owner,
                                       final Stmt.ContractMethod method) {
        beginScope();
        expectedReturnTypes.push(method.typeDescriptor().returnType());
        final var enclosingFlow = flowState;
        final var enclosingLoopDepth = loopDepth;
        flowState = new FlowState();
        loopDepth = 0;
        final var thisToken = new Token(TokenType.THIS, "this", null, method.name().line());
        final TypeDescriptor ownerType = owner.typeParameters().isEmpty()
                ? TypeDescriptor.of(owner.name().lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(owner.name().lexeme()),
                        owner.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
        final var thisType = method.isMutating()
                ? new ReferenceDescriptor(ownerType)
                : ownerType;
        declare(SYNTHETIC_VAR, thisToken, thisType, BindingMutability.IMMUTABLE);
        define(thisToken);
        for (int i = 0; i < method.parameters().size(); i++) {
            final var parameter = method.parameters().get(i);
            declare(SYNTHETIC_VAR, parameter, method.typeDescriptor().parameters().get(i),
                    BindingMutability.IMMUTABLE);
            define(parameter);
        }
        try {
            resolveStmts(method.body());
            ensureReturns(method.name(), method.typeDescriptor().returnType(), method.body());
        } finally {
            expectedReturnTypes.pop();
            endScope();
            loopDepth = enclosingLoopDepth;
            flowState = enclosingFlow;
        }
    }

    private void resolveClass(final Stmt.ClassDecl declaration) {
        final var fieldNames = new HashSet<String>();
        for (final var field : declaration.fields()) {
            if (!fieldNames.add(field.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(field.name(), "Duplicate class member."));
            }
            validateType(field.type(), field.name());
        }

        final var methodNames = new HashSet<String>();
        for (final var property : declaration.properties()) {
            if (!fieldNames.add(property.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(property.name(), "Duplicate class member."));
            }
            validateType(property.type(), property.name());
            if (property.isMutating() && property.setterBody() == null && property.isCustom()) {
                Zeron.resolutionError(new ResolutionError(property.name(),
                        "A writable custom property requires a setter."));
            }
        }
        for (final var method : declaration.methods()) {
            if (!fieldNames.add(method.name().lexeme()) || !methodNames.add(method.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(method.name(), "Duplicate class member."));
            }
            validateFunctionTypes(method.typeDescriptor(), method.name());
        }

        final var contractNames = new HashSet<String>();
        for (final var contractUse : declaration.contractUses()) {
            final var contractName = contractUse.name();
            if (!contractNames.add(contractName.lexeme())) {
                Zeron.resolutionError(new ResolutionError(contractName, "Duplicate contract conformance."));
            }
            final var contract = contracts.get(contractName.lexeme());
            if (contract == null) {
                Zeron.resolutionError(new ResolutionError(contractName, "Unknown contract."));
            }
            ensureTypeAccessible(contractName.lexeme(), contractName, contract.isPublic());
            if (contract.typeParameters().size() != contractUse.typeArguments().size()) {
                Zeron.resolutionError(new ResolutionError(contractName,
                        "Expected " + contract.typeParameters().size() + " contract type arguments, found "
                                + contractUse.typeArguments().size() + "."));
            }
            contractUse.typeArguments().forEach(type -> validateType(type, contractName));
            if (contract.isSealed() && contract.permittedClasses().stream()
                    .noneMatch(permitted -> permitted.name().lexeme().equals(declaration.name().lexeme()))) {
                Zeron.resolutionError(new ResolutionError(contractName,
                        "Class is not listed in the sealed contract's permits clause."));
            }
        }

        final var previousClass = currentClassName;
        final var previousInitializerFields = initializerVisibleFields;
        currentClassName = declaration.name().lexeme();
        try {
            for (int fieldIndex = 0; fieldIndex < declaration.fields().size(); fieldIndex++) {
                final var field = declaration.fields().get(fieldIndex);
                if (field.initializer() == null) continue;
                resolveFieldInitializer(declaration, field,
                        declaration.fields().subList(0, fieldIndex));
            }
            for (final var property : declaration.properties()) {
                if (property.initializer() != null) {
                    resolveFieldInitializer(declaration,
                            new Stmt.Field(property.name(), property.type(), property.initializer()),
                            declaration.fields());
                }
                if (property.isCustom()) resolvePropertyAccessors(declaration, property);
            }
            for (final var method : declaration.methods()) resolveMethod(declaration, method);
            for (final var constructor : declaration.namedConstructors()) {
                resolveNamedConstructor(constructor);
            }
            for (final var contractUse : declaration.contractUses()) {
                checkConformance(declaration, contractUse, contracts.get(contractUse.name().lexeme()));
            }
        } finally {
            currentClassName = previousClass;
            initializerVisibleFields = previousInitializerFields;
        }
    }

    private void resolveFieldInitializer(final Stmt.ClassDecl owner,
                                        final Stmt.Field field,
                                        final List<Stmt.Field> earlierFields) {
        validateFieldInitializer(field.initializer(), earlierFields, field.name());
        final var previousMethodOwner = currentMethodOwner;
        final var previousInitializerFields = initializerVisibleFields;
        final var previousFlow = flowState;
        currentMethodOwner = owner;
        initializerVisibleFields = List.copyOf(earlierFields);
        flowState = new FlowState();
        beginScope();
        final var thisToken = new Token(TokenType.THIS, "this", null, field.name().line());
        declare(SYNTHETIC_VAR, thisToken, classType(owner), BindingMutability.IMMUTABLE);
        define(thisToken);
        try {
            final var initializerType = resolve(field.initializer());
            ensureAssignable(field.type(), initializerType, field.name());
        } finally {
            endScope();
            currentMethodOwner = previousMethodOwner;
            initializerVisibleFields = previousInitializerFields;
            flowState = previousFlow;
        }
    }

    private void validateFieldInitializer(final Expr initializer,
                                          final List<Stmt.Field> earlierFields,
                                          final Token where) {
        switch (initializer) {
            case Expr.Literal _ -> {}
            case Expr.Grouping grouping ->
                    validateFieldInitializer(grouping.expression, earlierFields, where);
            case Expr.Unary unary ->
                    validateFieldInitializer(unary.right, earlierFields, where);
            case Expr.Binary binary -> {
                validateFieldInitializer(binary.left, earlierFields, where);
                validateFieldInitializer(binary.right, earlierFields, where);
            }
            case Expr.Logical logical -> {
                validateFieldInitializer(logical.left, earlierFields, where);
                validateFieldInitializer(logical.right, earlierFields, where);
            }
            case Expr.Variable variable -> {
                if (earlierFields.stream().noneMatch(candidate ->
                        candidate.name().lexeme().equals(variable.name.lexeme()))
                        || !variable.explicitFunctionTypeArguments.isEmpty()) {
                    Zeron.resolutionError(new ResolutionError(variable.name,
                            "A field initializer may read only fields declared earlier."));
                }
            }
            default -> Zeron.resolutionError(new ResolutionError(where,
                    "Field initializers currently allow only literals, operators, and reads of earlier fields."));
        }
    }

    private void resolveMethod(final Stmt.ClassDecl owner, final Stmt.Method method) {
        beginScope();
    expectedReturnTypes.push(method.typeDescriptor().returnType());
        final var enclosingFlow = flowState;
        final var enclosingMethodOwner = currentMethodOwner;
        currentMethodOwner = owner;
        flowState = new FlowState();
        final var thisToken = new Token(TokenType.THIS, "this", null, method.name().line());
        final TypeDescriptor ownerType = classType(owner);
        final TypeDescriptor thisType = method.isMutating()
            ? new ReferenceDescriptor(ownerType)
            : ownerType;
        declare(SYNTHETIC_VAR, thisToken, thisType, BindingMutability.IMMUTABLE);
        define(thisToken);
        for (int i = 0; i < method.parameters().size(); i++) {
            final var parameter = method.parameters().get(i);
            declare(SYNTHETIC_VAR, parameter, method.typeDescriptor().parameters().get(i), BindingMutability.IMMUTABLE);
            define(parameter);
        }
        try {
            resolveStmts(method.body());
            ensureReturns(method.name(), method.typeDescriptor().returnType(), method.body());
        } finally {
            expectedReturnTypes.pop();
            endScope();
            flowState = enclosingFlow;
            currentMethodOwner = enclosingMethodOwner;
        }
    }

    private void resolvePropertyAccessors(final Stmt.ClassDecl owner,
                                          final Stmt.Property property) {
        if (property.getterBody() != null) {
            final var getterName = new Token(TokenType.IDENTIFIER,
                    Stmt.propertyGetterName(property.name().lexeme()), null, property.name().line());
            final var getterType = TypeDescriptor.functionOf(getterName.lexeme(), property.type());
            resolveMethod(owner, new Stmt.Method(getterName, List.of(), getterType,
                    property.isPublic(), false, property.getterBody()));
        }
        if (property.setterBody() != null) {
            final var setterName = new Token(TokenType.IDENTIFIER,
                    Stmt.propertySetterName(property.name().lexeme()), null, property.name().line());
            final var setterType = TypeDescriptor.functionOf(setterName.lexeme(),
                    TypeDescriptor.ofUnit(), property.type());
            resolveMethod(owner, new Stmt.Method(setterName, List.of(property.setterParameter()),
                    setterType, property.isPublic(), true, property.setterBody()));
        }
    }

    private void resolveNamedConstructor(final Stmt.NamedConstructor constructor) {
        beginScope();
        final var enclosingFlow = flowState;
        final var enclosingLoopDepth = loopDepth;
        flowState = new FlowState();
        loopDepth = 0;
        try {
            expectedReturnTypes.push(constructor.typeDescriptor().returnType());
            for (int i = 0; i < constructor.parameters().size(); i++) {
                final var parameter = constructor.parameters().get(i);
                declare(SYNTHETIC_VAR, parameter, constructor.typeDescriptor().parameters().get(i),
                        BindingMutability.IMMUTABLE);
                define(parameter);
            }
            resolveStmts(constructor.body());
            if (flowState.isReachable()) {
                Zeron.resolutionError(new ResolutionError(constructor.name(),
                        "Named constructor must return an instance on every normal path."));
            }
        } finally {
            if (!expectedReturnTypes.isEmpty()) expectedReturnTypes.pop();
            endScope();
            loopDepth = enclosingLoopDepth;
            flowState = enclosingFlow;
        }
    }

    private void checkConformance(final Stmt.ClassDecl declaration,
                                  final Stmt.ContractUse contractUse,
                                  final Stmt.ContractDecl contract) {
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < contract.typeParameters().size(); i++) {
            substitutions.put(contract.typeParameters().get(i), contractUse.typeArguments().get(i));
        }
        for (final var required : contract.methods()) {
            final var implementation = declaration.methods().stream()
                    .filter(method -> method.name().lexeme().equals(required.name().lexeme()))
                    .findFirst()
                    .orElse(null);
            final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                    required.typeDescriptor(), substitutions);
            final var defaults = implementation == null
                    ? defaultMethodsFor(declaration, requiredType)
                    : List.<DefaultMethodSelection>of();
            if (defaults.size() > 1) {
                Zeron.resolutionError(new ResolutionError(contractUse.name(),
                        "Multiple default contract methods named '" + required.name().lexeme()
                                + "' require an explicit class implementation."));
            }
            final var defaultMethod = defaults.size() == 1
                    && defaults.getFirst().method().isMutating() == required.isMutating()
                    ? defaults.getFirst()
                    : null;
            if (implementation == null && defaultMethod == null
                    || implementation != null && (!implementation.isPublic()
                    || implementation.isMutating() != required.isMutating()
                    || !compatibleMethodSignatures(requiredType, implementation.typeDescriptor()))) {
                Zeron.resolutionError(new ResolutionError(contractUse.name(),
                        "Class does not provide a compatible public contract method '"
                                + required.name().lexeme() + "'."));
            }
        }

        for (final var required : contract.properties()) {
            final var implementation = declaration.properties().stream()
                    .filter(property -> property.name().lexeme().equals(required.name().lexeme()))
                    .findFirst()
                    .orElse(null);
            final var requiredType = TypeSubstitution.substitute(required.type(), substitutions);
            if (implementation == null || !implementation.isPublic()
                    || (required.isMutating() && !implementation.isMutating())
                    || !requiredType.equals(implementation.type())) {
                Zeron.resolutionError(new ResolutionError(contractUse.name(),
                        "Class does not provide a compatible public contract property '"
                                + required.name().lexeme() + "'."));
            }
        }
    }

    public record DefaultMethodSelection(String ownerName, Stmt.ContractMethod method,
                                         FunctionDescriptor instantiatedType) {}

    public DefaultMethodSelection defaultMethodFor(final Stmt.ClassDecl declaration,
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

    private List<DefaultMethodSelection> defaultMethodsFor(final Stmt.ClassDecl declaration,
                                                          final FunctionDescriptor requiredType) {
        final var matches = new ArrayList<DefaultMethodSelection>();
        for (final var contractUse : declaration.contractUses()) {
            final var candidateContract = contracts.get(contractUse.name().lexeme());
            final var substitutions = contractSubstitutions(contractUse);
            for (final var candidate : candidateContract.methods()) {
                if (!candidate.isDefault()
                        || !candidate.name().lexeme().equals(requiredType.name())) continue;
                final var candidateType = (FunctionDescriptor) TypeSubstitution.substitute(
                        candidate.typeDescriptor(), substitutions);
                if (compatibleMethodSignatures(requiredType, candidateType)) {
                    matches.add(new DefaultMethodSelection(candidateContract.name().lexeme(), candidate,
                            candidateType));
                }
            }
        }
        return List.copyOf(matches);
    }

    private List<DefaultMethodSelection> defaultMethodsOnClass(final Stmt.ClassDecl declaration,
                                                              final String methodName) {
        final var matches = new ArrayList<DefaultMethodSelection>();
        for (final var contractUse : declaration.contractUses()) {
            final var contract = contracts.get(contractUse.name().lexeme());
            final var substitutions = contractSubstitutions(contractUse);
            for (final var method : contract.methods()) {
                if (!method.isDefault() || !method.name().lexeme().equals(methodName)) continue;
                final var type = (FunctionDescriptor) TypeSubstitution.substitute(
                        method.typeDescriptor(), substitutions);
                matches.add(new DefaultMethodSelection(contract.name().lexeme(), method, type));
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
        return typeCompatibility.canAssign(requiredReturn, implementation.returnType());
    }

    private void validateFunctionTypes(final FunctionDescriptor function, final Token where) {
        for (final var parameter : function.parameters()) validateType(parameter, where);
        validateType(function.returnType(), where);
    }

    private void validateTypeParameterBound(final TypeParameterDescriptor parameter, final Token where) {
        if (parameter.bound() == null) return;
        if (!(parameter.bound() instanceof NominalDescriptor || parameter.bound() instanceof GenericDescriptor)) {
            Zeron.resolutionError(new ResolutionError(where, "A generic function bound must be a contract type."));
        }
        final var boundName = className(parameter.bound());
        final var contract = contracts.get(boundName);
        if (contract == null) {
            Zeron.resolutionError(new ResolutionError(where,
                    "Type parameter bound '" + boundName + "' is not a contract."));
        }
        ensureTypeAccessible(boundName, where, contract.isPublic());
        validateType(parameter.bound(), where);
    }

    private void validateType(final TypeDescriptor type, final Token where) {
        switch (type) {
            case NominalDescriptor nominal -> {
                validateStarTypeAmbiguity(nominal.name(), where);
                if (!types.contains(nominal.name())) {
                    final var javaClass = javaClassPath.find(nominal.name());
                    if (javaClass == null) {
                        Zeron.resolutionError(new ResolutionError(where,
                                "Unknown type '" + nominal.name() + "'."));
                    }
                    if (javaClass.hasGenericSignature()) {
                        Zeron.resolutionError(new ResolutionError(where,
                                "Generic Java classes are not supported by the current interop slice."));
                    }
                    break;
                }
                final var classDeclaration = classes.get(nominal.name());
                final var contractDeclaration = contracts.get(nominal.name());
                if (classDeclaration != null) {
                    ensureTypeAccessible(nominal.name(), where, classDeclaration.isPublic());
                }
                if (contractDeclaration != null) {
                    ensureTypeAccessible(nominal.name(), where, contractDeclaration.isPublic());
                }
                final var arity = classDeclaration != null ? classDeclaration.typeParameters().size()
                        : contractDeclaration != null ? contractDeclaration.typeParameters().size() : 0;
                if (arity != 0) {
                    Zeron.resolutionError(new ResolutionError(where,
                            "Type '" + nominal.name() + "' requires " + arity + " type arguments."));
                }
            }
            case ArrayDescriptor array -> validateType(array.elementType(), where);
            case NullableDescriptor nullable -> validateType(nullable.baseType(), where);
            case ReferenceDescriptor reference -> validateType(reference.baseType(), where);
            case FunctionDescriptor function -> validateFunctionTypes(function, where);
            case GenericDescriptor generic -> {
                final var name = generic.baseType().name();
                validateStarTypeAmbiguity(name, where);
                final var classDeclaration = classes.get(name);
                final var contractDeclaration = contracts.get(name);
                if (classDeclaration == null && contractDeclaration == null) {
                    if (javaClassPath.find(name) != null) {
                        Zeron.resolutionError(new ResolutionError(where,
                                "Generic Java classes are not supported by the current interop slice."));
                    }
                    Zeron.resolutionError(new ResolutionError(where, "Unknown generic type '" + name + "'."));
                }
                if (classDeclaration != null) {
                    ensureTypeAccessible(name, where, classDeclaration.isPublic());
                }
                if (contractDeclaration != null) {
                    ensureTypeAccessible(name, where, contractDeclaration.isPublic());
                }
                final var arity = classDeclaration != null ? classDeclaration.typeParameters().size()
                        : contractDeclaration.typeParameters().size();
                if (arity == 0 || arity != generic.typeParameters().size()) {
                    Zeron.resolutionError(new ResolutionError(where,
                            "Type '" + name + "' expects " + arity + " type arguments, found "
                                    + generic.typeParameters().size() + "."));
                }
                generic.typeParameters().forEach(parameter -> validateType(parameter, where));
            }
            default -> {}
        }
    }

    private TypeDescriptor classType(final Stmt.ClassDecl declaration) {
        if (declaration.typeParameters().isEmpty()) return TypeDescriptor.of(declaration.name().lexeme());
        return TypeDescriptor.genericOf(TypeDescriptor.ofName(declaration.name().lexeme()),
                declaration.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
    }

    private Map<TypeParameterDescriptor, TypeDescriptor> substitutionsFor(
            final List<TypeParameterDescriptor> parameters, final TypeDescriptor receiverType) {
        final var baseType = receiverType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : receiverType;
        if (!(baseType instanceof GenericDescriptor generic)
                || parameters.size() != generic.typeParameters().size()) return Map.of();
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < parameters.size(); i++) {
            substitutions.put(parameters.get(i), generic.typeParameters().get(i));
        }
        return substitutions;
    }

    private void resolve(final Stmt stmt) {
        switch (stmt) {
            case Stmt.ClassDecl declaration -> resolveClass(declaration);
            case Stmt.ContractDecl declaration -> resolveContract(declaration);
            case Stmt.Block(List<Stmt> statements) -> {
                beginScope();
                resolveStmts(statements);
                endScope();
            }
            case Stmt.Break(Token keyword) -> {
                if (loopDepth == 0) {
                    Zeron.resolutionError(new ResolutionError(keyword,
                            "Can only break inside of a loop."));
                }
                if (flowState.isReachable()) loopFlows.peek().breakStates.add(flowState.copy());
                flowState.markUnreachable();
            }
            case Stmt.Continue(Token keyword) -> {
                if (loopDepth == 0) {
                    Zeron.resolutionError(new ResolutionError(keyword,
                            "Can only continue inside of a loop."));
                }
                if (flowState.isReachable()) loopFlows.peek().continueStates.add(flowState.copy());
                flowState.markUnreachable();
            }
            case Stmt.Expression(Expr expression) -> {
                resolve(expression);
            }
            case Stmt.Function fn -> {
                validateFunctionTypes(fn.typeDescriptor(), fn.name());
                fn.typeDescriptor().typeParameters().forEach(
                        parameter -> validateTypeParameterBound(parameter, fn.name()));
                if (!functionNamesByDeclaration.containsKey(fn.name())) registerFunction(packageName, fn);
                resolveFunction(fn);
            }
            case Stmt.ExternalFunction externalFunction -> {
                validateFunctionTypes(externalFunction.typeDescriptor(), externalFunction.name());
                if (!functionNamesByDeclaration.containsKey(externalFunction.name())) {
                    registerFunction(packageName, externalFunction);
                }
            }
            case Stmt.For(Token iterationBind, Token _, Expr iterable, Stmt body) -> {
                beginScope();
                final var iterableType = resolve(iterable);
                final var elementType = ensureIterable(iterableType, iterationBind);
                iterationElementTypes.put(iterationBind, elementType);
                declare(SYNTHETIC_VAR, iterationBind, elementType, BindingMutability.IMMUTABLE);
                define(iterationBind);
                final var incoming = flowState.copy();
                var headerState = incoming.copy();
                LoopFlow stableLoopFlow;
                while (true) {
                    final var loopFlow = new LoopFlow();
                    loopFlows.push(loopFlow);
                    loopDepth++;
                    try {
                        flowState = headerState.copy();
                        resolve(body);
                    } finally {
                        loopDepth--;
                        loopFlows.pop();
                    }
                    final var backEdges = new ArrayList<>(loopFlow.continueStates);
                    if (flowState.isReachable()) backEdges.add(flowState.copy());
                    var nextHeader = incoming.copy();
                    for (final var backEdge : backEdges) {
                        nextHeader = FlowState.join(nextHeader, backEdge);
                    }
                    if (nextHeader.sameAs(headerState)) {
                        stableLoopFlow = loopFlow;
                        break;
                    }
                    headerState = nextHeader;
                }
                final var exits = incoming.copy();
                exits.remove(iterationBind);
                var exitState = exits;
                for (final var breakState : stableLoopFlow.breakStates) {
                    final var reachableBreak = breakState.copy();
                    reachableBreak.remove(iterationBind);
                    exitState = FlowState.join(exitState, reachableBreak);
                }
                flowState = exitState;
                endScope();
            }
            case Stmt.If(Token keyword, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                final var incoming = flowState.copy();
                final var conditionFlows = resolveCondition(condition, incoming, keyword);
                flowState = conditionFlows.whenTrue().copy();
                resolve(thenBranch);
                final var thenFlow = flowState.copy();

                final FlowState elseFlow;
                if (elseBranch == null) {
                    elseFlow = conditionFlows.whenFalse();
                } else {
                    flowState = conditionFlows.whenFalse().copy();
                    resolve(elseBranch);
                    elseFlow = flowState.copy();
                }

                flowState = FlowState.join(thenFlow, elseFlow);
            }
            case Stmt.Return(Expr value) -> {
                final var expectedReturnType = expectedReturnTypes.peek();
                final var returnType = value == null
                        ? TypeDescriptor.ofUnit()
                    : resolveArgument(value, expectedReturnType);
                if (!expectedReturnTypes.isEmpty()) {
                    ensureAssignable(expectedReturnTypes.peek(), returnType, SYNTHETIC_IDENTIFIER);
                }
                flowState.markUnreachable();
            }
            case Stmt.Var var -> {
                Zeron.debug("resolving variable       " + var.name().lexeme() + " " + var.type());
                if (!(var.type() instanceof InferDescriptor)) validateType(var.type(), var.name());

                declare(var, var.name(), var.type(), var.mutability());
                TypeDescriptor resolvedType = var.type();

                // let i: Int;
                if    (!(resolvedType instanceof InferDescriptor)
                    && var.initializer() == null
                    && !(resolvedType.isNullable())) {
                    Zeron.resolutionError(new ResolutionError(var.name(),
                            "A variable with no initializer must be of a nullable type."));
                }

                // let x = expression; OR let x: T = expression;
                if (var.initializer() != null) {
                    resolvedType = var.type() instanceof InferDescriptor
                            ? resolve(var.initializer())
                            : resolveArgument(var.initializer(), var.type());
                    if (var.type() instanceof InferDescriptor
                            && var.mutability() == BindingMutability.IMMUTABLE
                            && var.initializer() instanceof Expr.Lambda lambda
                            && resolvedType instanceof FunctionDescriptor functionType) {
                        final var generalized = generalizeLambda(lambda, functionType);
                        if (generalized != null) resolvedType = generalized;
                    }
                    if (var.mutability().isReassignable()
                            && resolvedType instanceof FunctionDescriptor functionType
                            && functionType.isGeneric()) {
                        Zeron.resolutionError(new ResolutionError(var.name(),
                                "Polymorphic function values require an immutable binding."));
                    }
                    resolvedType = ensureAssignable(var.type(), resolvedType, var.name());
                    if (var.type() instanceof InferDescriptor
                            && resolvedType instanceof NullDescriptor) {
                        Zeron.resolutionError(new ResolutionError(var.name(),
                                "Cannot infer a type from a null value; add an explicit nullable type annotation."));
                    }
                    // replaces <infer> with resolved type for the symbol
                    if (var.type() instanceof InferDescriptor)
                        symbols.setResolvedType(var.name(), resolvedType);
                }

                // if initializer ends up as <infer>, it means expectedType was <infer> as well
                if (resolvedType instanceof InferDescriptor) {
                    Zeron.resolutionError(new ResolutionError(var.name(),
                            "Cannot infer type from declaration."));
                }

                if (!(var.type() instanceof InferDescriptor)) {
                    Zeron.debug(" resolved variable " + var.name().lexeme() + " "
                            + var.type() + " from explicit type");
                } else {
                    Zeron.debug(" resolved variable " + var.name().lexeme() + " "
                            + resolvedType + " from initializer");
                }

                define(var.name());
            }
            case Stmt.While(Token keyword, Expr condition, Stmt body) -> {
                final var incoming = flowState.copy();
                var headerState = incoming.copy();
                ConditionFlows conditionFlows;
                LoopFlow stableLoopFlow;
                while (true) {
                    final var loopFlow = new LoopFlow();
                    loopFlows.push(loopFlow);
                    loopDepth++;
                    try {
                        conditionFlows = resolveCondition(condition, headerState, keyword);
                        flowState = conditionFlows.whenTrue().copy();
                        resolve(body);
                    } finally {
                        loopDepth--;
                        loopFlows.pop();
                    }
                    final var backEdges = new ArrayList<>(loopFlow.continueStates);
                    if (flowState.isReachable()) backEdges.add(flowState.copy());
                    var nextHeader = incoming.copy();
                    for (final var backEdge : backEdges) {
                        nextHeader = FlowState.join(nextHeader, backEdge);
                    }
                    if (nextHeader.sameAs(headerState)) {
                        stableLoopFlow = loopFlow;
                        break;
                    }
                    headerState = nextHeader;
                }
                final var normalExit = conditionFlows.whenFalse().copy();
                var exitState = normalExit;
                for (final var breakState : stableLoopFlow.breakStates) {
                    final var reachableBreak = breakState.copy();
                    exitState = FlowState.join(exitState, reachableBreak);
                }
                flowState = exitState;
            }
        }
    }

    public void resolveStmts(final List<Stmt> statements) {
        for (final var statement : statements) {
            resolve(statement);
        }
    }

    private void ensureImmutableCaptures(final Expr.Lambda lambda) {
        new LambdaCaptureValidator(symbols).validate(lambda);
    }

    private TypeDescriptor resolve(Expr expr) {
        return switch (expr) {
            case Expr.MemberCall call -> resolveMemberCall(call);
            case Expr.PropertyAssignment assignment -> {
                if (assignment.property.safeNavigation()) {
                    Zeron.resolutionError(new ResolutionError(assignment.property.name,
                            "Safe navigation cannot be used for property assignment."));
                }
                resolveProperty(assignment.property);
                final var receiverType = assignment.property.receiver.getType();
                final var ownerName = className(receiverType);
                if (!(receiverType instanceof ReferenceDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(assignment.property.name,
                            "Property assignment requires a mutable reference."));
                }
                final var owner = classes.get(ownerName);
                final var propertyDeclaration = findProperty(ownerName, assignment.property.name);
                final TypeDescriptor expectedType;
                if (propertyDeclaration != null) {
                    if (!propertyDeclaration.isMutating()) {
                        Zeron.resolutionError(new ResolutionError(assignment.property.name,
                                "Property is read-only."));
                    }
                    expectedType = resolvedPropertyType(ownerName, propertyDeclaration, receiverType);
                } else {
                    final var field = findField(ownerName, assignment.property.name);
                    expectedType = owner == null ? field.type()
                            : TypeSubstitution.substitute(field.type(),
                                    substitutionsFor(owner.typeParameters(), receiverType));
                }
                ensureAssignable(expectedType,
                    resolveArgument(assignment.value, expectedType),
                    assignment.property.name);
                assignment.setType(TypeDescriptor.ofUnit());
                yield TypeDescriptor.ofUnit();
            }
            case Expr.PropertyCompoundAssignment assignment -> {
                resolveProperty(assignment.property);
                final var receiverType = assignment.property.receiver.getType();
                if (!(receiverType instanceof ReferenceDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(assignment.property.name,
                            "Property compound assignment requires a mutable reference."));
                }
                final var ownerName = className(receiverType);
                final var property = findProperty(ownerName, assignment.property.name);
                if (property == null || !property.isMutating()) {
                    Zeron.resolutionError(new ResolutionError(assignment.property.name,
                            "Compound assignment requires a writable property."));
                }
                final var operation = new Expr.Binary(assignment.property,
                        assignment.operator, assignment.value, TypeDescriptor.ofInfer());
                final var valueType = resolve(operation);
                assignment.setResolvedOperation(operation);
                ensureAssignable(assignment.property.getType(), valueType, assignment.property.name);
                assignment.setType(TypeDescriptor.ofUnit());
                yield TypeDescriptor.ofUnit();
            }
            case Expr.Property property -> resolveProperty(property);
            case Expr.ArrayLiteral literal -> {
                final var elementTypes = new ArrayList<TypeDescriptor>();
                var elementType = (TypeDescriptor) null;
                var hasNullElement = false;
                for (final var element : literal.elements) {
                    final var resolvedElement = resolve(element);
                    elementTypes.add(resolvedElement);
                    if (resolvedElement instanceof NullDescriptor) {
                        hasNullElement = true;
                    } else if (elementType == null) {
                        elementType = resolvedElement;
                    } else if (elementType instanceof NullableDescriptor nullable
                            && nullable.baseType().equals(resolvedElement)) {
                        continue;
                    } else if (resolvedElement instanceof NullableDescriptor nullable
                            && nullable.baseType().equals(elementType)) {
                        elementType = resolvedElement;
                    } else {
                        ensureExact(SYNTHETIC_IDENTIFIER, elementType, resolvedElement);
                    }
                }
                if (elementType == null) {
                    Zeron.resolutionError(new ResolutionError(SYNTHETIC_IDENTIFIER,
                            "Cannot infer an array element type from null values."));
                }
                if (hasNullElement) elementType = elementType.toNullable();
                final var operation = resolveIntrinsic(IntrinsicId.ARRAY_LITERAL,
                        List.of(elementType), elementTypes, SYNTHETIC_IDENTIFIER);
                literal.setIntrinsicOperation(operation);
                literal.setType(operation.resultType());
                yield operation.resultType();
            }
            case Expr.Index index -> {
                final var receiverType = resolve(index.array);
                final var arrayType = resolveArrayType(receiverType, SYNTHETIC_IDENTIFIER);
                final var indexType = resolve(index.index);
                final var operation = resolveIntrinsic(IntrinsicId.ARRAY_READ,
                        List.of(arrayType.elementType()), List.of(receiverType, indexType), SYNTHETIC_IDENTIFIER);
                index.setIntrinsicOperation(operation);
                index.setType(operation.resultType());
                yield operation.resultType();
            }
            case Expr.IndexAssignment assignment -> {
                final var receiverType = resolve(assignment.array);
                if (!(receiverType instanceof ReferenceDescriptor reference)
                        || !(reference.baseType() instanceof ArrayDescriptor arrayType)) {
                    Zeron.resolutionError(new ResolutionError(SYNTHETIC_IDENTIFIER,
                            "Array slot assignment requires a mutable &Array<T> view."));
                    yield TypeDescriptor.ofUnit();
                }
                    final var indexType = resolve(assignment.index);
                    final var valueType = resolve(assignment.value);
                    final var operation = resolveIntrinsic(IntrinsicId.ARRAY_WRITE,
                        List.of(arrayType.elementType()), List.of(receiverType, indexType, valueType),
                        SYNTHETIC_IDENTIFIER);
                    assignment.setIntrinsicOperation(operation);
                    assignment.setType(operation.resultType());
                    yield operation.resultType();
            }
            // |> a = expr ::= when
            //               | assignable (typeof a, typeof expr) -> typeof expr
            //               | else                               -> ResolutionError
            // suggested type for assignment will always be inferred,
            // resolve the expression, ensure it's assignable
            // return the assigned type (the resolved one)
            case Expr.Assignment assignment -> {
                final var binding = symbols.getSymbol(assignment.name);
                if (!binding.mutability().isReassignable()) {
                    Zeron.resolutionError(new ResolutionError(assignment.name,
                            "Cannot reassign immutable binding '" + assignment.name.lexeme() + "'."));
                }
                final var expectedType = binding.type();
                final var resolvedType = resolveArgument(assignment.value, expectedType);
                ensureAssignable(expectedType, resolvedType, assignment.name);

                if (flowState.isReachable()) {
                    flowState.remove(binding.name());
                    for (final var writeScope : flowWriteScopes) writeScope.add(binding.name());
                }
                symbols.define(assignment.name);
                assignment.setType(expectedType);
                yield expectedType;
            }
            case Expr.CoalesceAssignment assignment -> resolveCoalesceAssignment(assignment);
            // |> a + b ::= when predicate x is Infer, TypeParam
            //            | predicate a && not predicate b -> typeof b
            //            | predicate b && not predicate a -> typeof a
            //            | else                           -> ResolutionError
            // suggested type for binary will always be inferred,
            // resolve left and right, ensure types are exact and
            // return the expression tagged with the resolved type
            case Expr.Binary binary -> {
                if (binary.operator.type() == TokenType.EQUAL_EQUAL_EQUAL) {
                    final var leftType = resolve(binary.left);
                    final var rightType = resolve(binary.right);
                    final var leftIsNull = leftType instanceof NullDescriptor;
                    final var rightIsNull = rightType instanceof NullDescriptor;
                    if (leftIsNull && rightIsNull
                            || leftIsNull && !isIdentityComparable(rightType)
                            || rightIsNull && !isIdentityComparable(leftType)) {
                        Zeron.resolutionError(new ResolutionError(binary.operator,
                                "'===' requires reference-valued operands; nullable primitives and Unit "
                                        + "are not supported."));
                    }
                    if (!leftIsNull && !rightIsNull) {
                        if (!isIdentityComparable(leftType) || !isIdentityComparable(rightType)) {
                            Zeron.resolutionError(new ResolutionError(binary.operator,
                                    "'===' requires reference-valued operands; nullable primitives and Unit "
                                            + "are not supported."));
                        }
                        final var leftView = identityViewType(leftType);
                        final var rightView = identityViewType(rightType);
                        if (!typeCompatibility.canAssign(leftView, rightView)
                                && !typeCompatibility.canAssign(rightView, leftView)) {
                            Zeron.resolutionError(new ResolutionError(binary.operator,
                                    "'===' operands must have compatible reference types."));
                        }
                    }
                    binary.setType(TypeDescriptor.ofBoolean());
                    yield TypeDescriptor.ofBoolean();
                }
                if ((binary.operator.type() == TokenType.EQUAL_EQUAL
                        || binary.operator.type() == TokenType.BANG_EQUAL)
                        && (isNullLiteral(binary.left) || isNullLiteral(binary.right))) {
                    final var leftType = resolve(binary.left);
                    final var rightType = resolve(binary.right);
                    final var comparedType = isNullLiteral(binary.left) ? rightType : leftType;
                    if (!isNullLiteral(binary.left) && !isNullLiteral(binary.right)
                            || isPrimitive(comparedType) && !comparedType.isNullable()) {
                        Zeron.resolutionError(new ResolutionError(binary.operator,
                                "Null comparisons require a nullable or reference value."));
                    }
                    binary.setType(TypeDescriptor.ofBoolean());
                    yield TypeDescriptor.ofBoolean();
                }
                final var leftType = resolve(binary.left);
                final var rightType = resolve(binary.right);
                final var refinedLeftType = refineInferredType(binary.left, leftType, rightType);
                final var refinedRightType = refineInferredType(binary.right, rightType, leftType);
                Zeron.debug("resolving binary   " + refinedLeftType + " "
                    + binary.operator.lexeme() + " " + refinedRightType);
                if (TypeSubstitution.containsTypeParameter(refinedLeftType)
                    || TypeSubstitution.containsTypeParameter(refinedRightType)) {
                    Zeron.resolutionError(new ResolutionError(binary.operator,
                        "Operators on generic type parameters require constraints, which are not supported."));
                }
                if (refinedLeftType.isNullable() || refinedRightType.isNullable()
                    || refinedLeftType instanceof NullDescriptor || refinedRightType instanceof NullDescriptor) {
                    Zeron.resolutionError(new ResolutionError(binary.operator,
                        "Nullable operands require a null check before using this operator."));
                }
                ensureExact(binary.operator, refinedLeftType, refinedRightType);
                final var isComparison = switch (binary.operator.type()) {
                    case EQUAL_EQUAL, BANG_EQUAL, GREATER, GREATER_EQUAL, LESS, LESS_EQUAL -> true;
                    default -> false;
                };
                if ((binary.operator.type() == TokenType.GREATER || binary.operator.type() == TokenType.GREATER_EQUAL
                    || binary.operator.type() == TokenType.LESS || binary.operator.type() == TokenType.LESS_EQUAL)
                    && !(refinedLeftType instanceof IntDescriptor
                    || refinedLeftType instanceof FloatDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(binary.operator,
                        "Relational comparisons require numeric operands."));
                }
                if (!isComparison
                    && !(refinedLeftType instanceof InferDescriptor)
                    && !(refinedRightType instanceof InferDescriptor)) {
                    final var validOperands = switch (binary.operator.type()) {
                    case PLUS -> refinedLeftType instanceof IntDescriptor
                        || refinedLeftType instanceof FloatDescriptor
                        || refinedLeftType instanceof StringDescriptor;
                    case MINUS, STAR, SLASH, PERCENT -> refinedLeftType instanceof IntDescriptor
                        || refinedLeftType instanceof FloatDescriptor;
                    case AMPERSAND, PIPE, CARET, SHIFT_LEFT, SHIFT_RIGHT, UNSIGNED_SHIFT_RIGHT ->
                        refinedLeftType instanceof IntDescriptor;
                    default -> false;
                    };
                    if (!validOperands) {
                    Zeron.resolutionError(new ResolutionError(binary.operator,
                        switch (binary.operator.type()) {
                            case AMPERSAND, PIPE, CARET, SHIFT_LEFT, SHIFT_RIGHT, UNSIGNED_SHIFT_RIGHT ->
                                "Bitwise operators require Int operands.";
                            default -> "Arithmetic operators require numeric operands, except String concatenation with '+'.";
                        }));
                    }
                }
                final var resolvedType = isComparison
                    ? TypeDescriptor.ofBoolean()
                    : refinedLeftType.orElse(refinedRightType);

                binary.setType(resolvedType);
                yield resolvedType;
            }
            // |> a(b, c) ::= match return_type a is not Infer
            //              | and ensure_args (a b c) -> return_type a
            //              | else -> resolve_with_types (a typeof b typeof c)
            // suggested type for call will always be inferred,
            // resolve the arguments, make sure they align with
            // the parameters and
            // return the expression tagged with the resolved type
            case Expr.Call call -> {
                // must disambiguate call between lambda (variable) and function (global)
                FunctionDescriptor descriptor;
                // check locally first, since lambdas shadow functions
                if (symbols.containsSymbol(call.callee)) {
                    final var symbol = getSymbol(call.callee);
                    if (symbol instanceof FunctionDescriptor f) {
                        descriptor = f;
                    } else if (symbol instanceof ReferenceDescriptor reference
                            && reference.baseType() instanceof FunctionDescriptor f) {
                        descriptor = f;
                    } else {
                        Zeron.resolutionError(new ResolutionError(call.callee,
                                "Callee is not a function."));
                        yield null;
                    }
                } else {
                    final var functionName = resolveFunctionName(call.callee.lexeme(), call.callee);
                    final var functionToken = functionName == null ? null : functionSymbolTokens.get(functionName);
                    if (functionToken == null) {
                        if (currentMethodOwner == null) {
                            Zeron.resolutionError(new ResolutionError(call.callee,
                                    "Unknown function '" + call.callee.lexeme() + "'."));
                        }
                        final var receiver = new Expr.Variable(
                                new Token(TokenType.THIS, "this", null, call.callee.line()),
                                TypeDescriptor.ofInfer());
                        final var implicitCall = new Expr.MemberCall(receiver, call.callee, call.paren,
                                call.arguments, call.explicitTypeArguments, TypeDescriptor.ofInfer());
                        call.setImplicitMemberCall(implicitCall);
                        final var resultType = resolveMemberCall(implicitCall);
                        call.setType(resultType);
                        yield resultType;
                    }
                    call.setResolvedFunctionName(functionName);
                    descriptor = (FunctionDescriptor) symbols.getFunction(functionToken).type();
                }
                if (descriptor.isGeneric()) {
                    final var resultType = resolveGenericCall(call, descriptor);
                    if ("zeron.collections.allocateArray".equals(call.resolvedFunctionName())) {
                        final var arrayElement = arrayElementType(call.getType(), call.callee);
                        call.setIntrinsicOperation(resolveIntrinsic(IntrinsicId.ARRAY_ALLOC,
                                List.of(arrayElement),
                                call.arguments.stream().map(Expr::getType).toList(), call.callee));
                    } else if ("zeron.collections.clearArraySlot".equals(call.resolvedFunctionName())) {
                        final var arrayElement = arrayElementType(call.arguments.getFirst().getType(), call.callee);
                        call.setIntrinsicOperation(resolveIntrinsic(IntrinsicId.ARRAY_CLEAR_SLOT,
                                List.of(arrayElement),
                                call.arguments.stream().map(Expr::getType).toList(), call.callee));
                    } else if ("zeron.collections.unwrapSome".equals(call.resolvedFunctionName())) {
                        var optionType = call.arguments.getFirst().getType();
                        if (optionType instanceof ReferenceDescriptor reference) {
                            optionType = reference.baseType();
                        }
                        final var optionValue = ((GenericDescriptor) optionType).typeParameters().getFirst();
                        call.setIntrinsicOperation(resolveIntrinsic(IntrinsicId.OPTION_UNWRAP_SOME,
                                List.of(optionValue),
                                call.arguments.stream().map(Expr::getType).toList(), call.callee));
                    }
                    yield resultType;
                }

                if (!call.explicitTypeArguments.isEmpty()) {
                    Zeron.resolutionError(new ResolutionError(call.callee,
                            "This function does not declare type parameters."));
                }
                var parameters = descriptor.parameters();
                Zeron.debug("resolving call              " + call.callee.lexeme() + parameters
                    + " -> " + descriptor.returnType());

                // if arities differ, there were too many args
                if (descriptor.arity() != call.arguments.size()) {
                    Zeron.resolutionError(new ResolutionError(call.callee,
                            "Expected " + descriptor.arity() + " arguments, found " + call.arguments.size()));
                }

                // if a lambda return type is inferred,
                // parameters are generic
                if (descriptor.returnType() instanceof InferDescriptor) {
                    descriptor = resolveCallWithTypes(call.callee, call.arguments);
                } else {
                    for (var i = 0; i < call.arguments.size(); i++) {
                        final var argument = call.arguments.get(i);
                        final var expected = parameters.get(i);
                        final var resolved = resolveArgument(argument, expected);
                        ensureAssignable(expected, resolved, call.callee);
                    }
                }

                call.setType(descriptor.returnType());
                yield descriptor.returnType();
            }
            // |> (a) ::= typeof a
            // suggested type for groupings will always be inferred,
            // just unbox and send the expression down the resolution pipeline
            case Expr.Grouping grouping -> {
                final var resolvedType = resolve(grouping.expression);
                grouping.setType(resolvedType);
                yield resolvedType;
            }
            // suggested type for if expressions will always be inferred,
            // resolve the condition, ensure it is a boolean,
            // resolve each branch, ensure they have a common parent, and
            // return the expression tagged with the resolved type
            case Expr.If iff -> {
                final var incoming = flowState.copy();
                final var conditionFlows = resolveCondition(iff.condition, incoming, iff.paren);
                flowState = conditionFlows.whenTrue().copy();
                final var thenType = resolve(iff.thenExpr);
                final var thenFlow = flowState.copy();
                flowState = conditionFlows.whenFalse().copy();
                final var elseType = resolve(iff.elseExpr);
                final var elseFlow = flowState.copy();
                final var commonType = ensureCommonParent(iff.paren, thenType, elseType);
                flowState = FlowState.join(thenFlow, elseFlow);
                iff.setType(commonType);
                yield commonType;
            }
            case Expr.Coalesce coalesce -> {
                final var leftType = resolve(coalesce.left);
                final var afterLeft = flowState.copy();
                if (!(leftType instanceof NullableDescriptor) && !(leftType instanceof NullDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(coalesce.operator,
                            "The left operand of '??' must be nullable."));
                }

                final var nonNullType = leftType instanceof NullableDescriptor nullable
                        ? nullable.baseType()
                        : TypeDescriptor.ofInfer();
                coalesce.setLeftNonNullType(nonNullType);
                final var nonNullFlow = leftType instanceof NullDescriptor
                        ? FlowState.unreachable()
                        : afterLeft.copy();
                final var nullFlow = afterLeft.copy();
                if (!(leftType instanceof NullDescriptor)) {
                    final var variable = directVariable(coalesce.left);
                    if (variable != null && isRefinable(variable.name)) {
                        final var binding = symbols.getSymbol(variable.name);
                        final var existingFact = afterLeft.get(binding.name());
                        final var nonNullFact = existingFact == null
                                ? nonNullFact(binding.type())
                                : new FlowFact(false, existingFact.nonNullAlternatives());
                        nonNullFlow.put(binding.name(), nonNullFact);
                        nullFlow.put(binding.name(), new FlowFact(true, Set.of()));
                    }
                }

                flowState = nullFlow;
                final var fallbackType = resolve(coalesce.right);
                final var fallbackFlow = flowState.copy();
                final TypeDescriptor resultType;
                if (leftType instanceof NullDescriptor) {
                    if (fallbackType instanceof NullDescriptor) {
                        Zeron.resolutionError(new ResolutionError(coalesce.operator,
                                "The result type of 'null ?? null' cannot be inferred."));
                    }
                    resultType = fallbackType;
                } else {
                    resultType = ensureCommonParent(coalesce.operator, nonNullType, fallbackType);
                }

                flowState = FlowState.join(nonNullFlow, fallbackFlow);
                coalesce.setType(resultType);
                yield resultType;
            }
            // suggested type for lambdas will always be inferred,
            // but they need to be structurally inferred. we can extract
            // arity from the parameter count and infer a return type from
            // the body.
            case Expr.Lambda lambda -> {
                ensureImmutableCaptures(lambda);
                final var resolvedType = inferLambdaType(lambda);
                lambda.setType(resolvedType);
                yield resolvedType;
            }
            case Expr.Literal literal ->
                    literal.getType();
            case Expr.TypeTest test -> {
                yield resolveTypeTest(test);
            }
            case Expr.Cast cast -> {
                final var sourceType = resolve(cast.value);
                validateRuntimeTestTarget(cast.targetType, cast.operator);
                if (!typeCompatibility.canTypeTest(sourceType, cast.targetType)) {
                    Zeron.resolutionError(new ResolutionError(cast.operator,
                            "Cast is impossible between " + sourceType + " and " + cast.targetType + "."));
                }
                if (!cast.safe && (sourceType.isNullable() || sourceType instanceof NullDescriptor)) {
                    Zeron.resolutionError(new ResolutionError(cast.operator,
                            "A checked cast from a nullable value requires a non-null flow proof."));
                }
                final var resultType = cast.safe ? cast.targetType.toNullable() : cast.targetType;
                cast.setType(resultType);
                yield resultType;
            }
            case Expr.Logical logical -> {
                final var incoming = flowState.copy();
                final Set<Token> writes = Collections.newSetFromMap(new IdentityHashMap<>());
                flowWriteScopes.push(writes);
                try {
                    resolveCondition(logical, incoming, logical.operator);
                } finally {
                    flowWriteScopes.pop();
                }
                flowState = incoming;
                for (final var written : writes) flowState.remove(written);
                logical.setType(TypeDescriptor.ofBoolean());
                yield TypeDescriptor.ofBoolean();
            }
            case Expr.Unary unary -> {
                final var operandType = resolve(unary.right);
                if (TypeSubstitution.containsTypeParameter(operandType)) {
                    Zeron.resolutionError(new ResolutionError(unary.operator,
                            "Unary operators on generic type parameters require constraints, which are not supported."));
                }
                if (unary.operator.type() == TokenType.NOT) {
                    ensureBoolean(operandType, unary.operator);
                    unary.setType(TypeDescriptor.ofBoolean());
                    yield TypeDescriptor.ofBoolean();
                }
                if (unary.operator.type() == TokenType.TILDE) {
                    if (!(operandType instanceof IntDescriptor)) {
                        Zeron.resolutionError(new ResolutionError(unary.operator,
                                "Bitwise complement requires an Int operand."));
                    }
                    unary.setType(TypeDescriptor.ofInt());
                    yield TypeDescriptor.ofInt();
                }
                if (unary.operator.type() != TokenType.MINUS
                        && unary.operator.type() != TokenType.PLUS) {
                    Zeron.resolutionError(new ResolutionError(unary.operator,
                            "The 'typeof' operator is not implemented."));
                }
                if (operandType instanceof IntDescriptor || operandType instanceof FloatDescriptor) {
                    unary.setType(operandType);
                    yield operandType;
                }
                Zeron.resolutionError(new ResolutionError(unary.operator,
                        "Unary '+' and '-' require a non-null Int or Float operand."));
                yield TypeDescriptor.ofInfer();
            }
            case Expr.Variable variable -> {
                if (variable.resolvedFunctionName() != null) {
                    yield variable.specializedFunctionType();
                }
                if (!variable.explicitFunctionTypeArguments.isEmpty()) {
                    yield resolveFunctionReference(variable, null);
                }
                final var name = variable.name;
                if (initializerVisibleFields != null && name.type() != TokenType.THIS) {
                    final var fieldType = resolveImplicitFieldRead(variable);
                    if (fieldType == null) {
                        Zeron.resolutionError(new ResolutionError(name,
                                "A field initializer may read only fields declared earlier."));
                    }
                    yield fieldType;
                }
                if (!symbols.containsSymbol(name)) {
                    final var functionName = resolveFunctionName(name.lexeme(), name);
                    if (functionName != null) {
                        final var functionType = (FunctionDescriptor) symbols
                                .getFunction(functionSymbolTokens.get(functionName)).type();
                        if (functionType.isGeneric()) {
                            Zeron.resolutionError(new ResolutionError(name,
                                    "A generic function value needs explicit type arguments or an expected function type."));
                        }
                        yield resolveFunctionReference(variable, null);
                    }
                    final var implicitFieldType = resolveImplicitFieldRead(variable);
                    if (implicitFieldType != null) yield implicitFieldType;
                }
                Zeron.debug("resolving variable lookup   " + name.lexeme());
                if (symbols.containsSymbol(name) && !symbols.getSymbol(name).isInit()) {
                    Zeron.resolutionError(new ResolutionError(name,
                            "Can't read local variable in its own initializer."));
                }

                final var resolvedType = effectiveType(symbols.getSymbol(name));
                variable.setType(resolvedType);
                Zeron.debug(" -> " + resolvedType);
                yield resolvedType;
            }
        };
    }

    private TypeDescriptor resolveTypeTest(final Expr.TypeTest test) {
        final var sourceType = resolve(test.value);
        validateRuntimeTestTarget(test.targetType, test.operator);
        if (!typeCompatibility.canTypeTest(sourceType, test.targetType)) {
            Zeron.resolutionError(new ResolutionError(test.operator,
                    "Type test is impossible between " + sourceType + " and " + test.targetType + "."));
        }

        test.setType(TypeDescriptor.ofBoolean());
        return TypeDescriptor.ofBoolean();
    }

    private TypeDescriptor resolveCoalesceAssignment(final Expr.CoalesceAssignment assignment) {
        final var binding = symbols.getSymbol(assignment.name);
        if (!binding.mutability().isReassignable()) {
            Zeron.resolutionError(new ResolutionError(assignment.name,
                    "Cannot reassign immutable binding '" + assignment.name.lexeme() + "'."));
        }
        if (binding.lvt() == SymbolTable.GLOBAL) {
            Zeron.resolutionError(new ResolutionError(assignment.name,
                    "'??=' is currently supported only for mutable local bindings."));
        }
        if (!(binding.type() instanceof NullableDescriptor)) {
            Zeron.resolutionError(new ResolutionError(assignment.name,
                    "The target of '??=' must have a nullable declared type."));
        }

        final var incoming = flowState.copy();
        final var currentType = effectiveType(binding);
        final var canBeNonNull = !(currentType instanceof NullDescriptor);
        final var canBeNull = currentType instanceof NullableDescriptor
                || currentType instanceof NullDescriptor;
        final var nonNullFlow = canBeNonNull ? incoming.copy() : FlowState.unreachable();
        final var nullFlow = canBeNull ? incoming.copy() : FlowState.unreachable();
        if (canBeNonNull && canBeNull) {
            final var existingFact = incoming.get(binding.name());
            nonNullFlow.put(binding.name(), existingFact == null
                    ? nonNullFact(binding.type())
                    : new FlowFact(false, existingFact.nonNullAlternatives()));
        }
        if (canBeNull) {
            nullFlow.put(binding.name(), new FlowFact(true, Set.of()));
        }

        flowState = nullFlow.copy();
        final var valueType = resolveArgument(assignment.value, binding.type());
        ensureAssignable(binding.type(), valueType, assignment.name);
        if (flowState.isReachable()) {
            if (valueType instanceof NullDescriptor) {
                flowState.put(binding.name(), new FlowFact(true, Set.of()));
            } else if (!(valueType instanceof NullableDescriptor)) {
                flowState.put(binding.name(), nonNullFact(binding.type()));
            } else {
                flowState.remove(binding.name());
            }
            for (final var writeScope : flowWriteScopes) writeScope.add(binding.name());
        }
        flowState = FlowState.join(nonNullFlow, flowState);
        symbols.define(assignment.name);
        assignment.setType(binding.type());
        return binding.type();
    }

    private void validateRuntimeTestTarget(final TypeDescriptor targetType, final Token where) {
        validateType(targetType, where);
        if (targetType instanceof NullableDescriptor || targetType instanceof ReferenceDescriptor
                || targetType instanceof ArrayDescriptor || targetType instanceof GenericDescriptor
                || targetType instanceof FunctionDescriptor || targetType instanceof TypeParameterDescriptor
                || targetType instanceof NullDescriptor || targetType instanceof NeverDescriptor
                || targetType instanceof InferDescriptor) {
            Zeron.resolutionError(new ResolutionError(where,
                    "This type cannot be used as a runtime type-test target."));
        }
    }

    private ConditionFlows resolveCondition(final Expr expression,
                                           final FlowState incoming,
                                           final Token where) {
        final var enclosingFlow = flowState;
        try {
            if (expression instanceof Expr.Literal literal && literal.value instanceof Boolean value) {
                final var whenTrue = incoming.copy();
                final var whenFalse = incoming.copy();
                if (value) whenFalse.markUnreachable();
                else whenTrue.markUnreachable();
                return new ConditionFlows(whenTrue, whenFalse);
            }
            if (expression instanceof Expr.Grouping grouping) {
                final var result = resolveCondition(grouping.expression, incoming, where);
                grouping.setType(TypeDescriptor.ofBoolean());
                return result;
            }
            if (expression instanceof Expr.Unary unary
                    && unary.operator.type() == TokenType.NOT) {
                final var result = resolveCondition(unary.right, incoming, unary.operator);
                unary.setType(TypeDescriptor.ofBoolean());
                return new ConditionFlows(result.whenFalse(), result.whenTrue());
            }
            if (expression instanceof Expr.Logical logical) {
                final var left = resolveCondition(logical.left, incoming, logical.operator);
                final ConditionFlows result;
                if (logical.operator.type() == TokenType.AND) {
                    final var right = resolveCondition(logical.right, left.whenTrue(), logical.operator);
                    result = new ConditionFlows(right.whenTrue(),
                            FlowState.join(left.whenFalse(), right.whenFalse()));
                } else {
                    final var right = resolveCondition(logical.right, left.whenFalse(), logical.operator);
                    result = new ConditionFlows(FlowState.join(left.whenTrue(), right.whenTrue()),
                            right.whenFalse());
                }
                logical.setType(TypeDescriptor.ofBoolean());
                return result;
            }

            flowState = incoming.copy();
            ensureBoolean(resolve(expression), where);
            final var evaluated = flowState.copy();
            final var whenTrue = evaluated.copy();
            final var whenFalse = evaluated.copy();

            if (expression instanceof Expr.TypeTest test) {
                final var variable = directVariable(test.value);
                if (variable != null && isRefinable(variable.name)) {
                    whenTrue.put(symbols.getSymbol(variable.name).name(),
                            refineTypeFact(symbols.getSymbol(variable.name).type(),
                                    evaluated.get(symbols.getSymbol(variable.name).name()),
                                    test.targetType));
                }
            } else if (expression instanceof Expr.Binary binary
                    && isNullComparison(binary)) {
                final var checked = directVariable(isNullLiteral(binary.left) ? binary.right : binary.left);
                if (checked != null && isRefinable(checked.name)) {
                    final var binding = symbols.getSymbol(checked.name);
                    final var nullFact = new FlowFact(true, Set.of());
                    final var nonNullFact = nonNullFact(binding.type());
                    final var equalsNull = binary.operator.type() == TokenType.EQUAL_EQUAL
                            || binary.operator.type() == TokenType.EQUAL_EQUAL_EQUAL;
                    whenTrue.put(binding.name(), equalsNull ? nullFact : nonNullFact);
                    whenFalse.put(binding.name(), equalsNull ? nonNullFact : nullFact);
                }
            }
            return new ConditionFlows(whenTrue, whenFalse);
        } finally {
            flowState = enclosingFlow;
        }
    }

    private FlowFact refineTypeFact(final TypeDescriptor declaredType,
                                   final FlowFact existing,
                                   final TypeDescriptor targetType) {
        final Set<TypeDescriptor> currentAlternatives;
        if (existing != null) {
            currentAlternatives = existing.nonNullAlternatives();
        } else {
            currentAlternatives = initialNonNullAlternatives(declaredType);
        }
        if (currentAlternatives == null) return new FlowFact(false, Set.of(targetType));

        final var narrowed = new LinkedHashSet<TypeDescriptor>();
        for (final var alternative : currentAlternatives) {
            if (typeCompatibility.canAssign(targetType, alternative)) {
                narrowed.add(alternative);
            } else if (typeCompatibility.canAssign(alternative, targetType)) {
                narrowed.add(targetType);
            }
        }
        if (narrowed.isEmpty()) narrowed.add(targetType);
        return new FlowFact(false, narrowed);
    }

    private FlowFact nonNullFact(final TypeDescriptor declaredType) {
        final var alternatives = initialNonNullAlternatives(declaredType);
        return new FlowFact(false, alternatives);
    }

    private Set<TypeDescriptor> initialNonNullAlternatives(final TypeDescriptor declaredType) {
        var baseType = declaredType instanceof NullableDescriptor nullable
                ? nullable.baseType()
                : declaredType;
        if (baseType instanceof ReferenceDescriptor reference) baseType = reference.baseType();
        if (baseType instanceof AnyDescriptor) return null;
        return Set.of(baseType);
    }

    private TypeDescriptor effectiveType(final Bind binding) {
        final var fact = flowState.get(binding.name());
        if (fact == null) return binding.type();
        if (fact.nonNullAlternatives() != null && fact.mayBeNull()
            && fact.nonNullAlternatives().isEmpty()) return TypeDescriptor.ofNull();
        if (fact.nonNullAlternatives() == null) {
            if (!fact.mayBeNull() && binding.type() instanceof NullableDescriptor nullable) {
                return nullable.baseType();
            }
            return binding.type();
        }
        if (fact.nonNullAlternatives().isEmpty()) return TypeDescriptor.ofNever();

        final var commonType = typeCompatibility.commonTypeForAlternatives(fact.nonNullAlternatives());
        final var declaredBase = binding.type() instanceof NullableDescriptor nullable
            ? nullable.baseType()
            : binding.type();
        final var effectiveBase = declaredBase instanceof ReferenceDescriptor
            ? new ReferenceDescriptor(commonType)
            : commonType;
        return fact.mayBeNull() ? effectiveBase.toNullable() : effectiveBase;
    }

    private boolean isRefinable(final Token name) {
        return symbols.containsSymbol(name)
                && symbols.getSymbol(name).lvt() != SymbolTable.GLOBAL;
    }

    private Expr.Variable directVariable(final Expr expression) {
        return switch (expression) {
            case Expr.Variable variable -> variable;
            case Expr.Grouping grouping -> directVariable(grouping.expression);
            default -> null;
        };
    }

    private boolean isNullLiteral(final Expr expression) {
        return switch (expression) {
            case Expr.Literal literal -> literal.value == null;
            case Expr.Grouping grouping -> isNullLiteral(grouping.expression);
            default -> false;
        };
    }

    private boolean isNullComparison(final Expr.Binary binary) {
        return (binary.operator.type() == TokenType.EQUAL_EQUAL
                || binary.operator.type() == TokenType.BANG_EQUAL
                || binary.operator.type() == TokenType.EQUAL_EQUAL_EQUAL)
                && (isNullLiteral(binary.left) || isNullLiteral(binary.right));
    }

    private boolean isIdentityComparable(final TypeDescriptor type) {
        final var identityType = identityViewType(type);
        return identityType instanceof AnyDescriptor
                || identityType instanceof NominalDescriptor
                || identityType instanceof GenericDescriptor
                || identityType instanceof ArrayDescriptor
                || identityType instanceof FunctionDescriptor
                || identityType instanceof StringDescriptor;
    }

    private TypeDescriptor identityViewType(final TypeDescriptor type) {
        if (type instanceof NullableDescriptor nullable) return identityViewType(nullable.baseType());
        if (type instanceof ReferenceDescriptor reference) return identityViewType(reference.baseType());
        return type;
    }

    private boolean isPrimitive(final TypeDescriptor type) {
        final var baseType = type instanceof NullableDescriptor nullable
                ? nullable.baseType()
                : type;
        return baseType instanceof IntDescriptor || baseType instanceof FloatDescriptor
                || baseType instanceof BooleanDescriptor;
    }

    private TypeDescriptor resolveImplicitFieldRead(final Expr.Variable variable) {
        if (currentMethodOwner == null) return null;
        final var availableFields = initializerVisibleFields == null
                ? currentMethodOwner.fields()
                : initializerVisibleFields;
        final var field = availableFields.stream()
                .filter(candidate -> candidate.name().lexeme().equals(variable.name.lexeme()))
                .findFirst()
                .orElse(null);
        if (field == null) return null;

        final var receiver = new Expr.Variable(
                new Token(TokenType.THIS, "this", null, variable.name.line()),
                TypeDescriptor.ofInfer());
        final var receiverType = resolve(receiver);
        ensureFieldAccessible(variable.name, currentMethodOwner.name().lexeme());
        final var receiverBaseType = receiverType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : receiverType;
        final var resolvedType = TypeSubstitution.substitute(field.type(),
                substitutionsFor(currentMethodOwner.typeParameters(), receiverBaseType));
        variable.setImplicitFieldRead(receiver, currentMethodOwner.name().lexeme(), field.type());
        variable.setType(resolvedType);
        return resolvedType;
    }

    private TypeDescriptor resolveProperty(final Expr.Property property) {
        final var receiverType = resolve(property.receiver);
        final var safeFlows = property.safeNavigation()
                ? beginSafeNavigation(property.receiver, property.name, receiverType)
                : null;
        final var memberReceiverType = receiverType instanceof NullableDescriptor nullable
                ? nullable.baseType()
                : receiverType;
        final var baseType = memberReceiverType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : memberReceiverType;
        final var propertyIntrinsic = intrinsics.property(property.name.lexeme());
        if (propertyIntrinsic != null && propertyIntrinsic.id() == IntrinsicId.ARRAY_LENGTH
            && baseType instanceof ArrayDescriptor arrayType) {
            final var operation = resolveIntrinsic(propertyIntrinsic.id(),
                List.of(arrayType.elementType()), List.of(memberReceiverType), property.name);
            property.setIntrinsicOperation(operation);
            final var resultType = property.safeNavigation()
                    ? operation.resultType().toNullable()
                    : operation.resultType();
            property.setType(resultType);
            finishSafeNavigation(safeFlows);
            return resultType;
        }
        final var ownerName = className(baseType);
        final var owner = classes.get(ownerName);
        final var propertyDeclaration = findProperty(ownerName, property.name);
        if (propertyDeclaration != null) {
            if (!propertyDeclaration.isPublic()
                    && !Objects.equals(currentClassName, ownerName)) {
                Zeron.resolutionError(new ResolutionError(property.name, "Property is private."));
            }
            final var propertyType = resolvedPropertyType(ownerName, propertyDeclaration, memberReceiverType);
            property.setResolvedOwnerName(ownerName);
            property.setResolvedAsProperty(true);
            final var resultType = property.safeNavigation()
                    ? propertyType.toNullable()
                    : propertyType;
            property.setType(resultType);
            finishSafeNavigation(safeFlows);
            return resultType;
        }
        if (owner == null) Zeron.resolutionError(new ResolutionError(property.name, "Unknown property."));
        final var field = owner.fields().stream()
                .filter(candidate -> candidate.name().lexeme().equals(property.name.lexeme()))
                .findFirst()
                .orElse(null);
        if (field == null) {
            Zeron.resolutionError(new ResolutionError(property.name, "Unknown field."));
        }
        ensureFieldAccessible(property.name, ownerName);
        final var fieldType = TypeSubstitution.substitute(field.type(),
                substitutionsFor(owner.typeParameters(), memberReceiverType));
        final var resultType = property.safeNavigation() ? fieldType.toNullable() : fieldType;
        property.setType(resultType);
        finishSafeNavigation(safeFlows);
        return resultType;
    }

    private TypeDescriptor resolveMemberCall(final Expr.MemberCall call) {
        if (call.safeNavigation()) {
            final var receiverType = resolve(call.receiver);
            final var safeFlows = beginSafeNavigation(call.receiver, call.name, receiverType);
            final var memberReceiverType = receiverType instanceof NullableDescriptor nullable
                    ? nullable.baseType()
                    : receiverType;
            flowState = safeFlows.nonNull().copy();
            final var resultType = resolveMemberCall(call, memberReceiverType);
            finishSafeNavigation(safeFlows);
            final var nullableResult = resultType.toNullable();
            call.setType(nullableResult);
            return nullableResult;
        }
        return resolveMemberCall(call, null);
    }

    private TypeDescriptor resolveMemberCall(final Expr.MemberCall call,
                                             final TypeDescriptor alreadyResolvedReceiverType) {
        Zeron.debug("resolving member call       " + call.name.lexeme() + " for " + call.receiver);
        final var classOwnerName = call.receiver instanceof Expr.Variable typeName
            ? resolveClassName(typeName.name.lexeme(), typeName.name)
            : null;
        if (call.safeNavigation() && classOwnerName != null) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Safe navigation cannot be used for constructors or static calls."));
        }
        if (classOwnerName != null) {
            final var declaration = classes.get(classOwnerName);
            if (declaration == null) {
                final var javaClass = javaClassPath.find(classOwnerName);
                if (javaClass != null) return resolveJavaTypeCall(call, javaClass);
            }
            call.setResolvedClassName(classOwnerName);
            if (declaration.typeParameters().size() != call.explicitTypeArguments.size()) {
                Zeron.resolutionError(new ResolutionError(call.name,
                        "Expected " + declaration.typeParameters().size() + " class type arguments, found "
                                + call.explicitTypeArguments.size() + "."));
            }
            call.explicitTypeArguments.forEach(type -> validateType(type, call.name));
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            for (int i = 0; i < declaration.typeParameters().size(); i++) {
                substitutions.put(declaration.typeParameters().get(i), call.explicitTypeArguments.get(i));
            }
                if (call.name.lexeme().equals("new")) {
                if (!declaration.constructor().isPublic()
                    && !Objects.equals(currentClassName, declaration.name().lexeme())) {
                    Zeron.resolutionError(new ResolutionError(call.name, "Constructor is private."));
                }
                final var constructorTypes = declaration.canonicalConstructorTypes();
                if (call.arguments.size() != constructorTypes.size()) {
                    Zeron.resolutionError(new ResolutionError(call.name,
                        "Expected " + constructorTypes.size() + " constructor arguments, found "
                            + call.arguments.size() + "."));
                }
                for (int i = 0; i < call.arguments.size(); i++) {
                    final var expectedType = TypeSubstitution.substitute(
                        constructorTypes.get(i), substitutions);
                    ensureAssignable(expectedType,
                        resolveArgument(call.arguments.get(i), expectedType), call.name);
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
                Zeron.resolutionError(new ResolutionError(call.name, "Unknown named constructor."));
                }
                if (!namedConstructor.isPublic()
                    && !Objects.equals(currentClassName, declaration.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(call.name, "Named constructor is private."));
                }
                final var instantiatedFactory = (FunctionDescriptor) TypeSubstitution.substitute(
                    namedConstructor.typeDescriptor(), substitutions);
                if (instantiatedFactory.arity() != call.arguments.size()) {
                Zeron.resolutionError(new ResolutionError(call.name,
                    "Expected " + instantiatedFactory.arity() + " arguments, found "
                        + call.arguments.size() + "."));
                }
                for (int i = 0; i < call.arguments.size(); i++) {
                final var expectedType = instantiatedFactory.parameters().get(i);
                ensureAssignable(expectedType,
                    resolveArgument(call.arguments.get(i), expectedType), call.name);
                }
                call.setResolvedDescriptor(instantiatedFactory);
                call.setType(instantiatedFactory.returnType());
                return instantiatedFactory.returnType();
        }

        final var receiverType = alreadyResolvedReceiverType == null
                ? resolve(call.receiver)
                : alreadyResolvedReceiverType;
        if (receiverType instanceof TypeParameterDescriptor parameter) {
            return resolveBoundedMemberCall(call, parameter);
        }
        final var ownerName = className(receiverType);
        final var owner = classes.get(ownerName);
        final var classMethod = owner == null ? null : owner.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .findFirst()
                .orElse(null);
        final var contract = contracts.get(ownerName);
        final var contractMethod = contract == null ? null : contract.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .findFirst()
                .orElse(null);
        if (classMethod == null && contractMethod == null) {
            if (owner != null) {
                final var defaults = defaultMethodsOnClass(owner, call.name.lexeme());
                if (defaults.size() > 1) {
                    Zeron.resolutionError(new ResolutionError(call.name,
                            "Multiple default contract methods named '" + call.name.lexeme()
                                    + "' require an explicit class implementation."));
                }
                if (!defaults.isEmpty()) {
                    final var defaultMethod = defaults.getFirst();
                    final var descriptor = defaultMethod.instantiatedType();
                    if (defaultMethod.method().isMutating()
                            && !(receiverType instanceof ReferenceDescriptor)) {
                        Zeron.resolutionError(new ResolutionError(call.name,
                                "Mutating method requires a mutable reference."));
                    }
                    call.setResolvedOwnerName(defaultMethod.ownerName());
                    call.setReceiverRequiresCast(true);
                    if (descriptor.isGeneric()) return resolveGenericMemberCall(call, descriptor);
                    if (!call.explicitTypeArguments.isEmpty()) {
                        Zeron.resolutionError(new ResolutionError(call.name,
                                "This method does not declare type parameters."));
                    }
                    call.setResolvedDescriptor(descriptor);
                    if (descriptor.arity() != call.arguments.size()) {
                        Zeron.resolutionError(new ResolutionError(call.name,
                                "Expected " + descriptor.arity() + " arguments, found "
                                        + call.arguments.size() + "."));
                    }
                    for (int i = 0; i < call.arguments.size(); i++) {
                        ensureAssignable(descriptor.parameters().get(i),
                                resolveArgument(call.arguments.get(i), descriptor.parameters().get(i)),
                                call.name);
                    }
                    call.setType(descriptor.returnType());
                    return descriptor.returnType();
                }
            }
            final var javaClass = javaClassPath.find(ownerName);
            if (javaClass != null) return resolveJavaInstanceCall(call, receiverType, javaClass);
            Zeron.resolutionError(new ResolutionError(call.name, "Unknown method: '" + call.name.lexeme() + "'"));
        }

        final var descriptor = classMethod != null
                ? classMethod.typeDescriptor()
                : contractMethod.typeDescriptor();
        final var typeParameters = classMethod != null
            ? owner.typeParameters()
            : contract.typeParameters();
        final var instantiatedDescriptor = (FunctionDescriptor) TypeSubstitution.substitute(
            descriptor, substitutionsFor(typeParameters, receiverType));
        final var isMutating = classMethod != null
                ? classMethod.isMutating()
                : contractMethod.isMutating();
        if (classMethod != null && !classMethod.isPublic()
                && !Objects.equals(currentClassName, ownerName)) {
            Zeron.resolutionError(new ResolutionError(call.name, "Method is private."));
        }
        if (isMutating && !(receiverType instanceof ReferenceDescriptor)) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Mutating method requires a mutable reference."));
        }
        if (instantiatedDescriptor.isGeneric()) {
            return resolveGenericMemberCall(call, instantiatedDescriptor);
        }
        if (!call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "This method does not declare type parameters."));
        }
        call.setResolvedDescriptor(instantiatedDescriptor);
        if (instantiatedDescriptor.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Expected " + instantiatedDescriptor.arity() + " arguments, found "
                            + call.arguments.size() + "."));
        }
        for (int i = 0; i < call.arguments.size(); i++) {
            ensureAssignable(instantiatedDescriptor.parameters().get(i),
                    resolveArgument(call.arguments.get(i), instantiatedDescriptor.parameters().get(i)),
                    call.name);
        }
        call.setType(instantiatedDescriptor.returnType());
        return instantiatedDescriptor.returnType();
    }

    private SafeNavigationFlows beginSafeNavigation(final Expr receiver,
                                                    final Token where,
                                                    final TypeDescriptor receiverType) {
        if (receiverType instanceof NullDescriptor) {
            Zeron.resolutionError(new ResolutionError(where,
                    "Safe navigation requires a receiver with a known non-null type."));
        }
        final var afterReceiver = flowState.copy();
        final var mayBeNull = receiverType instanceof NullableDescriptor;
        final var nonNullFlow = afterReceiver.copy();
        final var nullPath = mayBeNull ? afterReceiver.copy() : FlowState.unreachable();
        if (mayBeNull) {
            final var variable = directVariable(receiver);
            if (variable != null && isRefinable(variable.name)) {
                final var binding = symbols.getSymbol(variable.name);
                final var existingFact = afterReceiver.get(binding.name());
                nonNullFlow.put(binding.name(), existingFact == null
                        ? nonNullFact(binding.type())
                        : new FlowFact(false, existingFact.nonNullAlternatives()));
                nullPath.put(binding.name(), new FlowFact(true, Set.of()));
            }
        }
        flowState = nonNullFlow.copy();
        return new SafeNavigationFlows(nonNullFlow, nullPath);
    }

    private void finishSafeNavigation(final SafeNavigationFlows flows) {
        if (flows != null) {
            flowState = FlowState.join(flowState, flows.nullPath());
        }
    }

    private TypeDescriptor resolveGenericMemberCall(final Expr.MemberCall call,
                                                    final FunctionDescriptor genericType) {
        final var typeParameters = genericType.typeParameters();
        if (!call.explicitTypeArguments.isEmpty()
                && call.explicitTypeArguments.size() != typeParameters.size()) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Expected " + typeParameters.size() + " type arguments, found "
                            + call.explicitTypeArguments.size() + "."));
        }
        if (genericType.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Expected " + genericType.arity() + " arguments, found " + call.arguments.size() + "."));
        }

        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
            final var explicitType = call.explicitTypeArguments.get(i);
            validateType(explicitType, call.name);
            substitutions.put(typeParameters.get(i), explicitType);
        }

        final var resolvedArguments = new TypeDescriptor[call.arguments.size()];
        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (argument instanceof Expr.Lambda || isFunctionReferenceCandidate(argument)) continue;
            resolvedArguments[i] = resolve(argument);
            TypeUnifier.unify(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.name);
        }

        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (!(argument instanceof Expr.Lambda) && !isFunctionReferenceCandidate(argument)) continue;
            final var expected = TypeSubstitution.substitute(genericType.parameters().get(i), substitutions);
            if (functionType(expected) == null) {
                Zeron.resolutionError(new ResolutionError(call.name,
                        "A function value argument requires a function parameter type."));
            }
            resolvedArguments[i] = resolveArgument(argument, expected);
            TypeUnifier.unify(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.name);
        }

        for (final var parameter : typeParameters) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(call.name,
                        "Cannot infer type parameter '" + parameter.name()
                                + "'; provide an explicit type argument."));
            }
        }

        final var instantiatedParameters = genericType.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                .toList();
        for (int i = 0; i < resolvedArguments.length; i++) {
            ensureAssignable(instantiatedParameters.get(i), resolvedArguments[i], call.name);
        }
        final var instantiatedReturn = TypeSubstitution.substitute(genericType.returnType(), substitutions);
        call.setResolvedDescriptor(TypeDescriptor.functionOf(genericType.name(), instantiatedReturn,
                instantiatedParameters.toArray(TypeDescriptor[]::new)));
        call.setType(instantiatedReturn);
        return instantiatedReturn;
    }

    private TypeDescriptor resolveJavaTypeCall(final Expr.MemberCall call,
                                               final JavaClassPath.JavaClass javaClass) {
        if (!call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Java generic type arguments are not supported by the current interop slice."));
        }
        final var constructor = call.name.lexeme().equals("new");
        if (constructor && (javaClass.isInterface() || javaClass.isAbstract())) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Cannot construct an abstract Java class or interface."));
        }
        final var methodName = constructor ? "<init>" : call.name.lexeme();
        return resolveJavaCall(call, javaClass, methodName, constructor, !constructor);
    }

    private TypeDescriptor resolveJavaInstanceCall(final Expr.MemberCall call,
                                                   final TypeDescriptor receiverType,
                                                   final JavaClassPath.JavaClass javaClass) {
        if (!(receiverType instanceof ReferenceDescriptor)) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Java instance method calls require a mutable & receiver."));
        }
        if (!call.explicitTypeArguments.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Java generic method type arguments are not supported by the current interop slice."));
        }
        return resolveJavaCall(call, javaClass, call.name.lexeme(), false, false);
    }

    private TypeDescriptor resolveJavaCall(final Expr.MemberCall call,
                                           final JavaClassPath.JavaClass javaClass,
                                           final String methodName,
                                           final boolean constructor,
                                           final boolean staticCall) {
        if (call.arguments.stream().anyMatch(Expr.Lambda.class::isInstance)) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Java functional-interface callback conversion is not supported by this interop slice."));
        }
        final var actualTypes = call.arguments.stream().map(this::resolve).toList();
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
                if (parameterType == null || !typeCompatibility.canAssign(parameterType, actualTypes.get(i))) {
                    applicable = false;
                    break;
                }
                parameterTypes.add(parameterType);
                cost += javaConversionCost(parameterType, actualTypes.get(i));
            }
            if (!applicable) continue;

            final var returnType = constructor
                    ? new ReferenceDescriptor(TypeDescriptor.ofName(javaClass.binaryName()))
                    : JavaTypeMapping.toZeronType(method.descriptor().returnType());
            if (returnType == null) {
                hasUnsupportedShape = true;
                continue;
            }
            if (!constructor) validateType(returnType, call.name);
                candidates.add(new JavaCandidate(method, List.copyOf(parameterTypes), returnType, cost,
                    method.isVarArgs()));
        }

        if (candidates.isEmpty()) {
            final var reason = hasUnsupportedShape
                    ? "Matching Java generic, varargs, or unsupported type signatures are not supported."
                    : "No applicable public Java overload was found.";
            Zeron.resolutionError(new ResolutionError(call.name, reason));
        }
        final var fixedArityCandidates = candidates.stream().filter(candidate -> !candidate.varArgs()).toList();
        final var applicableCandidates = fixedArityCandidates.isEmpty()
            ? candidates.stream().filter(JavaCandidate::varArgs).toList()
            : fixedArityCandidates;
        final var bestCost = applicableCandidates.stream().mapToInt(JavaCandidate::cost).min().orElseThrow();
        final var bestCandidates = applicableCandidates.stream()
            .filter(candidate -> candidate.cost() == bestCost).toList();
        if (bestCandidates.size() != 1) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Java overload resolution is ambiguous; provide arguments with a more specific type."));
        }
        final var selected = bestCandidates.getFirst();
        for (int i = 0; i < selected.parameterTypes().size(); i++) {
            ensureAssignable(selected.parameterTypes().get(i), actualTypes.get(i), call.name);
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

    private int javaConversionCost(final TypeDescriptor expected, final TypeDescriptor actual) {
        if (expected.equals(actual)) return 0;
        if (expected instanceof NullableDescriptor nullable && nullable.baseType().equals(actual)) return 1;
        return 2;
    }

    private TypeDescriptor resolveBoundedMemberCall(final Expr.MemberCall call,
                                                    final TypeParameterDescriptor parameter) {
        if (parameter.bound() == null) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Member access on an unconstrained type parameter is not allowed."));
        }
        final var bound = parameter.bound();
        final var ownerName = className(bound);
        final var contract = contracts.get(ownerName);
        final var method = contract == null ? null : contract.methods().stream()
                .filter(candidate -> candidate.name().lexeme().equals(call.name.lexeme()))
                .findFirst().orElse(null);
        if (method == null) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Method is not provided by the type parameter's contract bound."));
        }
        if (method.isMutating()) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Mutating methods are not available through a generic contract bound."));
        }

        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        final var boundBase = bound instanceof ReferenceDescriptor reference ? reference.baseType() : bound;
        if (boundBase instanceof GenericDescriptor genericBound) {
            if (genericBound.typeParameters().size() != contract.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(call.name,
                        "Invalid generic contract bound '" + bound + "'."));
            }
            for (int index = 0; index < contract.typeParameters().size(); index++) {
                substitutions.put(contract.typeParameters().get(index), genericBound.typeParameters().get(index));
            }
        }
        final var descriptor = (FunctionDescriptor) TypeSubstitution.substitute(
                method.typeDescriptor(), substitutions);
        if (descriptor.isGeneric()) {
            final var result = resolveGenericMemberCall(call, descriptor);
            call.setResolvedOwnerName(ownerName);
            call.setReceiverRequiresCast(true);
            return result;
        }
        if (descriptor.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(call.name,
                    "Expected " + descriptor.arity() + " arguments, found " + call.arguments.size() + "."));
        }
        for (int index = 0; index < call.arguments.size(); index++) {
            final var expected = descriptor.parameters().get(index);
            ensureAssignable(expected, resolveArgument(call.arguments.get(index), expected), call.name);
        }
        call.setResolvedOwnerName(ownerName);
        call.setReceiverRequiresCast(true);
        call.setResolvedDescriptor(descriptor);
        call.setType(descriptor.returnType());
        return descriptor.returnType();
    }

    private String resolveClassName(final String name, final Token where) {
        if (classes.containsKey(name)) return name;
        final var importedName = currentTypeImports.get(name);
        if (importedName != null && (classes.containsKey(importedName) || javaClassPath.find(importedName) != null)) {
            return importedName;
        }
        final var qualifiedName = packageName.isEmpty() ? name : packageName + "." + name;
        if (classes.containsKey(qualifiedName) || javaClassPath.find(qualifiedName) != null) return qualifiedName;
        final var starCandidates = starTypeCandidates(name);
        if (starCandidates.size() > 1) {
            Zeron.resolutionError(new ResolutionError(where,
                    "Ambiguous type '" + name + "' from star imports; add an explicit import or alias."));
        }
        if (starCandidates.size() == 1) return starCandidates.getFirst();
        return javaClassPath.find(name) != null ? name : null;
    }

    private List<String> starTypeCandidates(final String simpleTypeName) {
        return currentOnDemandImports.stream()
                .filter(importedPackage -> !importedPackage.equals(packageName))
                .map(importedPackage -> importedPackage + "." + simpleTypeName)
                .filter(name -> {
                    final var classDeclaration = classes.get(name);
                    final var contractDeclaration = contracts.get(name);
                    return classDeclaration != null && classDeclaration.isPublic()
                            || contractDeclaration != null && contractDeclaration.isPublic();
                })
                .distinct()
                .toList();
    }

    private void validateStarTypeAmbiguity(final String qualifiedName, final Token where) {
        final var importedTypeName = simpleName(qualifiedName);
        final var fromStarImport = currentOnDemandImports.stream()
                .anyMatch(importedPackage -> qualifiedName.startsWith(importedPackage + "."));
        if (fromStarImport && !currentTypeImports.containsValue(qualifiedName)
                && starTypeCandidates(importedTypeName).size() > 1) {
            Zeron.resolutionError(new ResolutionError(where,
                    "Ambiguous type '" + importedTypeName
                            + "' from star imports; add an explicit import or alias."));
        }
    }

    private String resolveFunctionName(final String name, final Token where) {
        final var localName = qualify(packageName, name);
        if (functions.containsKey(localName)) return localName;
        final var explicitImport = currentFunctionImports.get(name);
        if (explicitImport != null) return explicitImport;
        final var candidates = currentOnDemandImports.stream()
                .filter(importedPackage -> !importedPackage.equals(packageName))
                .map(importedPackage -> qualify(importedPackage, name))
                .filter(functions::containsKey)
                .filter(functionName -> ((Stmt.FunctionDeclaration) functions.get(functionName)).isPublic())
                .distinct()
                .toList();
        if (candidates.size() > 1) {
            Zeron.resolutionError(new ResolutionError(where,
                    "Ambiguous function '" + name + "' from star imports; add an explicit import or alias."));
        }
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private TypeDescriptor resolveArgument(final Expr argument,
                                           final TypeDescriptor expectedType) {
        if (argument instanceof Expr.Variable variable && symbols.containsSymbol(variable.name)) {
            var bindingType = symbols.getSymbol(variable.name).type();
            if (bindingType instanceof ReferenceDescriptor reference) bindingType = reference.baseType();
            if (bindingType instanceof FunctionDescriptor scheme && scheme.isGeneric()) {
                final var expectedFunction = functionType(expectedType);
                if (expectedFunction != null) {
                    final var specialized = instantiateLambdaScheme(scheme, expectedFunction, variable.name);
                    variable.setStoredFunctionType(scheme);
                    variable.setType(specialized);
                    return specialized;
                }
            }
        }
        if (argument instanceof Expr.Variable variable
                && (variable.resolvedFunctionName() != null
                || !symbols.containsSymbol(variable.name)
                && resolveFunctionName(variable.name.lexeme(), variable.name) != null)) {
            return resolveFunctionReference(variable, expectedType);
        }
        if (argument instanceof Expr.Lambda lambda) {
            final var functionType = functionType(expectedType);
            if (functionType != null) return resolveLambda(lambda, functionType);
        }
        return resolve(argument);
    }

    private FunctionDescriptor instantiateLambdaScheme(final FunctionDescriptor scheme,
                                                        final FunctionDescriptor expectedType,
                                                        final Token where) {
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        if (TypeSubstitution.containsTypeParameter(expectedType)) {
            unifyFunctionReferenceType(scheme, expectedType, substitutions, where);
        } else {
            TypeUnifier.unify(scheme, expectedType, substitutions, where);
        }
        for (final var parameter : scheme.typeParameters()) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(where,
                        "Cannot infer type parameter '" + parameter.name()
                                + "' for polymorphic lambda at this use."));
            }
        }
        final var specialized = TypeDescriptor.functionOf(scheme.name(),
                TypeSubstitution.substitute(scheme.returnType(), substitutions),
                scheme.parameters().stream()
                        .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                        .toArray(TypeDescriptor[]::new));
        if (!TypeSubstitution.containsTypeParameter(expectedType)) {
            ensureAssignable(expectedType, specialized, where);
        }
        return specialized;
    }

    private FunctionDescriptor resolveFunctionReference(final Expr.Variable reference,
                                                         final TypeDescriptor expectedType) {
        final var functionName = reference.resolvedFunctionName() == null
                ? resolveFunctionName(reference.name.lexeme(), reference.name)
                : reference.resolvedFunctionName();
        if (functionName == null || symbols.containsSymbol(reference.name)
                && reference.resolvedFunctionName() == null) {
            Zeron.resolutionError(new ResolutionError(reference.name,
                    "Function reference does not resolve to a top-level function."));
        }
        final var functionToken = functionSymbolTokens.get(functionName);
        final var sourceType = (FunctionDescriptor) symbols.getFunction(functionToken).type();
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        final var explicitTypes = reference.explicitFunctionTypeArguments;
        if (!explicitTypes.isEmpty()) {
            if (explicitTypes.size() != sourceType.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(reference.name,
                        "Expected " + sourceType.typeParameters().size() + " type arguments, found "
                                + explicitTypes.size() + "."));
            }
            for (int i = 0; i < explicitTypes.size(); i++) {
                validateType(explicitTypes.get(i), reference.name);
                substitutions.put(sourceType.typeParameters().get(i), explicitTypes.get(i));
            }
        } else if (sourceType.isGeneric()) {
            final var expectedFunction = functionType(expectedType);
            if (expectedFunction == null) {
                Zeron.resolutionError(new ResolutionError(reference.name,
                        "A generic function value needs explicit type arguments or an expected function type."));
            }
            if (TypeSubstitution.containsTypeParameter(expectedFunction)) {
                unifyFunctionReferenceType(sourceType, expectedFunction, substitutions, reference.name);
            } else {
                TypeUnifier.unify(sourceType, expectedFunction, substitutions, reference.name);
            }
        }

        for (final var parameter : sourceType.typeParameters()) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(reference.name,
                        "Cannot infer type parameter '" + parameter.name()
                                + "' for function value; provide an explicit type argument."));
            }
            if (parameter.bound() != null) {
                final var requiredBound = TypeSubstitution.substitute(parameter.bound(), substitutions);
                ensureAssignable(requiredBound, substitutions.get(parameter), reference.name);
            }
        }

        final var specializedType = sourceType.isGeneric()
                ? TypeDescriptor.functionOf(sourceType.name(),
                        TypeSubstitution.substitute(sourceType.returnType(), substitutions),
                        sourceType.parameters().stream()
                                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                                .toArray(TypeDescriptor[]::new))
                : sourceType;
        final var expectedFunction = expectedType == null ? null : functionType(expectedType);
        if (expectedFunction != null && !TypeSubstitution.containsTypeParameter(expectedFunction)) {
            ensureAssignable(expectedFunction, specializedType, reference.name);
        }
        reference.setResolvedFunctionName(functionName);
        reference.setSourceFunctionType(sourceType);
        reference.setSpecializedFunctionType(specializedType);
        reference.setType(specializedType);
        return specializedType;
    }

    private void unifyFunctionReferenceType(final TypeDescriptor pattern,
                                           TypeDescriptor actual,
                                           final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                                           final Token where) {
        if (pattern.equals(actual)) return;
        if (pattern instanceof TypeParameterDescriptor parameter) {
            final var previous = substitutions.putIfAbsent(parameter, actual);
            if (previous == null || previous.equals(actual)) return;
            if (actual instanceof TypeParameterDescriptor) return;
            if (previous instanceof TypeParameterDescriptor) {
                substitutions.put(parameter, actual);
                return;
            }
            TypeUnifier.unify(pattern, actual, substitutions, where);
            return;
        }
        if (actual instanceof TypeParameterDescriptor) return;
        if (pattern instanceof NullableDescriptor nullablePattern) {
            if (actual instanceof NullDescriptor) return;
            final var actualBase = actual instanceof NullableDescriptor nullableActual
                    ? nullableActual.baseType()
                    : actual;
            unifyFunctionReferenceType(nullablePattern.baseType(), actualBase, substitutions, where);
            return;
        }
        if (pattern instanceof ReferenceDescriptor referencePattern) {
            final var actualBase = actual instanceof ReferenceDescriptor referenceActual
                    ? referenceActual.baseType()
                    : actual;
            unifyFunctionReferenceType(referencePattern.baseType(), actualBase, substitutions, where);
            return;
        }
        if (pattern instanceof ArrayDescriptor arrayPattern
                && actual instanceof ReferenceDescriptor reference
                && reference.baseType() instanceof ArrayDescriptor) {
            actual = reference.baseType();
        }
        if (pattern instanceof ArrayDescriptor arrayPattern && actual instanceof ArrayDescriptor arrayActual) {
            unifyFunctionReferenceType(arrayPattern.elementType(), arrayActual.elementType(), substitutions, where);
            return;
        }
        if (pattern instanceof GenericDescriptor genericPattern
                && actual instanceof GenericDescriptor genericActual
                && genericPattern.baseType().equals(genericActual.baseType())
                && genericPattern.typeParameters().size() == genericActual.typeParameters().size()) {
            for (int i = 0; i < genericPattern.typeParameters().size(); i++) {
                unifyFunctionReferenceType(genericPattern.typeParameters().get(i),
                        genericActual.typeParameters().get(i), substitutions, where);
            }
            return;
        }
        if (pattern instanceof FunctionDescriptor functionPattern
                && actual instanceof FunctionDescriptor functionActual
                && functionPattern.arity() == functionActual.arity()) {
            for (int i = 0; i < functionPattern.arity(); i++) {
                unifyFunctionReferenceType(functionPattern.parameters().get(i),
                        functionActual.parameters().get(i), substitutions, where);
            }
            unifyFunctionReferenceType(functionPattern.returnType(),
                    functionActual.returnType(), substitutions, where);
            return;
        }
        TypeUnifier.unify(pattern, actual, substitutions, where);
    }

    private FunctionDescriptor functionType(final TypeDescriptor type) {
        return switch (type) {
            case FunctionDescriptor function -> function;
            case NullableDescriptor nullable -> functionType(nullable.baseType());
            case ReferenceDescriptor reference -> functionType(reference.baseType());
            default -> null;
        };
    }

    private String className(final TypeDescriptor type) {
        var baseType = type instanceof ReferenceDescriptor reference ? reference.baseType() : type;
        if (baseType instanceof GenericDescriptor generic) baseType = generic.baseType();
        return baseType instanceof NominalDescriptor nominal ? nominal.name() : "";
    }

    private void ensureTypeAccessible(final String name,
                                      final Token where,
                                      final boolean isPublic) {
        if (!packageOf(name).equals(packageName) && !isPublic
                && !currentTypeImports.containsValue(name)) {
            Zeron.resolutionError(new ResolutionError(where, "Type '" + name + "' is not public."));
        }
    }

    private static String packageOf(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? "" : qualifiedName.substring(0, separator);
    }

    private static String simpleName(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    }

    private Stmt.Field findField(final String className, final Token name) {
        final var owner = classes.get(className);
        if (owner == null) {
            Zeron.resolutionError(new ResolutionError(name, "Field receiver is not a class."));
        }
        return owner.fields().stream()
                .filter(field -> field.name().lexeme().equals(name.lexeme()))
                .findFirst()
                .orElseThrow(() -> new ResolutionError(name, "Unknown field."));
    }

    private PropertyInfo findProperty(final String ownerName, final Token name) {
        final var owner = classes.get(ownerName);
        if (owner != null) {
            return owner.properties().stream()
                    .filter(property -> property.name().lexeme().equals(name.lexeme()))
                    .findFirst()
                    .map(property -> new PropertyInfo(property.type(), property.isPublic(),
                            property.isMutating()))
                    .orElse(null);
        }
        final var contract = contracts.get(ownerName);
        if (contract == null) return null;
        return contract.properties().stream()
                .filter(property -> property.name().lexeme().equals(name.lexeme()))
                .findFirst()
                .map(property -> new PropertyInfo(property.type(), true, property.isMutating()))
                .orElse(null);
    }

    private TypeDescriptor resolvedPropertyType(final String ownerName,
                                                final PropertyInfo property,
                                                final TypeDescriptor receiverType) {
        final var owner = classes.get(ownerName);
        final var parameters = owner != null
                ? owner.typeParameters()
                : contracts.get(ownerName).typeParameters();
        return TypeSubstitution.substitute(property.type(), substitutionsFor(parameters, receiverType));
    }

    private void ensureFieldAccessible(final Token where, final String owner) {
        if (!Objects.equals(currentClassName, owner)) {
            Zeron.resolutionError(new ResolutionError(where, "Field is private."));
        }
    }

    private ArrayDescriptor resolveArrayType(TypeDescriptor type, Token where) {
        if (type instanceof ReferenceDescriptor reference) type = reference.baseType();
        if (type instanceof ArrayDescriptor arrayType) return arrayType;
        Zeron.resolutionError(new ResolutionError(where, "Expected a non-null Array<T> value."));
        throw new IllegalStateException("unreachable");
    }

    private TypeDescriptor arrayElementType(final TypeDescriptor resultType, final Token where) {
        TypeDescriptor arrayType = resultType;
        if (arrayType instanceof ReferenceDescriptor reference) arrayType = reference.baseType();
        if (arrayType instanceof ArrayDescriptor array) return array.elementType();
        Zeron.resolutionError(new ResolutionError(where,
                "The internal array operation requires an array value."));
        throw new IllegalStateException("unreachable");
    }

    private ResolvedIntrinsicOperation resolveIntrinsic(final IntrinsicId id,
                                                        final List<TypeDescriptor> typeArguments,
                                                        final List<TypeDescriptor> actualParameterTypes,
                                                        final Token where) {
        final var definition = intrinsics.require(id);
        final var signature = definition.signature();
        if (signature.typeParameters().size() != typeArguments.size()) {
            throw new IllegalStateException("Intrinsic type-argument arity mismatch for " + id.stableName());
        }
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < typeArguments.size(); i++) {
            substitutions.put(signature.typeParameters().get(i), typeArguments.get(i));
        }

        final var expectedParameterTypes = signature.parameterTypes().stream()
                .map(type -> TypeSubstitution.substitute(type, substitutions))
                .toList();
        final var expectedArity = signature.variadic()
                ? actualParameterTypes.size() >= expectedParameterTypes.size()
                : actualParameterTypes.size() == expectedParameterTypes.size();
        if (!expectedArity) {
            throw new IllegalStateException("Intrinsic operand arity mismatch for " + id.stableName());
        }
        final var instantiatedParameterTypes = new ArrayList<TypeDescriptor>(actualParameterTypes.size());
        for (int i = 0; i < actualParameterTypes.size(); i++) {
            final var expected = expectedParameterTypes.get(signature.variadic() ? 0 : i);
            ensureAssignable(expected, actualParameterTypes.get(i), where);
            instantiatedParameterTypes.add(expected);
        }
        final var resultType = TypeSubstitution.substitute(signature.returnType(), substitutions);
        return new ResolvedIntrinsicOperation(id, instantiatedParameterTypes, resultType);
    }

    private void beginScope() {
        symbols.beginScope();
    }

    private void endScope() {
        symbols.endScope();
    }

    private void declareFunction(final Stmt declaration,
                                 final Token name,
                                 final TypeDescriptor type) {
        symbols.declareFunction(declaration, name, type);
    }

    private void declare(final Stmt declaration,
                         final Token name,
                         final TypeDescriptor type,
                         final BindingMutability mutability) {
        symbols.declareSymbol(declaration, name, type, mutability);
    }

    private void define(final Token name) {
        symbols.define(name);
    }

    private FunctionDescriptor getFunction(final Token name) {
        return (FunctionDescriptor) symbols.getFunction(name).type();
    }

    private TypeDescriptor getSymbol(final Token name) {
        return symbols.getSymbol(name).type();
    }

    private Stmt getDeclaration(final Token name) {
        return symbols.getSymbol(name).declaration();
    }

    // TODO: fix
    private FunctionDescriptor inferLambdaType(final Expr.Lambda lambda) {
        final var enclosingLoopDepth = loopDepth;
        final var enclosingFlow = flowState;
        loopDepth = 0;
        flowState = new FlowState();
        beginScope();
        try {
            for (final var param : lambda.params) {
                declare(SYNTHETIC_VAR, param, TypeDescriptor.ofInfer(), BindingMutability.IMMUTABLE);
                define(param);
            }

            TypeDescriptor returnType = TypeDescriptor.ofUnit();
            for (final var stmt : lambda.body) {
                if (stmt instanceof Stmt.Return(Expr value)) {
                    returnType = value == null ? TypeDescriptor.ofUnit() : resolve(value);
                } else {
                    resolve(stmt);
                }
            }

            final var parameterTypes = new ArrayList<TypeDescriptor>();
            for (final var param : lambda.params) {
                final var binding = symbols.getSymbol(param);
                parameterTypes.add(binding.type() instanceof InferDescriptor
                        ? TypeDescriptor.ofInfer()
                        : binding.type());
            }

            return parameterTypes.isEmpty()
                    ? TypeDescriptor.functionOf("", returnType)
                    : TypeDescriptor.functionOf("", returnType,
                            parameterTypes.toArray(TypeDescriptor[]::new));
        } finally {
            endScope();
            loopDepth = enclosingLoopDepth;
            flowState = enclosingFlow;
        }
    }

        private FunctionDescriptor generalizeLambda(final Expr.Lambda lambda,
                            final FunctionDescriptor inferredType) {
            if (inferredType.parameters().stream().anyMatch(this::containsFunctionScheme)
                || containsFunctionScheme(inferredType.returnType())) return null;
        final var unresolvedParameters = new HashSet<String>();
        for (int i = 0; i < lambda.params.size(); i++) {
            if (containsInfer(inferredType.parameters().get(i))) {
            unresolvedParameters.add(lambda.params.get(i).lexeme());
            }
        }
        if (unresolvedParameters.isEmpty() && !containsInfer(inferredType.returnType())) return null;

        if (lambda.body.size() != 1 || !(lambda.body.getFirst() instanceof Stmt.Return returnStmt)) {
            return null;
        }
        final var returnedParameter = returnStmt.value() instanceof Expr.Variable variable
            ? lambda.params.stream().filter(parameter -> parameter.lexeme().equals(variable.name.lexeme()))
                .findFirst().orElse(null)
            : null;
        if (containsInfer(inferredType.returnType()) && returnedParameter == null) return null;
        if (returnStmt.value() != null && returnedParameter == null
            && referencesAnyVariable(returnStmt.value(), unresolvedParameters)) return null;

        final var scopeId = LAMBDA_TYPE_SCOPES.incrementAndGet();
        final var typeParameters = new ArrayList<TypeParameterDescriptor>();
        final var parameterTypes = new ArrayList<TypeDescriptor>(lambda.params.size());
        final var quantifiedParameters = new HashMap<String, TypeParameterDescriptor>();
        for (int i = 0; i < lambda.params.size(); i++) {
            final var parameterType = inferredType.parameters().get(i);
            if (!containsInfer(parameterType)) {
            parameterTypes.add(parameterType);
            continue;
            }
            if (!(parameterType instanceof InferDescriptor)) return null;
            final var typeParameter = new TypeParameterDescriptor(scopeId,
                "LambdaT" + i);
            typeParameters.add(typeParameter);
            quantifiedParameters.put(lambda.params.get(i).lexeme(), typeParameter);
            parameterTypes.add(typeParameter);
        }

        final TypeDescriptor returnType;
        if (containsInfer(inferredType.returnType())) {
            final var typeParameter = quantifiedParameters.get(returnedParameter.lexeme());
            if (typeParameter == null) return null;
            returnType = typeParameter;
        } else {
            returnType = inferredType.returnType();
        }

        if (typeParameters.isEmpty()) return null;
        final var generalized = TypeDescriptor.genericFunctionOf("", returnType,
            parameterTypes, typeParameters);
        lambda.setType(generalized);
        return generalized;
        }

    private boolean containsFunctionScheme(final TypeDescriptor type) {
        return switch (type) {
            case FunctionDescriptor function -> function.isGeneric()
                    || function.parameters().stream().anyMatch(this::containsFunctionScheme)
                    || containsFunctionScheme(function.returnType());
            case NullableDescriptor nullable -> containsFunctionScheme(nullable.baseType());
            case ReferenceDescriptor reference -> containsFunctionScheme(reference.baseType());
            case ArrayDescriptor array -> containsFunctionScheme(array.elementType());
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(this::containsFunctionScheme);
            default -> false;
        };
    }

        private boolean containsInfer(final TypeDescriptor type) {
        return switch (type) {
            case InferDescriptor _ -> true;
            case NullableDescriptor nullable -> containsInfer(nullable.baseType());
            case ReferenceDescriptor reference -> containsInfer(reference.baseType());
            case ArrayDescriptor array -> containsInfer(array.elementType());
            case GenericDescriptor generic -> generic.typeParameters().stream().anyMatch(this::containsInfer);
            case FunctionDescriptor function -> containsInfer(function.returnType())
                || function.parameters().stream().anyMatch(this::containsInfer);
            default -> false;
        };
        }

        private boolean referencesAnyVariable(final Expr expression, final Set<String> names) {
        return switch (expression) {
            case Expr.Variable variable -> names.contains(variable.name.lexeme());
            case Expr.Assignment assignment -> names.contains(assignment.name.lexeme())
                || referencesAnyVariable(assignment.value, names);
            case Expr.CoalesceAssignment assignment -> names.contains(assignment.name.lexeme())
                || referencesAnyVariable(assignment.value, names);
            case Expr.Binary binary -> referencesAnyVariable(binary.left, names)
                || referencesAnyVariable(binary.right, names);
            case Expr.Call call -> names.contains(call.callee.lexeme())
                || call.arguments.stream().anyMatch(argument -> referencesAnyVariable(argument, names));
            case Expr.MemberCall call -> referencesAnyVariable(call.receiver, names)
                || call.arguments.stream().anyMatch(argument -> referencesAnyVariable(argument, names));
            case Expr.Property property -> referencesAnyVariable(property.receiver, names);
            case Expr.PropertyAssignment assignment -> referencesAnyVariable(assignment.property.receiver, names)
                || referencesAnyVariable(assignment.value, names);
            case Expr.PropertyCompoundAssignment assignment ->
                referencesAnyVariable(assignment.property.receiver, names)
                    || referencesAnyVariable(assignment.value, names);
            case Expr.ArrayLiteral literal ->
                literal.elements.stream().anyMatch(element -> referencesAnyVariable(element, names));
            case Expr.Index index -> referencesAnyVariable(index.array, names)
                || referencesAnyVariable(index.index, names);
            case Expr.IndexAssignment assignment -> referencesAnyVariable(assignment.array, names)
                || referencesAnyVariable(assignment.index, names)
                || referencesAnyVariable(assignment.value, names);
            case Expr.Grouping grouping -> referencesAnyVariable(grouping.expression, names);
            case Expr.If iff -> referencesAnyVariable(iff.condition, names)
                || referencesAnyVariable(iff.thenExpr, names)
                || referencesAnyVariable(iff.elseExpr, names);
            case Expr.Logical logical -> referencesAnyVariable(logical.left, names)
                || referencesAnyVariable(logical.right, names);
            case Expr.Coalesce coalesce -> referencesAnyVariable(coalesce.left, names)
                || referencesAnyVariable(coalesce.right, names);
            case Expr.Unary unary -> referencesAnyVariable(unary.right, names);
            case Expr.TypeTest test -> referencesAnyVariable(test.value, names);
            case Expr.Cast cast -> referencesAnyVariable(cast.value, names);
            case Expr.Lambda nested -> nested.body.stream().anyMatch(statement ->
                statement instanceof Stmt.Return returned && returned.value() != null
                    && referencesAnyVariable(returned.value(), names));
            case Expr.Literal _ -> false;
        };
        }

    private FunctionDescriptor resolveLambda(final Expr.Lambda lambda,
                                             final FunctionDescriptor expectedType) {
        final var lambdaArity = lambda.params.size();
        if (lambdaArity != expectedType.arity()) {
            Zeron.resolutionError(new ResolutionError(lambda.arrow,
                    "Expected " + expectedType.arity() + " lambda parameters, found " + lambdaArity));
            return expectedType;
        }

        final var enclosingLoopDepth = loopDepth;
        final var enclosingFlow = flowState;
        loopDepth = 0;
        flowState = new FlowState();
        beginScope();
        try {
            for (int i = 0; i < lambda.params.size(); i++) {
                final var parameter = lambda.params.get(i);
                final var parameterType = expectedType.parameters().get(i);
                declare(SYNTHETIC_VAR, parameter, parameterType, BindingMutability.IMMUTABLE);
                define(parameter);
            }

            TypeDescriptor returnType = TypeDescriptor.ofUnit();
            for (final var stmt : lambda.body) {
                if (stmt instanceof Stmt.Return(Expr value)) {
                    returnType = value == null ? TypeDescriptor.ofUnit() : resolve(value);
                } else {
                    resolve(stmt);
                }
            }

            final var unresolvedReturn = TypeSubstitution.containsTypeParameter(expectedType.returnType());
            if (!(expectedType.returnType() instanceof InferDescriptor) && !unresolvedReturn) {
                ensureAssignable(expectedType.returnType(), returnType, lambda.arrow);
            }

            final var resolvedType = expectedType.returnType() instanceof InferDescriptor || unresolvedReturn
                    ? expectedType.toReturnType(returnType)
                    : expectedType;
            lambda.setType(resolvedType);
            return resolvedType;
        } finally {
            endScope();
            loopDepth = enclosingLoopDepth;
            flowState = enclosingFlow;
        }
    }

    private boolean isFunctionReferenceCandidate(final Expr expression) {
        return expression instanceof Expr.Variable variable
                && variable.explicitFunctionTypeArguments.isEmpty()
                && variable.resolvedFunctionName() == null
                && !symbols.containsSymbol(variable.name)
                && resolveFunctionName(variable.name.lexeme(), variable.name) != null;
    }

    private TypeDescriptor resolveGenericCall(final Expr.Call call,
                                              final FunctionDescriptor genericType) {
        call.setGenericFunctionType(genericType);
        final var typeParameters = genericType.typeParameters();
        if (!call.explicitTypeArguments.isEmpty()
                && call.explicitTypeArguments.size() != typeParameters.size()) {
            Zeron.resolutionError(new ResolutionError(call.callee,
                    "Expected " + typeParameters.size() + " type arguments, found "
                            + call.explicitTypeArguments.size() + "."));
        }
        if (genericType.arity() != call.arguments.size()) {
            Zeron.resolutionError(new ResolutionError(call.callee,
                    "Expected " + genericType.arity() + " arguments, found " + call.arguments.size()));
        }

        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < call.explicitTypeArguments.size(); i++) {
            final var explicitType = call.explicitTypeArguments.get(i);
            validateType(explicitType, call.callee);
            substitutions.put(typeParameters.get(i), explicitType);
        }

        final var resolvedArguments = new TypeDescriptor[call.arguments.size()];
        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (argument instanceof Expr.Lambda || isFunctionReferenceCandidate(argument)) continue;
            resolvedArguments[i] = resolve(argument);
            TypeUnifier.unify(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.callee);
        }

        for (int i = 0; i < call.arguments.size(); i++) {
            final var argument = call.arguments.get(i);
            if (!(argument instanceof Expr.Lambda) && !isFunctionReferenceCandidate(argument)) continue;
            final var expected = TypeSubstitution.substitute(genericType.parameters().get(i), substitutions);
            if (functionType(expected) == null) {
                Zeron.resolutionError(new ResolutionError(call.callee,
                        "A function value argument requires a function parameter type."));
            }
            resolvedArguments[i] = resolveArgument(argument, expected);
            TypeUnifier.unify(genericType.parameters().get(i), resolvedArguments[i], substitutions, call.callee);
        }

        for (final var parameter : typeParameters) {
            if (!substitutions.containsKey(parameter)) {
                Zeron.resolutionError(new ResolutionError(call.callee,
                        "Cannot infer type parameter '" + parameter.name()
                                + "'; provide an explicit type argument."));
            }
            if (parameter.bound() != null) {
                final var requiredBound = TypeSubstitution.substitute(parameter.bound(), substitutions);
                ensureAssignable(requiredBound, substitutions.get(parameter), call.callee);
            }
        }

        final var instantiatedParameters = genericType.parameters().stream()
                .map(parameter -> TypeSubstitution.substitute(parameter, substitutions))
                .toList();
        for (int i = 0; i < resolvedArguments.length; i++) {
            ensureAssignable(instantiatedParameters.get(i), resolvedArguments[i], call.callee);
        }
        final var instantiatedReturn = TypeSubstitution.substitute(genericType.returnType(), substitutions);
        call.setType(instantiatedReturn);
        return instantiatedReturn;
    }

    private FunctionDescriptor resolveCallWithTypes(final Token callee, final List<Expr> arguments) {
        // original e.g (#A, #B) -> infer
        final var lambdaTypeDesc = getSymbol(callee);
        // candidate e.g (Int, Int) -> infer
        final var candidateTypeDesc = TypeDescriptor.functionOf(
                "",
                TypeDescriptor.ofInfer(),
                arguments.stream().map(this::resolve).toArray(TypeDescriptor[]::new));

        // assume lambda, so declaration must be a variable
        final var declaration = (Stmt.Var)getDeclaration(callee);
        // assume lambda is inferred, so initializer is not null
        final var lambda = (Expr.Lambda)declaration.initializer();
        final var candidate = new Expr.Lambda(lambda.arrow, lambda.params, lambda.body,
                candidateTypeDesc);
        final var resolvedType = resolveLambda(candidate, candidateTypeDesc);
        lambda.setType(resolvedType);
        if (declaration instanceof Stmt.Var variable) {
            symbols.setResolvedType(variable.name(), resolvedType);
        }
        return resolvedType;
    }

    private TypeDescriptor refineInferredType(final Expr expr,
                                             final TypeDescriptor type,
                                             final TypeDescriptor otherType) {
        if (!(expr instanceof Expr.Variable variable)
                || !(type instanceof InferDescriptor)
                || otherType instanceof InferDescriptor
                || !symbols.containsSymbol(variable.name)
                || !(symbols.getSymbol(variable.name).type() instanceof InferDescriptor)) {
            return type;
        }

        symbols.setResolvedType(variable.name, otherType);
        return otherType;
    }

    private void resolveFunction(final Stmt.Function function) {
        final var enclosingLoopDepth = loopDepth;
        final var enclosingFlow = flowState;
        loopDepth = 0;
        flowState = new FlowState();
        beginScope();
        expectedReturnTypes.push(function.typeDescriptor().returnType());
        try {
            final var paramNames = function.parameters();
            final var params = function.typeDescriptor().parameters();
            for (int i = 0; i < function.parameters().size(); i++) {
                declare(SYNTHETIC_VAR, paramNames.get(i), params.get(i), BindingMutability.IMMUTABLE);
                define(paramNames.get(i));
            }
            resolveStmts(function.body());
            final var resolvedType = ensureReturns(
                    function.name(),
                    function.typeDescriptor().returnType(),
                    function.body());
            if (function.typeDescriptor().returnType() instanceof InferDescriptor) {
                symbols.setResolvedReturnType(functionSymbolToken(function.name()), resolvedType);
            }
                Zeron.debug(" resolved function " + function.name().lexeme()
                    + " -> " + symbols.getFunction(functionSymbolToken(function.name())).type());
        } finally {
            expectedReturnTypes.pop();
            endScope();
            loopDepth = enclosingLoopDepth;
            flowState = enclosingFlow;
        }
    }

    public TypeDescriptor ensureReturns(final Token where,
                                        final TypeDescriptor expectedType,
                                        final List<Stmt> statements) {
        var currentType = expectedType;
        for (final var statement : statements) {
            if (statement instanceof Stmt.Return(Expr value)) {
                final var returnType = value == null
                        ? TypeDescriptor.ofUnit()
                        : resolveArgument(value, expectedType);
                if (currentType instanceof InferDescriptor)
                    currentType = returnType;
                else
                    ensureAssignable(currentType, returnType, where);
            }
        }
        return currentType instanceof InferDescriptor ? TypeDescriptor.ofUnit() : currentType;
    }

    public TypeDescriptor ensureExact(final Token where,
                                      final TypeDescriptor typeA,
                                      final TypeDescriptor typeB) {
        return typeCompatibility.ensureExact(where, typeA, typeB);
    }

    public TypeDescriptor ensureCommonParent(final Token where,
                                             final TypeDescriptor typeA,
                                             final TypeDescriptor typeB) {
        return typeCompatibility.ensureCommonParent(where, typeA, typeB);
    }

    public TypeDescriptor ensureAssignable(TypeDescriptor expectedType, TypeDescriptor resolvedType) {
        return ensureAssignable(expectedType, resolvedType, SYNTHETIC_IDENTIFIER);
    }

    private TypeDescriptor ensureAssignable(TypeDescriptor expectedType,
                                            TypeDescriptor resolvedType,
                                            Token where) {
        return typeCompatibility.ensureAssignable(expectedType, resolvedType, where);
    }

    public boolean isContractProjection(final TypeDescriptor expectedType,
                                        final TypeDescriptor resolvedType) {
        return typeCompatibility.isContractProjection(expectedType, resolvedType);
    }

    public void ensureBoolean(TypeDescriptor type) {
        ensureBoolean(type, SYNTHETIC_IDENTIFIER);
    }

    private void ensureBoolean(TypeDescriptor type, Token where) {
        typeCompatibility.ensureBoolean(type, where);
    }

    public void ensureIterable(final TypeDescriptor type) {
        ensureIterable(type, SYNTHETIC_IDENTIFIER);
    }

    private TypeDescriptor ensureIterable(final TypeDescriptor type, final Token where) {
        final var arrayType = type instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : type;
        if (arrayType instanceof ArrayDescriptor array) return array.elementType();
        final var baseName = className(arrayType);
        final var iterableName = "zeron.collections.Iterable";
        if (baseName.equals(iterableName) && arrayType instanceof GenericDescriptor generic
                && generic.typeParameters().size() == 1) {
            recordIterationProtocol(where, iterableName);
            return generic.typeParameters().getFirst();
        }
        final var declaration = classes.get(baseName);
        if (declaration != null) {
            final var substitutions = substitutionsFor(declaration.typeParameters(), arrayType);
            for (final var contractUse : declaration.contractUses()) {
                if (!iterableName.equals(contractUse.name().lexeme())
                        || contractUse.typeArguments().size() != 1) continue;
                recordIterationProtocol(where, iterableName);
                return TypeSubstitution.substitute(contractUse.typeArguments().getFirst(), substitutions);
            }
        }
        Zeron.resolutionError(new ResolutionError(where,
                "For loops require Array<T>, an integer range literal, or a type conforming to Iterable<T>."));
        return TypeDescriptor.ofInfer();
    }

    private void recordIterationProtocol(final Token iterationBind, final String iterableName) {
        final var iterable = contracts.get(iterableName);
        if (iterable == null) {
            Zeron.resolutionError(new ResolutionError(iterationBind,
                    "Missing iterator protocol contract '" + iterableName + "'."));
        }
        final var iteratorMethod = iterable.methods().stream()
                .filter(method -> method.name().lexeme().equals("iterator"))
                .findFirst()
                .orElseThrow(() -> new ResolutionError(iterationBind,
                        "Iterable contract must declare iterator()."));
        var iteratorType = iteratorMethod.typeDescriptor().returnType();
        if (iteratorType instanceof ReferenceDescriptor reference) iteratorType = reference.baseType();
        final String iteratorName = iteratorType instanceof GenericDescriptor generic
                ? generic.baseType().name()
                : iteratorType instanceof NominalDescriptor nominal ? nominal.name() : null;
        if (iteratorName == null) {
            Zeron.resolutionError(new ResolutionError(iterationBind,
                    "Iterable.iterator() must return an Iterator<T> reference."));
        }
        iterationProtocols.put(iterationBind, new IterationProtocol(iterableName, iteratorName));
    }
}
