package com.maruseron.zeron.domain;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ZeronVersions;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.ast.ImportDeclaration;
import com.maruseron.zeron.ast.NamespaceMembers;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.jar.JarFile;

public record ZeronLibraryIndex(int standardLibraryApiVersion,
                                List<ExportedDeclaration> declarations) {
    private static final int MAGIC = 0x5A415049;
    public static final int VERSION = ZeronVersions.API_INDEX_SCHEMA_VERSION;
    private static final int MAX_ENTRIES = 1_000_000;
    private static final AtomicInteger READ_SCOPE_IDS = new AtomicInteger(-1);

    public ZeronLibraryIndex {
        declarations = List.copyOf(declarations);
    }

        public List<CompilationUnit> toCompilationUnits(final String sourceLabel) {
        final var declarationsByPackage = new LinkedHashMap<String, List<Stmt>>();
        for (final var exported : declarations) {
            final var packageName = switch (exported) {
                case FunctionExport function -> packageName(function.jvmOwner());
                case ExtensionExport extension -> packageName(extension.jvmOwner());
                case ValueExport value -> packageName(value.jvmOwner());
                case WitnessExport witness -> packageName(witness.qualifiedName());
                default -> packageName(exported.qualifiedName());
            };
            final var statements = declarationsByPackage.computeIfAbsent(packageName, _ -> new ArrayList<>());
            switch (exported) {
                case FunctionExport function -> {
                    final var functionName = token(simpleName(function.qualifiedName()));
                    final var declaration = new Stmt.Function(functionName,
                        parameterNames(function.signature(), functionName.line()),
                        function.signature(), List.of(), true, List.of(), function.minimumArity(),
                        function.variadic());
                    if (function.namespaceName() == null) {
                        statements.add(declaration);
                    } else {
                        addNamespaceMember(statements, function.namespaceName(), declaration);
                    }
                }
                case ExtensionExport extension -> {
                    final var name = token(simpleName(extension.qualifiedName()));
                    final var receiverParameterCount = extension.receiverTypeParameterCount();
                    final var typeParameters = extension.signature().typeParameters();
                    final var declaration = new Stmt.ExtensionMethod(name,
                            namedParameters(extension.parameterNames(), extension.signature().arity(), name.line()),
                            extension.signature(),
                            true, extension.mutating(), List.of(), List.of(),
                            extension.minimumArity() + 1, extension.variadic(), extension.receiverType(),
                            typeParameters.subList(0, receiverParameterCount),
                            typeParameters.subList(receiverParameterCount, typeParameters.size()),
                            extension.property());
                    addNamespaceMember(statements, extension.namespaceName(), declaration);
                }
                case ValueExport value -> {
                    final var valueName = token(simpleName(value.qualifiedName()));
                    final var declaration = new Stmt.Var(valueName, value.type(), null,
                            BindingMutability.IMMUTABLE, true);
                    if (value.namespaceName() == null) statements.add(declaration);
                    else addNamespaceMember(statements, value.namespaceName(), declaration);
                }
                case ExternalClassExport externalClass -> {
                    final var className = token(simpleName(externalClass.qualifiedName()));
                    final var constructors = externalClass.constructors().stream()
                            .map(constructor -> new Stmt.ExternalConstructor(token("new"),
                                    parameterNames(constructor.parameterTypes().size(), className.line()),
                                    constructor.parameterTypes(), true))
                            .toList();
                    final var methods = externalClass.methods().stream()
                            .map(method -> new Stmt.ExternalMethod(token(method.signature().name()),
                                    parameterNames(method.signature(), className.line()),
                                    method.signature(), true, method.isStatic(), method.mutating(), List.of(),
                                    method.exceptionMappings().stream()
                                            .map(mapping -> new Stmt.ExternalExceptionMapping(
                                                    TypeDescriptor.ofName(mapping.exceptionType()),
                                                    TypeDescriptor.ofName(mapping.effectType()),
                                                    token(mapping.constructorName())))
                                            .toList()))
                            .toList();
                    final var properties = externalClass.staticProperties().stream()
                            .map(property -> new Stmt.ExternalStaticProperty(
                                    token(property.name()), property.type(), true))
                            .toList();
                    statements.add(new Stmt.ExternalClass(className, externalClass.javaBinaryName(),
                            true, constructors, methods, properties));
                }
                case ClassExport classExport -> {
                    final var className = token(classExport.qualifiedName());
                    final var fields = new ArrayList<Stmt.Field>();
                    for (int i = 0; i < classExport.canonicalConstructorParameters().size(); i++) {
                    final var fieldName = classExport.canonicalConstructorParameterNames().isEmpty()
                            ? "field" + i : classExport.canonicalConstructorParameterNames().get(i);
                    fields.add(new Stmt.Field(token(fieldName),
                        classExport.canonicalConstructorParameters().get(i)));
                    }
                    final var contractUses = classExport.contracts().stream()
                        .map(use -> new Stmt.ContractUse(token(use.qualifiedName()), use.typeArguments()))
                        .toList();
                    final var constructor = new Stmt.Constructor(token("new"),
                        classExport.canonicalConstructorPublic());
                    final var namedConstructors = classExport.namedConstructors().stream()
                        .map(named -> new Stmt.NamedConstructor(token(named.name()),
                            namedParameters(named.parameterNames(), named.signature().arity(), className.line()),
                            named.signature(), true,
                            List.of(), named.variadic()))
                        .toList();
                    final var methods = classExport.methods().stream()
                        .map(method -> new Stmt.Method(token(method.name()),
                            namedParameters(method.parameterNames(), method.signature().arity(), className.line()),
                            method.signature(), true,
                            method.mutating(), List.of(), List.of(), method.minimumArity(), method.variadic()))
                        .toList();
                    final var properties = classExport.properties().stream()
                        .map(property -> new Stmt.Property(token(property.name()), property.type(), null,
                                true, property.mutating(), null, null, null, false))
                        .toList();
                    final var patterns = classExport.patterns().stream()
                            .map(pattern -> new Stmt.Pattern(token(pattern.name()),
                                    pattern.outputs().stream()
                                            .map(output -> new Stmt.PatternOutput(
                                                    token(output.name()), output.type()))
                                            .toList(),
                                    null, List.of(), true, pattern.refutable()))
                            .toList();
                    statements.add(new Stmt.ClassDecl(className, classExport.typeParameters(), contractUses,
                        fields, properties, constructor, namedConstructors, methods, patterns,
                        true, classExport.effect()));
                }
                case ContractExport contract -> {
                    final var contractName = token(contract.qualifiedName());
                    final var constructors = contract.constructors().stream()
                        .map(constructor -> new Stmt.NamedContractConstructor(
                                token(constructor.name()),
                                namedParameters(constructor.parameterNames(),
                                        constructor.signature().arity(), contractName.line()),
                                constructor.signature(),
                                constructor.variadic()))
                        .toList();
                    final var methods = contract.methods().stream()
                        .map(method -> new Stmt.ContractMethod(
                                token(method.name()),
                                namedParameters(method.parameterNames(), method.signature().arity(),
                                        contractName.line()), method.signature(),
                                method.mutating(), method.defaultMethod(), List.of(), List.of(),
                                method.minimumArity(), method.variadic()))
                        .toList();
                    final var properties = contract.properties().stream()
                        .map(property -> new Stmt.ContractProperty(
                                token(property.name()),
                                property.type(), property.mutating()))
                        .toList();
                    final var permittedClasses = contract.permittedClasses().stream()
                        .map(use -> new Stmt.ContractUse(token(use.qualifiedName()), use.typeArguments()))
                        .toList();
                    final var requiredContracts = contract.requiredContracts().stream()
                        .map(use -> new Stmt.ContractUse(token(use.qualifiedName()), use.typeArguments()))
                        .toList();
                    statements.add(new Stmt.ContractDecl(contractName, contract.typeParameters(),
                            requiredContracts, constructors, methods, properties, true,
                            contract.sealed(), permittedClasses));
                }
                case SumExport sum -> {
                    final var sumName = token(sum.qualifiedName());
                    final var cases = sum.cases().stream()
                            .map(sumCase -> new Stmt.SumCase(token(sumCase.name()),
                                    sumCase.payloads().stream()
                                            .map(payload -> new Stmt.SumPayload(
                                                    token(payload.name()), payload.type()))
                                            .toList()))
                            .toList();
                    statements.add(new Stmt.SumDecl(sumName, sum.typeParameters(), cases, true));
                }
                case ProductExport product -> {
                    final var productName = token(product.qualifiedName());
                    final var components = product.components().stream()
                            .map(component -> new Stmt.ProductComponent(token(component.name()), component.type()))
                            .toList();
                    statements.add(new Stmt.ProductDecl(productName, product.typeParameters(), components, true));
                }
                case NewtypeExport newtype -> {
                    final Expr validator = newtype.validated()
                            ? new Expr.Literal(Boolean.TRUE, TypeDescriptor.ofBoolean()) : null;
                    statements.add(new Stmt.NewtypeDecl(token(newtype.qualifiedName()),
                            newtype.underlyingType(), validator, true));
                }
                case WitnessExport witness -> {
                    final var mappings = witness.mappings().stream()
                            .map(mapping -> new Stmt.WitnessFactoryMapping(token(mapping.requirementName()),
                                    mapping.targetType(), token(mapping.constructorName())))
                            .toList();
                    final var methods = witness.methods().stream().map(method -> {
                        final var source = new Stmt.Function(token(method.requirementName()),
                                parameterNames(method.methodSignature(), 0), method.methodSignature(),
                                List.of(), false, List.of(), method.minimumArity(), method.variadic());
                        final var helperName = token(method.helperName());
                        final var helperParameters = new ArrayList<Token>();
                        helperParameters.add(new Token(TokenType.THIS, "this", null, -1));
                        helperParameters.addAll(parameterNames(method.helperType(), 0).stream().skip(1).toList());
                        final var helper = new Stmt.Function(helperName, helperParameters,
                                method.helperType(), List.of(), false, List.of(),
                                method.helperMinimumArity(), method.variadic());
                        return new Stmt.WitnessMethod(token(method.requirementName()), source, helper);
                    }).toList();
                    statements.add(new Stmt.Witness(token(simpleName(witness.qualifiedName())),
                            witness.typeParameters(), witness.contractType(), witness.targetType(), mappings,
                            methods, witness.derived(), witness.requirements().stream()
                                    .map(requirement -> new Stmt.WitnessRequirement(
                                            requirement.contractType(), requirement.targetType()))
                                    .toList()));
                }
            }
        }
        return declarationsByPackage.entrySet().stream()
            .map(entry -> new CompilationUnit(sourceLabel + "#" + entry.getKey(), entry.getKey(),
                List.<ImportDeclaration>of(), entry.getValue(), true))
            .toList();
        }

    public sealed interface ExportedDeclaration permits NamespacedDeclaration, ClassExport, ContractExport,
            SumExport, ProductExport, NewtypeExport, WitnessExport, ExternalClassExport {
        String qualifiedName();
    }

    public sealed interface NamespacedDeclaration extends ExportedDeclaration permits FunctionExport, ExtensionExport, ValueExport {
        String namespaceName();
        String jvmOwner();
    }

    public record ValueExport(String qualifiedName,
                              String jvmOwner,
                              String initializationOwner,
                              String namespaceName,
                              TypeDescriptor type) implements NamespacedDeclaration {
        public ValueExport {
            Objects.requireNonNull(qualifiedName);
            Objects.requireNonNull(jvmOwner);
            Objects.requireNonNull(initializationOwner);
            Objects.requireNonNull(type);
        }
    }

    public record ExternalClassExport(String qualifiedName,
                                      String javaBinaryName,
                                      List<ExternalConstructorExport> constructors,
                                      List<ExternalMethodExport> methods,
                                      List<ExternalStaticPropertyExport> staticProperties)
            implements ExportedDeclaration {
        public ExternalClassExport {
            Objects.requireNonNull(qualifiedName);
            Objects.requireNonNull(javaBinaryName);
            constructors = List.copyOf(constructors);
            methods = List.copyOf(methods);
            staticProperties = List.copyOf(staticProperties);
            if (qualifiedName.isEmpty() || !qualifiedName.equals(javaBinaryName)) {
                throw new IllegalArgumentException(
                        "External class exports must use their Java binary name as the qualified name.");
            }
        }
    }

    public record ExternalConstructorExport(List<TypeDescriptor> parameterTypes) {
        public ExternalConstructorExport {
            parameterTypes = List.copyOf(parameterTypes);
        }
    }

    public record ExternalMethodExport(FunctionDescriptor signature, boolean isStatic, boolean mutating,
                                       List<ExternalExceptionMappingExport> exceptionMappings) {
        public ExternalMethodExport {
            Objects.requireNonNull(signature);
            exceptionMappings = List.copyOf(exceptionMappings);
        }
    }

    public record ExternalExceptionMappingExport(String exceptionType, String effectType,
                                                 String constructorName) {}

    public record ExternalStaticPropertyExport(String name, TypeDescriptor type) {
        public ExternalStaticPropertyExport {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
        }
    }

    public record ExtensionExport(String qualifiedName, String jvmOwner, String namespaceName,
                                 TypeDescriptor receiverType, int receiverTypeParameterCount,
                                 FunctionDescriptor signature, boolean mutating, int minimumArity,
                                 boolean variadic, boolean property,
                                 List<String> parameterNames) implements NamespacedDeclaration {
        public ExtensionExport {
            Objects.requireNonNull(qualifiedName);
            Objects.requireNonNull(jvmOwner);
            Objects.requireNonNull(namespaceName);
            Objects.requireNonNull(receiverType);
            Objects.requireNonNull(signature);
            parameterNames = List.copyOf(parameterNames);
            if (!parameterNames.isEmpty() && parameterNames.size() != signature.arity()) {
                throw new IllegalArgumentException("Extension parameter names must match its signature.");
            }
            if (signature.parameters().isEmpty()
                    || receiverTypeParameterCount < 0
                    || receiverTypeParameterCount > signature.typeParameters().size()
                    || property && (mutating || signature.parameters().size() != 1
                            || minimumArity != 0 || variadic)) {
                throw new IllegalArgumentException("Invalid extension export.");
            }
            validateMinimumArity(TypeDescriptor.functionOf(signature.name(), signature.returnType(),
                    signature.parameters().subList(1, signature.parameters().size())
                            .toArray(TypeDescriptor[]::new)),
                    minimumArity, variadic);
        }

        public ExtensionExport(String qualifiedName, String jvmOwner, String namespaceName,
                               TypeDescriptor receiverType, int receiverTypeParameterCount,
                               FunctionDescriptor signature, boolean mutating, int minimumArity,
                               boolean variadic) {
            this(qualifiedName, jvmOwner, namespaceName, receiverType, receiverTypeParameterCount,
                    signature, mutating, minimumArity, variadic, false, List.of());
        }

        public ExtensionExport(String qualifiedName, String jvmOwner, String namespaceName,
                               TypeDescriptor receiverType, int receiverTypeParameterCount,
                               FunctionDescriptor signature, boolean mutating, int minimumArity,
                               boolean variadic, boolean property) {
            this(qualifiedName, jvmOwner, namespaceName, receiverType, receiverTypeParameterCount,
                    signature, mutating, minimumArity, variadic, property, List.of());
        }
    }

    public record FunctionExport(String qualifiedName,
                                 String jvmOwner,
                                 String namespaceName,
                                 FunctionDescriptor signature,
                                 int minimumArity,
                                 boolean variadic) implements NamespacedDeclaration {
        public FunctionExport {
            Objects.requireNonNull(qualifiedName);
            Objects.requireNonNull(jvmOwner);
            Objects.requireNonNull(signature);
            validateMinimumArity(signature, minimumArity, variadic);
        }

        public FunctionExport(String qualifiedName, String jvmOwner, String namespaceName,
                              FunctionDescriptor signature) {
            this(qualifiedName, jvmOwner, namespaceName, signature, signature.arity(), false);
        }

        public FunctionExport(String qualifiedName, String jvmOwner, String namespaceName,
                              FunctionDescriptor signature, int minimumArity) {
            this(qualifiedName, jvmOwner, namespaceName, signature, minimumArity, false);
        }
    }

    public record ClassExport(String qualifiedName,
                              List<TypeParameterDescriptor> typeParameters,
                              List<ContractUseExport> contracts,
                              List<TypeDescriptor> canonicalConstructorParameters,
                              List<String> canonicalConstructorParameterNames,
                              boolean canonicalConstructorPublic,
                              boolean effect,
                              List<NamedConstructorExport> namedConstructors,
                              List<MethodExport> methods,
                              List<PropertyExport> properties,
                              List<PatternExport> patterns) implements ExportedDeclaration {
        public ClassExport {
            Objects.requireNonNull(qualifiedName);
            typeParameters = List.copyOf(typeParameters);
            contracts = List.copyOf(contracts);
            canonicalConstructorParameters = List.copyOf(canonicalConstructorParameters);
            canonicalConstructorParameterNames = List.copyOf(canonicalConstructorParameterNames);
            if (!canonicalConstructorParameterNames.isEmpty()
                    && canonicalConstructorParameterNames.size() != canonicalConstructorParameters.size()) {
                throw new IllegalArgumentException("Canonical constructor parameter names must match its signature.");
            }
            namedConstructors = List.copyOf(namedConstructors);
            methods = List.copyOf(methods);
            properties = List.copyOf(properties);
            patterns = List.copyOf(patterns);
        }

        public ClassExport(String qualifiedName,
                           List<TypeParameterDescriptor> typeParameters,
                           List<ContractUseExport> contracts,
                           List<TypeDescriptor> canonicalConstructorParameters,
                           boolean canonicalConstructorPublic,
                           boolean effect,
                           List<NamedConstructorExport> namedConstructors,
                           List<MethodExport> methods,
                           List<PropertyExport> properties) {
            this(qualifiedName, typeParameters, contracts, canonicalConstructorParameters,
                    List.of(), canonicalConstructorPublic, effect, namedConstructors, methods, properties, List.of());
        }

        public ClassExport(String qualifiedName,
                           List<TypeParameterDescriptor> typeParameters,
                           List<ContractUseExport> contracts,
                           List<TypeDescriptor> canonicalConstructorParameters,
                           boolean canonicalConstructorPublic,
                           boolean effect,
                           List<NamedConstructorExport> namedConstructors,
                           List<MethodExport> methods,
                           List<PropertyExport> properties,
                           List<PatternExport> patterns) {
            this(qualifiedName, typeParameters, contracts, canonicalConstructorParameters, List.of(),
                    canonicalConstructorPublic, effect, namedConstructors, methods, properties, patterns);
        }
    }

    public record ContractExport(String qualifiedName,
                                 List<TypeParameterDescriptor> typeParameters,
                                 List<ContractUseExport> requiredContracts,
                                 List<NamedConstructorExport> constructors,
                                 List<MethodExport> methods,
                                 List<PropertyExport> properties,
                                 boolean sealed,
                                 List<ContractUseExport> permittedClasses) implements ExportedDeclaration {
        public ContractExport {
            Objects.requireNonNull(qualifiedName);
            typeParameters = List.copyOf(typeParameters);
            requiredContracts = List.copyOf(requiredContracts);
            constructors = List.copyOf(constructors);
            methods = List.copyOf(methods);
            properties = List.copyOf(properties);
            permittedClasses = List.copyOf(permittedClasses);
        }

        public ContractExport(String qualifiedName,
                              List<TypeParameterDescriptor> typeParameters,
                              List<NamedConstructorExport> constructors,
                              List<MethodExport> methods,
                              List<PropertyExport> properties) {
            this(qualifiedName, typeParameters, List.of(), constructors, methods, properties, false, List.of());
        }

        public ContractExport(String qualifiedName,
                              List<TypeParameterDescriptor> typeParameters,
                              List<NamedConstructorExport> constructors,
                              List<MethodExport> methods,
                              List<PropertyExport> properties,
                              boolean sealed,
                              List<ContractUseExport> permittedClasses) {
            this(qualifiedName, typeParameters, List.of(), constructors, methods, properties,
                    sealed, permittedClasses);
        }
    }

    public record SumExport(String qualifiedName, List<TypeParameterDescriptor> typeParameters,
                            List<SumCaseExport> cases) implements ExportedDeclaration {
        public SumExport {
            Objects.requireNonNull(qualifiedName);
            typeParameters = List.copyOf(typeParameters);
            cases = List.copyOf(cases);
        }
    }

    public record SumCaseExport(String name, List<SumPayloadExport> payloads) {
        public SumCaseExport {
            Objects.requireNonNull(name);
            payloads = List.copyOf(payloads);
        }
    }

    public record ProductExport(String qualifiedName, List<TypeParameterDescriptor> typeParameters,
                                List<ProductComponentExport> components) implements ExportedDeclaration {
        public ProductExport {
            Objects.requireNonNull(qualifiedName);
            typeParameters = List.copyOf(typeParameters);
            components = List.copyOf(components);
        }
    }

    public record ProductComponentExport(String name, TypeDescriptor type) {
        public ProductComponentExport {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
        }
    }

    public record NewtypeExport(String qualifiedName, TypeDescriptor underlyingType,
                                boolean validated) implements ExportedDeclaration {
        public NewtypeExport {
            Objects.requireNonNull(qualifiedName);
            Objects.requireNonNull(underlyingType);
            if (!(underlyingType instanceof IntDescriptor || underlyingType instanceof FloatDescriptor
                    || underlyingType instanceof BooleanDescriptor || underlyingType instanceof StringDescriptor)) {
                throw new IllegalArgumentException("Unsupported newtype representation.");
            }
        }
    }

    public record SumPayloadExport(String name, TypeDescriptor type) {
        public SumPayloadExport {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
        }
    }

    public record ContractUseExport(String qualifiedName, List<TypeDescriptor> typeArguments) {
        public ContractUseExport {
            Objects.requireNonNull(qualifiedName);
            typeArguments = List.copyOf(typeArguments);
        }

    }

    public record WitnessExport(String qualifiedName, List<TypeParameterDescriptor> typeParameters,
                                TypeDescriptor contractType, TypeDescriptor targetType,
                                List<WitnessFactoryMappingExport> mappings,
                                List<WitnessMethodExport> methods, boolean derived,
                                List<WitnessRequirementExport> requirements) implements ExportedDeclaration {
        public WitnessExport {
            Objects.requireNonNull(qualifiedName);
            typeParameters = List.copyOf(typeParameters);
            Objects.requireNonNull(contractType);
            Objects.requireNonNull(targetType);
            mappings = List.copyOf(mappings);
            methods = List.copyOf(methods);
            requirements = List.copyOf(requirements);
        }

        public WitnessExport(String qualifiedName, List<TypeParameterDescriptor> typeParameters,
                             TypeDescriptor contractType, TypeDescriptor targetType,
                             List<WitnessFactoryMappingExport> mappings,
                             List<WitnessMethodExport> methods) {
            this(qualifiedName, typeParameters, contractType, targetType, mappings,
                    methods, false, List.of());
        }

        public WitnessExport(String qualifiedName, List<TypeParameterDescriptor> typeParameters,
                             TypeDescriptor contractType, TypeDescriptor targetType,
                             List<WitnessFactoryMappingExport> mappings) {
            this(qualifiedName, typeParameters, contractType, targetType, mappings, List.of(),
                    false, List.of());
        }
    }

    public record WitnessRequirementExport(TypeDescriptor contractType, TypeDescriptor targetType) {
        public WitnessRequirementExport {
            Objects.requireNonNull(contractType);
            Objects.requireNonNull(targetType);
        }
    }

    private static void addDerivedWitnessExports(
            final List<ExportedDeclaration> exports, final String packageName,
            final Token typeName, final List<TypeParameterDescriptor> typeParameters,
            final List<Token> derivedContracts, final List<TypeDescriptor> componentTypes) {
        final var target = typeParameters.isEmpty()
                ? (TypeDescriptor) TypeDescriptor.ofName(typeName.lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(typeName.lexeme()),
                        typeParameters.stream().map(parameter -> (TypeDescriptor) parameter).toList());
        for (final var derived : derivedContracts) {
            final var contractName = switch (derived.lexeme()) {
                case "Eq" -> "zeron.lang.Eq";
                case "Hashable" -> "zeron.lang.Hashable";
                default -> null;
            };
            if (contractName == null) continue;
            final var requiredContract = TypeDescriptor.genericOf(TypeDescriptor.ofName(contractName),
                    List.of(target));
            final var componentContractName = contractName.equals("zeron.lang.Eq")
                    ? "zeron.lang.Eq" : "zeron.lang.Hashable";
            final var requirements = componentTypes.stream()
                    .filter(componentType -> !isDirectlyRecursive(componentType, target))
                    .map(componentType -> new WitnessRequirementExport(
                            TypeDescriptor.genericOf(TypeDescriptor.ofName(componentContractName),
                                    List.of(componentType)), componentType))
                    .toList();
            final var simpleName = typeName.lexeme().substring(typeName.lexeme().lastIndexOf('.') + 1);
            final var qualifiedName = qualify(packageName,
                    "$zeron$derived$" + simpleName + "$" + derived.lexeme());
            exports.add(new WitnessExport(qualifiedName, typeParameters, requiredContract, target,
                    List.of(), List.of(), true, requirements));
        }
    }

    private static List<TypeDescriptor> derivedComponentTypes(final Stmt.ClassDecl declaration) {
        final var typesByName = new LinkedHashMap<String, TypeDescriptor>();
        declaration.fields().forEach(field -> typesByName.put(field.name().lexeme(), field.type()));
        declaration.properties().stream().filter(property -> !property.isCustom())
                .forEach(property -> typesByName.put(property.name().lexeme(), property.type()));
        if (declaration.storedStateOrder().isEmpty()) return List.copyOf(typesByName.values());
        return declaration.storedStateOrder().stream().map(Token::lexeme)
                .filter(typesByName::containsKey).map(typesByName::get).toList();
    }

    private static boolean isDirectlyRecursive(final TypeDescriptor component,
                                               final TypeDescriptor target) {
        final var unwrapped = component instanceof NullableDescriptor nullable ? nullable.baseType() : component;
        return unwrapped.equals(target);
    }

    public record WitnessMethodExport(String requirementName, String helperQualifiedName,
                                      String helperJvmOwner, String helperName,
                                      FunctionDescriptor methodSignature,
                                      FunctionDescriptor helperType,
                                      int minimumArity, int helperMinimumArity,
                                      boolean variadic) {
        public WitnessMethodExport {
            Objects.requireNonNull(requirementName);
            Objects.requireNonNull(helperQualifiedName);
            Objects.requireNonNull(helperJvmOwner);
            Objects.requireNonNull(helperName);
            Objects.requireNonNull(methodSignature);
            Objects.requireNonNull(helperType);
        }
    }

    public record WitnessFactoryMappingExport(String requirementName, TypeDescriptor targetType,
                                              String constructorName) {
        public WitnessFactoryMappingExport {
            Objects.requireNonNull(requirementName);
            Objects.requireNonNull(targetType);
            Objects.requireNonNull(constructorName);
        }
    }

    public record NamedConstructorExport(String name, FunctionDescriptor signature, boolean variadic,
                                         List<String> parameterNames) {
        public NamedConstructorExport {
            Objects.requireNonNull(name);
            Objects.requireNonNull(signature);
            parameterNames = List.copyOf(parameterNames);
            if (!parameterNames.isEmpty() && parameterNames.size() != signature.arity()) {
                throw new IllegalArgumentException("Constructor parameter names must match its signature.");
            }
            validateMinimumArity(signature, signature.arity() - (variadic ? 1 : 0), variadic);
        }

        public NamedConstructorExport(String name, FunctionDescriptor signature, boolean variadic) {
            this(name, signature, variadic, List.of());
        }

        public NamedConstructorExport(String name, FunctionDescriptor signature) {
            this(name, signature, false, List.of());
        }
    }

    public record MethodExport(String name, FunctionDescriptor signature,
                               boolean mutating, boolean defaultMethod, int minimumArity,
                               boolean variadic, List<String> parameterNames) {
        public MethodExport {
            Objects.requireNonNull(name);
            Objects.requireNonNull(signature);
            parameterNames = List.copyOf(parameterNames);
            if (!parameterNames.isEmpty() && parameterNames.size() != signature.arity()) {
                throw new IllegalArgumentException("Method parameter names must match its signature.");
            }
            validateMinimumArity(signature, minimumArity, variadic);
        }

        public MethodExport(String name, FunctionDescriptor signature,
                            boolean mutating, boolean defaultMethod, int minimumArity, boolean variadic) {
            this(name, signature, mutating, defaultMethod, minimumArity, variadic, List.of());
        }

        public MethodExport(String name, FunctionDescriptor signature, boolean mutating) {
            this(name, signature, mutating, false, signature.arity(), false, List.of());
        }

        public MethodExport(String name, FunctionDescriptor signature,
                            boolean mutating, boolean defaultMethod) {
            this(name, signature, mutating, defaultMethod, signature.arity(), false, List.of());
        }

        public MethodExport(String name, FunctionDescriptor signature,
                            boolean mutating, boolean defaultMethod, int minimumArity) {
            this(name, signature, mutating, defaultMethod, minimumArity, false, List.of());
        }
    }

    private static void validateMinimumArity(final FunctionDescriptor signature, final int minimumArity,
                                             final boolean variadic) {
        if (variadic && (signature.parameters().isEmpty()
                || !(signature.parameters().getLast() instanceof ArrayDescriptor))) {
            throw new IllegalArgumentException("Variadic callable must end in an array parameter.");
        }
        final var fixedArity = signature.arity() - (variadic ? 1 : 0);
        if (minimumArity < 0 || minimumArity > fixedArity) {
            throw new IllegalArgumentException("Invalid minimum arity for exported callable.");
        }
    }

    public record PropertyExport(String name, TypeDescriptor type, boolean mutating) {
        public PropertyExport {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
        }
    }

    public record PatternExport(String name, List<PatternOutputExport> outputs, boolean refutable) {
        public PatternExport {
            Objects.requireNonNull(name);
            outputs = List.copyOf(outputs);
        }
    }

    public record PatternOutputExport(String name, TypeDescriptor type) {
        public PatternOutputExport {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
        }
    }

    public static ZeronLibraryIndex fromCompilation(final List<CompilationUnit> units,
                                                    final Function<Stmt.FunctionDeclaration, String> functionOwners,
                                                    final Function<Stmt.FunctionDeclaration, FunctionDescriptor> functionTypes,
                                                    final Function<Stmt.Var, TypeDescriptor> valueTypes,
                                                    final Function<Stmt.Var, String> valueOwners,
                                                    final String initializationOwner,
                                                    final boolean includeBundledSources) {
        final var exports = new ArrayList<ExportedDeclaration>();
        var witnessIndex = 0;
        for (final var unit : units) {
            if (unit.metadataOnly()
                    || !includeBundledSources && StandardLibrary.isBundledSourcePath(unit.sourcePath())) continue;
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                final var declaration = member.declaration();
                switch (declaration) {
                    case Stmt.ExtensionMethod extension when extension.isPublic() -> {
                        final var name = NamespaceMembers.qualifiedName(unit.packageName(),
                                member.namespaceName(), extension.name().lexeme());
                        final var owner = functionOwners.apply(extension);
                        if (owner == null) {
                            throw new IllegalStateException("Missing resolved owner for extension " + name);
                        }
                        exports.add(new ExtensionExport(name, owner, member.namespaceName(),
                                extension.receiverType(), extension.receiverTypeParameters().size(),
                                functionTypes.apply(extension), extension.isMutating(),
                                extension.minimumCallArity(), extension.variadic(), extension.property(),
                                extension.parameters().stream().map(Token::lexeme).toList()));
                    }
                    case Stmt.FunctionDeclaration function when function.isPublic() -> {
                        final var name = member.namespaceName() == null
                                ? qualify(unit.packageName(), function.name().lexeme())
                                : NamespaceMembers.qualifiedName(unit.packageName(), member.namespaceName(),
                                        function.name().lexeme());
                        final var signature = functionTypes.apply(function);
                        final var owner = functionOwners.apply(function);
                        if (signature == null || owner == null) {
                            throw new IllegalStateException("Missing resolved signature or owner for " + name);
                        }
                        exports.add(new FunctionExport(name, owner, member.namespaceName(),
                                signature, function.minimumArity(), function.variadic()));
                    }
                    case Stmt.Var variable when variable.isPublic() -> {
                        final var name = member.namespaceName() == null
                                ? qualify(unit.packageName(), variable.name().lexeme())
                                : NamespaceMembers.qualifiedName(
                                        unit.packageName(), member.namespaceName(), variable.name().lexeme());
                        final var owner = valueOwners.apply(variable);
                        if (owner == null) {
                            throw new IllegalStateException("Missing resolved owner for value " + name);
                        }
                        exports.add(new ValueExport(name, owner, initializationOwner,
                                member.namespaceName(), valueTypes.apply(variable)));
                    }
                    case Stmt.ClassDecl classDeclaration when classDeclaration.isPublic() -> {
                        exports.add(new ClassExport(qualify(unit.packageName(), classDeclaration.name().lexeme()),
                                classDeclaration.typeParameters(),
                                classDeclaration.contractUses().stream()
                                        .map(use -> new ContractUseExport(use.name().lexeme(), use.typeArguments()))
                                        .toList(),
                                classDeclaration.constructor().isPublic()
                                        ? classDeclaration.canonicalConstructorTypes()
                                        : List.of(),
                                classDeclaration.constructor().isPublic()
                                        ? canonicalConstructorParameterNames(classDeclaration) : List.of(),
                                classDeclaration.constructor().isPublic(),
                                classDeclaration.isEffect(),
                                classDeclaration.namedConstructors().stream()
                                        .filter(Stmt.NamedConstructor::isPublic)
                                        .map(constructor -> new NamedConstructorExport(
                                                constructor.name().lexeme(), constructor.typeDescriptor(),
                                                constructor.variadic(),
                                                constructor.parameters().stream().map(Token::lexeme).toList()))
                                        .toList(),
                                classDeclaration.methods().stream()
                                        .filter(Stmt.Method::isPublic)
                                        .map(method -> new MethodExport(method.name().lexeme(),
                                                method.typeDescriptor(), method.isMutating(), false,
                                                method.minimumArity(), method.variadic(),
                                                method.parameters().stream().map(Token::lexeme).toList()))
                                        .toList(),
                                classDeclaration.properties().stream()
                                        .filter(Stmt.Property::isPublic)
                                        .map(property -> new PropertyExport(property.name().lexeme(),
                                                property.type(), property.isMutating()))
                                        .toList(),
                                classDeclaration.patterns().stream()
                                        .filter(Stmt.Pattern::isPublic)
                                        .map(pattern -> new PatternExport(pattern.name().lexeme(),
                                                pattern.outputs().stream()
                                                        .map(output -> new PatternOutputExport(
                                                                output.name().lexeme(), output.type()))
                                                        .toList(),
                                                pattern.refutable()))
                                        .toList()));
                        addDerivedWitnessExports(exports, unit.packageName(), classDeclaration.name(),
                                classDeclaration.typeParameters(),
                                classDeclaration.derivedContracts(), derivedComponentTypes(classDeclaration));
                    }
                    case Stmt.ExternalClass externalClass when externalClass.isPublic() -> {
                        exports.add(new ExternalClassExport(
                                qualify(unit.packageName(), externalClass.name().lexeme()),
                                externalClass.javaBinaryName(),
                                externalClass.constructors().stream()
                                        .filter(Stmt.ExternalConstructor::isPublic)
                                        .map(constructor -> new ExternalConstructorExport(
                                                constructor.parameterTypes()))
                                        .toList(),
                                externalClass.methods().stream()
                                        .filter(Stmt.ExternalMethod::isPublic)
                                        .map(method -> new ExternalMethodExport(
                                                method.typeDescriptor(), method.isStatic(), method.isMutating(),
                                                method.exceptionMappings().stream()
                                                        .map(mapping -> new ExternalExceptionMappingExport(
                                                                mapping.javaExceptionType().name(),
                                                                mapping.effectType().name(),
                                                                mapping.constructorName().lexeme()))
                                                        .toList()))
                                        .toList(),
                                externalClass.staticProperties().stream()
                                        .filter(Stmt.ExternalStaticProperty::isPublic)
                                        .map(property -> new ExternalStaticPropertyExport(
                                                property.name().lexeme(), property.type()))
                                        .toList()));
                    }
                    case Stmt.SumDecl sum when sum.isPublic() -> {
                        exports.add(new SumExport(qualify(unit.packageName(), sum.name().lexeme()),
                                sum.typeParameters(),
                                sum.cases().stream()
                                        .map(sumCase -> new SumCaseExport(sumCase.name().lexeme(),
                                                sumCase.payloads().stream()
                                                        .map(payload -> new SumPayloadExport(
                                                                payload.name().lexeme(), payload.type()))
                                                        .toList()))
                                        .toList()));
                            addDerivedWitnessExports(exports, unit.packageName(), sum.name(),
                                    sum.typeParameters(), sum.derivedContracts(),
                                    sum.cases().stream().flatMap(sumCase -> sumCase.payloads().stream())
                                            .map(Stmt.SumPayload::type).toList());
                    }
                    case Stmt.ProductDecl product when product.isPublic() -> {
                            exports.add(new ProductExport(qualify(unit.packageName(), product.name().lexeme()),
                                        product.typeParameters(),
                                        product.components().stream()
                                                .map(component -> new ProductComponentExport(
                                                        component.name().lexeme(), component.type()))
                                                .toList()));
                            addDerivedWitnessExports(exports, unit.packageName(), product.name(),
                                    product.typeParameters(), product.derivedContracts(),
                                    product.components().stream().map(Stmt.ProductComponent::type).toList());
                    }
                    case Stmt.NewtypeDecl newtype when newtype.isPublic() -> {
                            exports.add(new NewtypeExport(qualify(unit.packageName(), newtype.name().lexeme()),
                                        newtype.underlyingType(), newtype.validator() != null));
                            addDerivedWitnessExports(exports, unit.packageName(), newtype.name(),
                                    List.of(), newtype.derivedContracts(), List.of(newtype.underlyingType()));
                    }
                    case Stmt.ContractDecl contract when contract.isPublic() ->
                            exports.add(new ContractExport(qualify(unit.packageName(), contract.name().lexeme()),
                                    contract.typeParameters(),
                                    contract.requiredContracts().stream()
                                            .map(use -> new ContractUseExport(
                                                    use.name().lexeme(), use.typeArguments()))
                                            .toList(),
                                    contract.namedConstructors().stream()
                                            .map(constructor -> new NamedConstructorExport(
                                                    constructor.name().lexeme(),
                                                    constructor.typeDescriptor(),
                                                    constructor.variadic(),
                                                    constructor.parameters().stream().map(Token::lexeme).toList()))
                                            .toList(),
                                    contract.methods().stream()
                                            .map(method -> new MethodExport(method.name().lexeme(),
                                                    method.typeDescriptor(), method.isMutating(),
                                                    method.isDefault(), method.minimumArity(),
                                                    method.variadic(),
                                                    method.parameters().stream().map(Token::lexeme).toList()))
                                            .toList(),
                                    contract.properties().stream()
                                            .map(property -> new PropertyExport(property.name().lexeme(),
                                                    property.type(), property.isMutating()))
                                            .toList(),
                                    contract.isSealed(),
                                    contract.permittedClasses().stream()
                                            .map(use -> new ContractUseExport(
                                                    use.name().lexeme(), use.typeArguments()))
                                            .toList()));
                    case Stmt.Witness witness -> {
                        final var qualifiedName = (unit.packageName().isEmpty() ? ""
                                : unit.packageName() + ".") + "$zeron$witness$" + witnessIndex++;
                        exports.add(new WitnessExport(qualifiedName, witness.typeParameters(),
                                witness.contractType(), witness.targetType(),
                                witness.mappings().stream()
                                        .map(mapping -> new WitnessFactoryMappingExport(
                                                mapping.requirementName().lexeme(),
                                                mapping.targetType(),
                                                mapping.constructorName().lexeme()))
                                        .toList(),
                                witness.methods().stream().map(method -> {
                                    final var helperName = method.implementation().name().lexeme();
                                    final var helperQualifiedName = qualify(unit.packageName(), helperName);
                                    final var helperOwner = functionOwners.apply(method.implementation());
                                    if (helperOwner == null) {
                                        throw new IllegalStateException(
                                                "Missing resolved owner for witness method " + helperQualifiedName);
                                    }
                                    return new WitnessMethodExport(method.requirementName().lexeme(),
                                            helperQualifiedName, helperOwner, helperName,
                                            method.methodSignature().typeDescriptor(),
                                            method.implementation().typeDescriptor(),
                                            method.methodSignature().minimumArity(),
                                            method.implementation().minimumArity(),
                                            method.methodSignature().variadic());
                                }).toList(), witness.derived(),
                                witness.requirements().stream()
                                        .map(requirement -> new WitnessRequirementExport(
                                                requirement.contractType(), requirement.targetType()))
                                        .toList()));
                    }
                    default -> {}
                }
            }
        }
        exports.sort(Comparator.comparing(ExportedDeclaration::qualifiedName)
                .thenComparing(declaration -> declaration.getClass().getName()));
        return new ZeronLibraryIndex(StandardLibrary.API_VERSION, exports);
    }

    private static List<String> canonicalConstructorParameterNames(final Stmt.ClassDecl declaration) {
        final var names = new ArrayList<String>();
        declaration.fields().stream()
                .filter(field -> field.initializer() == null)
                .map(field -> field.name().lexeme())
                .forEach(names::add);
        declaration.properties().stream()
                .filter(Stmt.Property::canonicalConstructorParameter)
                .map(property -> property.name().lexeme())
                .forEach(names::add);
        return List.copyOf(names);
    }

    public void writeTo(final Path path) throws IOException {
        final var parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (final var output = new DataOutputStream(Files.newOutputStream(path))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(standardLibraryApiVersion);
            output.writeInt(declarations.size());
            for (final var declaration : declarations) writeDeclaration(output, declaration);
        }
    }

    public static ZeronLibraryIndex readFrom(final Path path) throws IOException {
        try (final var input = new DataInputStream(Files.newInputStream(path))) {
            return readFrom(input);
        }
    }

    public static ZeronLibraryIndex readFromJar(final Path path) throws IOException {
        final var index = readJarIndex(path);
        validateStandardLibraryCompatibility(index);
        return index;
    }

    private static ZeronLibraryIndex readJarIndex(final Path path) throws IOException {
        try (final var jar = new JarFile(path.toFile())) {
            final var indexEntry = jar.getJarEntry(ZeronVersions.API_INDEX_JAR_ENTRY);
            if (indexEntry == null || indexEntry.isDirectory()) {
                throw new IOException("Missing Zeron API index in library JAR: " + path);
            }
            try (final var input = new DataInputStream(jar.getInputStream(indexEntry))) {
                return readFrom(input);
            }
        }
    }

    private static ZeronLibraryIndex readFrom(final DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Not a Zeron library index.");
        final var version = input.readInt();
        if (version != VERSION) throw new IOException("Unsupported Zeron library index version: " + version);
        final var standardLibraryApiVersion = input.readInt();
        final var count = readCount(input);
        final var declarations = new ArrayList<ExportedDeclaration>(count);
        for (int i = 0; i < count; i++) declarations.add(readDeclaration(input));
        if (input.read() != -1) throw new IOException("Trailing data in Zeron library index.");
        return new ZeronLibraryIndex(standardLibraryApiVersion, declarations);
    }

    public static ZeronLibraryIndex readFromDirectory(final Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Zeron library root is not a class directory: " + root);
        }
        final var indexPath = root.resolve(ZeronVersions.API_INDEX_RELATIVE_PATH);
        if (!Files.isRegularFile(indexPath)) {
            throw new IOException("Missing Zeron API index: " + indexPath);
        }
        final var index = readFrom(indexPath);
        validateStandardLibraryCompatibility(index);
        return index;
    }

    private static void validateStandardLibraryCompatibility(final ZeronLibraryIndex index) throws IOException {
        if (index.standardLibraryApiVersion() != StandardLibrary.API_VERSION) {
            throw new IOException("Zeron library requires standard-library API version "
                    + index.standardLibraryApiVersion() + ", but this compiler provides version "
                    + StandardLibrary.API_VERSION + ".");
        }
    }

    private static void writeDeclaration(final DataOutputStream output,
                                         final ExportedDeclaration declaration) throws IOException {
        switch (declaration) {
            case FunctionExport function -> {
                output.writeByte(1);
                output.writeUTF(function.qualifiedName());
                output.writeUTF(function.jvmOwner());
                writeOptionalString(output, function.namespaceName());
                writeFunction(output, function.signature(), new WriteContext());
                output.writeInt(function.minimumArity());
                output.writeBoolean(function.variadic());
            }
            case ExtensionExport extension -> {
                output.writeByte(5);
                output.writeUTF(extension.qualifiedName());
                output.writeUTF(extension.jvmOwner());
                output.writeUTF(extension.namespaceName());
                final var context = new WriteContext();
                writeFunction(output, extension.signature(), context);
                writeType(output, extension.receiverType(), context);
                output.writeInt(extension.receiverTypeParameterCount());
                output.writeBoolean(extension.mutating());
                output.writeInt(extension.minimumArity());
                output.writeBoolean(extension.variadic());
                output.writeBoolean(extension.property());
                writeStrings(output, extension.parameterNames());
            }
            case ValueExport value -> {
                output.writeByte(4);
                output.writeUTF(value.qualifiedName());
                output.writeUTF(value.jvmOwner());
                output.writeUTF(value.initializationOwner());
                writeOptionalString(output, value.namespaceName());
                writeType(output, value.type(), new WriteContext());
            }
            case ClassExport classExport -> {
                output.writeByte(2);
                output.writeUTF(classExport.qualifiedName());
                final var context = new WriteContext();
                writeTypeParameters(output, classExport.typeParameters(), context);
                output.writeInt(classExport.contracts().size());
                for (final var contract : classExport.contracts()) {
                    output.writeUTF(contract.qualifiedName());
                    output.writeInt(contract.typeArguments().size());
                    for (final var type : contract.typeArguments()) writeType(output, type, context);
                }
                output.writeBoolean(classExport.canonicalConstructorPublic());
                output.writeBoolean(classExport.effect());
                output.writeInt(classExport.canonicalConstructorParameters().size());
                for (final var type : classExport.canonicalConstructorParameters()) writeType(output, type, context);
                writeStrings(output, classExport.canonicalConstructorParameterNames());
                output.writeInt(classExport.namedConstructors().size());
                for (final var constructor : classExport.namedConstructors()) {
                    output.writeUTF(constructor.name());
                    writeFunction(output, constructor.signature(), context);
                    output.writeBoolean(constructor.variadic());
                    writeStrings(output, constructor.parameterNames());
                }
                writeMethods(output, classExport.methods(), context);
                writeProperties(output, classExport.properties(), context);
                output.writeInt(classExport.patterns().size());
                for (final var pattern : classExport.patterns()) {
                    output.writeUTF(pattern.name());
                    output.writeBoolean(pattern.refutable());
                    output.writeInt(pattern.outputs().size());
                    for (final var outputParameter : pattern.outputs()) {
                        output.writeUTF(outputParameter.name());
                        writeType(output, outputParameter.type(), context);
                    }
                }
            }
            case ContractExport contract -> {
                output.writeByte(3);
                output.writeUTF(contract.qualifiedName());
                final var context = new WriteContext();
                writeTypeParameters(output, contract.typeParameters(), context);
                output.writeInt(contract.requiredContracts().size());
                for (final var required : contract.requiredContracts()) {
                    output.writeUTF(required.qualifiedName());
                    output.writeInt(required.typeArguments().size());
                    for (final var type : required.typeArguments()) writeType(output, type, context);
                }
                output.writeInt(contract.constructors().size());
                for (final var constructor : contract.constructors()) {
                    output.writeUTF(constructor.name());
                    writeFunction(output, constructor.signature(), context);
                    output.writeBoolean(constructor.variadic());
                    writeStrings(output, constructor.parameterNames());
                }
                writeMethods(output, contract.methods(), context);
                writeProperties(output, contract.properties(), context);
                output.writeBoolean(contract.sealed());
                output.writeInt(contract.permittedClasses().size());
                for (final var permitted : contract.permittedClasses()) {
                    output.writeUTF(permitted.qualifiedName());
                    output.writeInt(permitted.typeArguments().size());
                    for (final var type : permitted.typeArguments()) writeType(output, type, context);
                }
            }
            case SumExport sum -> {
                output.writeByte(7);
                output.writeUTF(sum.qualifiedName());
                final var context = new WriteContext();
                writeTypeParameters(output, sum.typeParameters(), context);
                output.writeInt(sum.cases().size());
                for (final var sumCase : sum.cases()) {
                    output.writeUTF(sumCase.name());
                    output.writeInt(sumCase.payloads().size());
                    for (final var payload : sumCase.payloads()) {
                        output.writeUTF(payload.name());
                        writeType(output, payload.type(), context);
                    }
                }
            }
            case ProductExport product -> {
                output.writeByte(8);
                output.writeUTF(product.qualifiedName());
                final var context = new WriteContext();
                writeTypeParameters(output, product.typeParameters(), context);
                output.writeInt(product.components().size());
                for (final var component : product.components()) {
                    output.writeUTF(component.name());
                    writeType(output, component.type(), context);
                }
            }
            case NewtypeExport newtype -> {
                output.writeByte(9);
                output.writeUTF(newtype.qualifiedName());
                writeType(output, newtype.underlyingType(), new WriteContext());
                output.writeBoolean(newtype.validated());
            }
            case ExternalClassExport externalClass -> {
                output.writeByte(10);
                output.writeUTF(externalClass.qualifiedName());
                output.writeUTF(externalClass.javaBinaryName());
                final var context = new WriteContext();
                output.writeInt(externalClass.constructors().size());
                for (final var constructor : externalClass.constructors()) {
                    output.writeInt(constructor.parameterTypes().size());
                    for (final var type : constructor.parameterTypes()) writeType(output, type, context);
                }
                output.writeInt(externalClass.methods().size());
                for (final var method : externalClass.methods()) {
                    writeFunction(output, method.signature(), context);
                    output.writeBoolean(method.isStatic());
                    output.writeBoolean(method.mutating());
                    output.writeInt(method.exceptionMappings().size());
                    for (final var mapping : method.exceptionMappings()) {
                        output.writeUTF(mapping.exceptionType());
                        output.writeUTF(mapping.effectType());
                        output.writeUTF(mapping.constructorName());
                    }
                }
                output.writeInt(externalClass.staticProperties().size());
                for (final var property : externalClass.staticProperties()) {
                    output.writeUTF(property.name());
                    writeType(output, property.type(), context);
                }
            }
            case WitnessExport witness -> {
                output.writeByte(6);
                output.writeUTF(witness.qualifiedName());
                final var context = new WriteContext();
                writeTypeParameters(output, witness.typeParameters(), context);
                writeType(output, witness.contractType(), context);
                writeType(output, witness.targetType(), context);
                output.writeInt(witness.mappings().size());
                for (final var mapping : witness.mappings()) {
                    output.writeUTF(mapping.requirementName());
                    writeType(output, mapping.targetType(), context);
                    output.writeUTF(mapping.constructorName());
                }
                output.writeInt(witness.methods().size());
                for (final var method : witness.methods()) {
                    output.writeUTF(method.requirementName());
                    output.writeUTF(method.helperQualifiedName());
                    output.writeUTF(method.helperJvmOwner());
                    output.writeUTF(method.helperName());
                    writeType(output, method.methodSignature(), context);
                    writeType(output, method.helperType(), context);
                    output.writeInt(method.minimumArity());
                    output.writeInt(method.helperMinimumArity());
                    output.writeBoolean(method.variadic());
                }
                output.writeBoolean(witness.derived());
                output.writeInt(witness.requirements().size());
                for (final var requirement : witness.requirements()) {
                    writeType(output, requirement.contractType(), context);
                    writeType(output, requirement.targetType(), context);
                }
            }
        }
    }

    private static ExportedDeclaration readDeclaration(final DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 1 -> new FunctionExport(input.readUTF(), input.readUTF(), readOptionalString(input),
                    readFunction(input, new ReadContext()), input.readInt(), input.readBoolean());
            case 5 -> {
                final var name = input.readUTF();
                final var owner = input.readUTF();
                final var namespace = input.readUTF();
                final var context = new ReadContext();
                final var signature = readFunction(input, context);
                final var receiverType = readType(input, context);
                final var receiverTypeParameterCount = input.readInt();
                final var mutating = input.readBoolean();
                final var minimumArity = input.readInt();
                final var variadic = input.readBoolean();
                final var property = input.readBoolean();
                yield new ExtensionExport(name, owner, namespace, receiverType, receiverTypeParameterCount,
                        signature, mutating, minimumArity, variadic, property, readStrings(input));
            }
            case 4 -> new ValueExport(input.readUTF(), input.readUTF(), input.readUTF(),
                    readOptionalString(input), readType(input, new ReadContext()));
            case 2 -> {
                final var name = input.readUTF();
                final var context = new ReadContext();
                final var typeParameters = readTypeParameters(input, context);
                final var contracts = new ArrayList<ContractUseExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    final var contractName = input.readUTF();
                    final var arguments = new ArrayList<TypeDescriptor>();
                    for (int j = 0, argumentCount = readCount(input); j < argumentCount; j++) {
                        arguments.add(readType(input, context));
                    }
                    contracts.add(new ContractUseExport(contractName, arguments));
                }
                final var canonicalPublic = input.readBoolean();
                final var effect = input.readBoolean();
                final var constructorParameters = new ArrayList<TypeDescriptor>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    constructorParameters.add(readType(input, context));
                }
                final var constructorParameterNames = readStrings(input);
                final var constructors = new ArrayList<NamedConstructorExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    final var constructorName = input.readUTF();
                    final var signature = readFunction(input, context);
                    final var variadic = input.readBoolean();
                    constructors.add(new NamedConstructorExport(constructorName, signature, variadic,
                            readStrings(input)));
                }
                final var methods = readMethods(input, context);
                final var properties = readProperties(input, context);
                final var patterns = new ArrayList<PatternExport>();
                final var patternCount = readCount(input);
                for (int i = 0; i < patternCount; i++) {
                    final var patternName = input.readUTF();
                    final var refutable = input.readBoolean();
                    final var outputCount = readCount(input);
                    final var outputs = new ArrayList<PatternOutputExport>();
                    for (int j = 0; j < outputCount; j++) {
                        outputs.add(new PatternOutputExport(input.readUTF(), readType(input, context)));
                    }
                    patterns.add(new PatternExport(patternName, outputs, refutable));
                }
                yield new ClassExport(name, typeParameters, contracts, constructorParameters,
                        constructorParameterNames, canonicalPublic, effect, constructors, methods, properties, patterns);
            }
            case 3 -> {
                final var name = input.readUTF();
                final var context = new ReadContext();
                final var typeParameters = readTypeParameters(input, context);
                final var requiredContracts = new ArrayList<ContractUseExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    final var requiredName = input.readUTF();
                    final var arguments = new ArrayList<TypeDescriptor>();
                    for (int j = 0, argumentCount = readCount(input); j < argumentCount; j++) {
                        arguments.add(readType(input, context));
                    }
                    requiredContracts.add(new ContractUseExport(requiredName, arguments));
                }
                final var constructors = new ArrayList<NamedConstructorExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    final var constructorName = input.readUTF();
                    final var signature = readFunction(input, context);
                    final var variadic = input.readBoolean();
                    constructors.add(new NamedConstructorExport(constructorName, signature, variadic,
                            readStrings(input)));
                }
                final var methods = readMethods(input, context);
                final var properties = readProperties(input, context);
                final var sealed = input.readBoolean();
                final var permitted = new ArrayList<ContractUseExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    final var permittedName = input.readUTF();
                    final var arguments = new ArrayList<TypeDescriptor>();
                    for (int j = 0, argumentCount = readCount(input); j < argumentCount; j++) {
                        arguments.add(readType(input, context));
                    }
                    permitted.add(new ContractUseExport(permittedName, arguments));
                }
                yield new ContractExport(name, typeParameters, requiredContracts, constructors,
                        methods, properties, sealed, permitted);
            }
            case 7 -> {
                final var name = input.readUTF();
                final var context = new ReadContext();
                final var typeParameters = readTypeParameters(input, context);
                final var cases = new ArrayList<SumCaseExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    final var caseName = input.readUTF();
                    final var payloads = new ArrayList<SumPayloadExport>();
                    for (int j = 0, payloadCount = readCount(input); j < payloadCount; j++) {
                        payloads.add(new SumPayloadExport(input.readUTF(), readType(input, context)));
                    }
                    cases.add(new SumCaseExport(caseName, payloads));
                }
                yield new SumExport(name, typeParameters, cases);
            }
            case 8 -> {
                final var name = input.readUTF();
                final var context = new ReadContext();
                final var typeParameters = readTypeParameters(input, context);
                final var components = new ArrayList<ProductComponentExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    components.add(new ProductComponentExport(input.readUTF(), readType(input, context)));
                }
                yield new ProductExport(name, typeParameters, components);
            }
            case 9 -> new NewtypeExport(input.readUTF(), readType(input, new ReadContext()),
                    input.readBoolean());
            case 10 -> {
                final var name = input.readUTF();
                final var javaBinaryName = input.readUTF();
                final var context = new ReadContext();
                final var constructors = new ArrayList<ExternalConstructorExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    final var parameterTypes = new ArrayList<TypeDescriptor>();
                    for (int j = 0, parameterCount = readCount(input); j < parameterCount; j++) {
                        parameterTypes.add(readType(input, context));
                    }
                    constructors.add(new ExternalConstructorExport(parameterTypes));
                }
                final var methods = new ArrayList<ExternalMethodExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    final var signature = readFunction(input, context);
                    final var isStatic = input.readBoolean();
                    final var mutating = input.readBoolean();
                    final var exceptionMappings = new ArrayList<ExternalExceptionMappingExport>();
                    for (int j = 0, mappingCount = readCount(input); j < mappingCount; j++) {
                        exceptionMappings.add(new ExternalExceptionMappingExport(
                                input.readUTF(), input.readUTF(), input.readUTF()));
                    }
                    methods.add(new ExternalMethodExport(signature, isStatic, mutating, exceptionMappings));
                }
                final var properties = new ArrayList<ExternalStaticPropertyExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    properties.add(new ExternalStaticPropertyExport(input.readUTF(), readType(input, context)));
                }
                yield new ExternalClassExport(name, javaBinaryName, constructors, methods, properties);
            }
            case 6 -> {
                final var name = input.readUTF();
                final var context = new ReadContext();
                final var typeParameters = readTypeParameters(input, context);
                final var contractType = readType(input, context);
                final var targetType = readType(input, context);
                final var mappings = new ArrayList<WitnessFactoryMappingExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    mappings.add(new WitnessFactoryMappingExport(input.readUTF(),
                            readType(input, context), input.readUTF()));
                }
                final var methods = new ArrayList<WitnessMethodExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    methods.add(new WitnessMethodExport(input.readUTF(), input.readUTF(), input.readUTF(),
                            input.readUTF(), (FunctionDescriptor) readType(input, context),
                            (FunctionDescriptor) readType(input, context), input.readInt(),
                            input.readInt(), input.readBoolean()));
                }
                final var derived = input.readBoolean();
                final var requirements = new ArrayList<WitnessRequirementExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    requirements.add(new WitnessRequirementExport(
                            readType(input, context), readType(input, context)));
                }
                yield new WitnessExport(name, typeParameters, contractType, targetType, mappings,
                        methods, derived, requirements);
            }
            default -> throw new IOException("Unknown declaration kind in Zeron library index.");
        };
    }

    private static void writeMethods(final DataOutputStream output,
                                     final List<MethodExport> methods,
                                     final WriteContext context) throws IOException {
        output.writeInt(methods.size());
        for (final var method : methods) {
            output.writeUTF(method.name());
            output.writeBoolean(method.mutating());
            output.writeBoolean(method.defaultMethod());
            output.writeInt(method.minimumArity());
            output.writeBoolean(method.variadic());
            writeStrings(output, method.parameterNames());
            writeFunction(output, method.signature(), context);
        }
    }

    private static List<MethodExport> readMethods(final DataInputStream input,
                                                   final ReadContext context) throws IOException {
        final var methods = new ArrayList<MethodExport>();
        for (int i = 0, count = readCount(input); i < count; i++) {
            final var name = input.readUTF();
            final var mutating = input.readBoolean();
            final var defaultMethod = input.readBoolean();
            final var minimumArity = input.readInt();
            final var variadic = input.readBoolean();
            final var parameterNames = readStrings(input);
            methods.add(new MethodExport(name, readFunction(input, context),
                    mutating, defaultMethod, minimumArity, variadic, parameterNames));
        }
        return methods;
    }

    private static void writeProperties(final DataOutputStream output,
                                        final List<PropertyExport> properties,
                                        final WriteContext context) throws IOException {
        output.writeInt(properties.size());
        for (final var property : properties) {
            output.writeUTF(property.name());
            output.writeBoolean(property.mutating());
            writeType(output, property.type(), context);
        }
    }

    private static List<PropertyExport> readProperties(final DataInputStream input,
                                                       final ReadContext context) throws IOException {
        final var properties = new ArrayList<PropertyExport>();
        for (int i = 0, count = readCount(input); i < count; i++) {
            final var name = input.readUTF();
            final var mutating = input.readBoolean();
            properties.add(new PropertyExport(name, readType(input, context), mutating));
        }
        return List.copyOf(properties);
    }

    private static void writeFunction(final DataOutputStream output,
                                      final FunctionDescriptor function,
                                      final WriteContext context) throws IOException {
        output.writeUTF(function.name());
        writeTypeParameters(output, function.typeParameters(), context);
        writeType(output, function.returnType(), context);
        output.writeInt(function.parameters().size());
        for (final var parameter : function.parameters()) writeType(output, parameter, context);
        output.writeInt(function.raisedEffects().size());
        for (final var effect : function.raisedEffects()) writeType(output, effect, context);
    }

    private static FunctionDescriptor readFunction(final DataInputStream input,
                                                   final ReadContext context) throws IOException {
        final var name = input.readUTF();
        final var typeParameters = readTypeParameters(input, context);
        final var returnType = readType(input, context);
        final var parameters = new ArrayList<TypeDescriptor>();
        for (int i = 0, count = readCount(input); i < count; i++) parameters.add(readType(input, context));
        final var effects = new ArrayList<TypeDescriptor>();
        for (int i = 0, count = readCount(input); i < count; i++) effects.add(readType(input, context));
        return TypeDescriptor.functionWithEffectsOf(name, returnType, parameters, typeParameters, effects);
    }

    private static void writeTypeParameters(final DataOutputStream output,
                                            final List<TypeParameterDescriptor> parameters,
                                            final WriteContext context) throws IOException {
        output.writeInt(parameters.size());
        for (final var parameter : parameters) {
            output.writeUTF(parameter.name());
            output.writeInt(context.wireScope(parameter.scopeId()));
        }
        for (final var parameter : parameters) {
            output.writeInt(parameter.bounds().size());
            for (final var bound : parameter.bounds()) writeType(output, bound, context);
        }
    }

    private static List<TypeParameterDescriptor> readTypeParameters(final DataInputStream input,
                                                                   final ReadContext context) throws IOException {
        final var count = readCount(input);
        final var names = new ArrayList<String>(count);
        final var scopes = new ArrayList<Integer>(count);
        final var parameters = new ArrayList<TypeParameterDescriptor>(count);
        for (int i = 0; i < count; i++) {
            names.add(input.readUTF());
            scopes.add(input.readInt());
        }
        for (int i = 0; i < count; i++) {
            final var parameter = new TypeParameterDescriptor(context.runtimeScope(scopes.get(i)), names.get(i));
            parameters.add(parameter);
            context.parameters.put(new WireTypeParameter(scopes.get(i), names.get(i)), parameter);
        }
        for (int i = 0; i < count; i++) {
            final var bounds = new ArrayList<TypeDescriptor>();
            for (int boundIndex = 0, boundCount = readCount(input); boundIndex < boundCount; boundIndex++) {
                bounds.add(readType(input, context));
            }
            if (!bounds.isEmpty()) {
                final var old = parameters.get(i);
                final var parameter = new TypeParameterDescriptor(old.scopeId(), old.name(), bounds);
                parameters.set(i, parameter);
                context.parameters.put(new WireTypeParameter(scopes.get(i), names.get(i)), parameter);
            }
        }
        return List.copyOf(parameters);
    }

    private static void writeType(final DataOutputStream output,
                                  final TypeDescriptor type,
                                  final WriteContext context) throws IOException {
        switch (type) {
            case NeverDescriptor _ -> output.writeByte(1);
            case AnyDescriptor _ -> output.writeByte(2);
            case UnitDescriptor _ -> output.writeByte(3);
            case IntDescriptor _ -> output.writeByte(4);
            case FloatDescriptor _ -> output.writeByte(5);
            case BooleanDescriptor _ -> output.writeByte(6);
            case StringDescriptor _ -> output.writeByte(7);
            case ByteDescriptor _ -> output.writeByte(15);
            case ByteArrayDescriptor _ -> output.writeByte(16);
            case LongDescriptor _ -> output.writeByte(17);
            case NominalDescriptor nominal -> {
                output.writeByte(8);
                output.writeUTF(nominal.name());
            }
            case GenericDescriptor generic -> {
                output.writeByte(9);
                output.writeUTF(generic.baseType().name());
                output.writeInt(generic.typeParameters().size());
                for (final var argument : generic.typeParameters()) writeType(output, argument, context);
            }
            case ArrayDescriptor array -> {
                output.writeByte(10);
                writeType(output, array.elementType(), context);
            }
            case NullableDescriptor nullable -> {
                output.writeByte(11);
                writeType(output, nullable.baseType(), context);
            }
            case ReferenceDescriptor reference -> {
                output.writeByte(12);
                writeType(output, reference.baseType(), context);
            }
            case TypeParameterDescriptor parameter -> {
                output.writeByte(13);
                output.writeInt(context.wireScope(parameter.scopeId()));
                output.writeUTF(parameter.name());
            }
            case FunctionDescriptor function -> {
                output.writeByte(14);
                writeFunction(output, function, context);
            }
            case InferDescriptor _, NullDescriptor _ ->
                    throw new IOException("Internal types cannot be exported in a Zeron library index.");
        }
    }

    private static TypeDescriptor readType(final DataInputStream input,
                                           final ReadContext context) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 1 -> TypeDescriptor.ofNever();
            case 2 -> TypeDescriptor.ofAny();
            case 3 -> TypeDescriptor.ofUnit();
            case 4 -> TypeDescriptor.ofInt();
            case 5 -> TypeDescriptor.ofFloat();
            case 6 -> TypeDescriptor.ofBoolean();
            case 7 -> TypeDescriptor.ofString();
            case 8 -> TypeDescriptor.ofName(input.readUTF());
            case 9 -> {
                final var base = TypeDescriptor.ofName(input.readUTF());
                final var arguments = new ArrayList<TypeDescriptor>();
                for (int i = 0, count = readCount(input); i < count; i++) arguments.add(readType(input, context));
                yield TypeDescriptor.genericOf(base, arguments);
            }
            case 10 -> TypeDescriptor.arrayOf(readType(input, context));
            case 11 -> readType(input, context).toNullable();
            case 12 -> new ReferenceDescriptor(readType(input, context));
            case 13 -> {
                final var scope = input.readInt();
                final var name = input.readUTF();
                final var parameter = context.parameters.get(new WireTypeParameter(scope, name));
                if (parameter == null) throw new IOException("Unbound type parameter in library index: " + name);
                yield parameter;
            }
            case 14 -> readFunction(input, context);
            case 15 -> TypeDescriptor.ofByte();
            case 16 -> TypeDescriptor.ofByteArray();
            case 17 -> TypeDescriptor.ofLong();
            default -> throw new IOException("Unknown type kind in Zeron library index.");
        };
    }

    private static int readCount(final DataInputStream input) throws IOException {
        final var count = input.readInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid collection size in Zeron library index.");
        return count;
    }

    private static void writeOptionalString(final DataOutputStream output, final String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) output.writeUTF(value);
    }

    private static String readOptionalString(final DataInputStream input) throws IOException {
        return input.readBoolean() ? input.readUTF() : null;
    }

    private static void writeStrings(final DataOutputStream output, final List<String> values) throws IOException {
        output.writeInt(values.size());
        for (final var value : values) output.writeUTF(value);
    }

    private static List<String> readStrings(final DataInputStream input) throws IOException {
        final var values = new ArrayList<String>();
        for (int i = 0, count = readCount(input); i < count; i++) values.add(input.readUTF());
        return List.copyOf(values);
    }

    private static String qualify(final String packageName, final String name) {
        return packageName == null || packageName.isEmpty() || name.contains(".")
                ? name : packageName + "." + name;
    }

    private static List<Token> parameterNames(final FunctionDescriptor function, final int line) {
        return parameterNames(function.arity(), line);
    }

    private static List<Token> parameterNames(final int arity, final int line) {
        final var names = new ArrayList<Token>(arity);
        for (int i = 0; i < arity; i++) names.add(token("arg" + i, line));
        return List.copyOf(names);
    }

    private static List<Token> namedParameters(final List<String> names, final int arity, final int line) {
        if (names.isEmpty()) return parameterNames(arity, line);
        if (names.size() != arity) throw new IllegalArgumentException("Parameter names do not match signature.");
        return names.stream().map(name -> token(name, line)).toList();
    }

    private static Token token(final String name) {
        return token(name, -1);
    }

    private static Token token(final String name, final int line) {
        return new Token(TokenType.IDENTIFIER, name, null, line);
    }

    private static <T> List<T> append(final List<T> values, final T value) {
        final var result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private static void addNamespaceMember(final List<Stmt> statements, final String namespaceName,
                                           final Stmt member) {
        final var namespace = statements.stream()
                .filter(Stmt.Namespace.class::isInstance)
                .map(Stmt.Namespace.class::cast)
                .filter(candidate -> candidate.name().lexeme().equals(namespaceName))
                .findFirst();
        if (namespace.isPresent()) {
            final var previous = namespace.get();
            statements.set(statements.indexOf(previous),
                    new Stmt.Namespace(previous.name(), append(previous.members(), member)));
        } else {
            statements.add(new Stmt.Namespace(token(namespaceName), List.of(member)));
        }
    }

    private static String packageName(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? "" : qualifiedName.substring(0, separator);
    }

    private static String simpleName(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    }

    private record WireTypeParameter(int scope, String name) {}

    private static final class WriteContext {
        private final Map<Integer, Integer> scopes = new LinkedHashMap<>();

        int wireScope(final int scope) {
            return scopes.computeIfAbsent(scope, ignored -> scopes.size());
        }
    }

    private static final class ReadContext {
        private final Map<Integer, Integer> scopes = new HashMap<>();
        private final Map<WireTypeParameter, TypeParameterDescriptor> parameters = new HashMap<>();

        int runtimeScope(final int wireScope) {
            return scopes.computeIfAbsent(wireScope, ignored -> READ_SCOPE_IDS.getAndDecrement());
        }
    }
}
