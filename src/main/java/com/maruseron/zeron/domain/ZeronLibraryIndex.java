package com.maruseron.zeron.domain;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
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
    public static final int VERSION = 14;
    private static final int MAX_ENTRIES = 1_000_000;
    private static final AtomicInteger READ_SCOPE_IDS = new AtomicInteger(-1);

    public ZeronLibraryIndex {
        declarations = List.copyOf(declarations);
    }

    public record ValueExport(String qualifiedName,
                              String jvmOwner,
                              String initializationOwner,
                              String namespaceName,
                              TypeDescriptor type) implements ExportedDeclaration {
        public ValueExport {
            Objects.requireNonNull(qualifiedName);
            Objects.requireNonNull(jvmOwner);
            Objects.requireNonNull(initializationOwner);
            Objects.requireNonNull(type);
        }
    }

        public List<CompilationUnit> toCompilationUnits(final String sourceLabel) {
        final var declarationsByPackage = new LinkedHashMap<String, List<Stmt>>();
        for (final var exported : declarations) {
            final var packageName = switch (exported) {
                case FunctionExport function -> packageName(function.jvmOwner());
                case ExtensionExport extension -> packageName(extension.jvmOwner());
                case ValueExport value -> packageName(value.jvmOwner());
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
                        parameterNames(extension.signature(), name.line()), extension.signature(),
                        true, extension.mutating(), List.of(), List.of(),
                        extension.minimumArity() + 1, extension.variadic(), extension.receiverType(),
                        typeParameters.subList(0, receiverParameterCount),
                        typeParameters.subList(receiverParameterCount, typeParameters.size()));
                addNamespaceMember(statements, extension.namespaceName(), declaration);
            }
            case ValueExport value -> {
                final var valueName = token(simpleName(value.qualifiedName()));
                final var declaration = new Stmt.Var(valueName, value.type(), null,
                        BindingMutability.IMMUTABLE, true);
                if (value.namespaceName() == null) statements.add(declaration);
                else addNamespaceMember(statements, value.namespaceName(), declaration);
            }
            case ClassExport classExport -> {
                final var className = token(classExport.qualifiedName());
                final var fields = new ArrayList<Stmt.Field>();
                for (int i = 0; i < classExport.canonicalConstructorParameters().size(); i++) {
                fields.add(new Stmt.Field(token("field" + i),
                    classExport.canonicalConstructorParameters().get(i)));
                }
                final var contractUses = classExport.contracts().stream()
                    .map(use -> new Stmt.ContractUse(token(use.qualifiedName()), use.typeArguments()))
                    .toList();
                final var constructor = new Stmt.Constructor(token("new"),
                    classExport.canonicalConstructorPublic());
                final var namedConstructors = classExport.namedConstructors().stream()
                    .map(named -> new Stmt.NamedConstructor(token(named.name()),
                        parameterNames(named.signature(), className.line()), named.signature(), true,
                        List.of(), named.variadic()))
                    .toList();
                final var methods = classExport.methods().stream()
                    .map(method -> new Stmt.Method(token(method.name()),
                        parameterNames(method.signature(), className.line()), method.signature(), true,
                        method.mutating(), List.of(), List.of(), method.minimumArity(), method.variadic()))
                    .toList();
                final var properties = classExport.properties().stream()
                    .map(property -> new Stmt.Property(token(property.name()), property.type(), null,
                            true, property.mutating(), null, null, null, false))
                    .toList();
                statements.add(new Stmt.ClassDecl(className, classExport.typeParameters(), contractUses,
                    fields, properties, constructor, namedConstructors, methods, true, classExport.effect()));
            }
            case ContractExport contract -> {
                final var contractName = token(contract.qualifiedName());
                final var methods = contract.methods().stream()
                    .map(method -> new Stmt.ContractMethod(token(method.name()),
                        parameterNames(method.signature(), contractName.line()), method.signature(),
                        method.mutating(), method.defaultMethod(), List.of(), List.of(),
                        method.minimumArity(), method.variadic()))
                    .toList();
                final var properties = contract.properties().stream()
                    .map(property -> new Stmt.ContractProperty(token(property.name()),
                            property.type(), property.mutating()))
                    .toList();
                final var permittedClasses = contract.permittedClasses().stream()
                    .map(use -> new Stmt.ContractUse(token(use.qualifiedName()), use.typeArguments()))
                    .toList();
                statements.add(new Stmt.ContractDecl(contractName, contract.typeParameters(),
                        methods, properties, true, contract.sealed(), permittedClasses));
            }
            }
        }
        return declarationsByPackage.entrySet().stream()
            .map(entry -> new CompilationUnit(sourceLabel + "#" + entry.getKey(), entry.getKey(),
                List.<ImportDeclaration>of(), entry.getValue(), true))
            .toList();
        }

    public sealed interface ExportedDeclaration permits FunctionExport, ExtensionExport,
            ValueExport, ClassExport, ContractExport {
        String qualifiedName();
    }

    public record ExtensionExport(String qualifiedName, String jvmOwner, String namespaceName,
                                 TypeDescriptor receiverType, int receiverTypeParameterCount,
                                 FunctionDescriptor signature, boolean mutating, int minimumArity,
                                 boolean variadic) implements ExportedDeclaration {
        public ExtensionExport {
            Objects.requireNonNull(qualifiedName);
            Objects.requireNonNull(jvmOwner);
            Objects.requireNonNull(namespaceName);
            Objects.requireNonNull(receiverType);
            Objects.requireNonNull(signature);
            if (signature.parameters().isEmpty()
                    || receiverTypeParameterCount < 0
                    || receiverTypeParameterCount > signature.typeParameters().size()) {
                throw new IllegalArgumentException("Invalid extension export.");
            }
            validateMinimumArity(TypeDescriptor.functionOf(signature.name(), signature.returnType(),
                    signature.parameters().subList(1, signature.parameters().size())
                            .toArray(TypeDescriptor[]::new)),
                    minimumArity, variadic);
        }
    }

    public record FunctionExport(String qualifiedName,
                                 String jvmOwner,
                                 String namespaceName,
                                 FunctionDescriptor signature,
                                 int minimumArity,
                                 boolean variadic) implements ExportedDeclaration {
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
                              boolean canonicalConstructorPublic,
                              boolean effect,
                              List<NamedConstructorExport> namedConstructors,
                              List<MethodExport> methods,
                              List<PropertyExport> properties) implements ExportedDeclaration {
        public ClassExport {
            Objects.requireNonNull(qualifiedName);
            typeParameters = List.copyOf(typeParameters);
            contracts = List.copyOf(contracts);
            canonicalConstructorParameters = List.copyOf(canonicalConstructorParameters);
            namedConstructors = List.copyOf(namedConstructors);
            methods = List.copyOf(methods);
            properties = List.copyOf(properties);
        }
    }

    public record ContractExport(String qualifiedName,
                                 List<TypeParameterDescriptor> typeParameters,
                                 List<MethodExport> methods,
                                 List<PropertyExport> properties,
                                 boolean sealed,
                                 List<ContractUseExport> permittedClasses) implements ExportedDeclaration {
        public ContractExport {
            Objects.requireNonNull(qualifiedName);
            typeParameters = List.copyOf(typeParameters);
            methods = List.copyOf(methods);
            properties = List.copyOf(properties);
            permittedClasses = List.copyOf(permittedClasses);
        }

        public ContractExport(String qualifiedName,
                              List<TypeParameterDescriptor> typeParameters,
                              List<MethodExport> methods,
                              List<PropertyExport> properties) {
            this(qualifiedName, typeParameters, methods, properties, false, List.of());
        }
    }

    public record ContractUseExport(String qualifiedName, List<TypeDescriptor> typeArguments) {
        public ContractUseExport {
            Objects.requireNonNull(qualifiedName);
            typeArguments = List.copyOf(typeArguments);
        }
    }

    public record NamedConstructorExport(String name, FunctionDescriptor signature, boolean variadic) {
        public NamedConstructorExport {
            Objects.requireNonNull(name);
            Objects.requireNonNull(signature);
            validateMinimumArity(signature, signature.arity() - (variadic ? 1 : 0), variadic);
        }

        public NamedConstructorExport(String name, FunctionDescriptor signature) {
            this(name, signature, false);
        }
    }

    public record MethodExport(String name, FunctionDescriptor signature,
                               boolean mutating, boolean defaultMethod, int minimumArity,
                               boolean variadic) {
        public MethodExport {
            Objects.requireNonNull(name);
            Objects.requireNonNull(signature);
            validateMinimumArity(signature, minimumArity, variadic);
        }

        public MethodExport(String name, FunctionDescriptor signature, boolean mutating) {
            this(name, signature, mutating, false, signature.arity(), false);
        }

        public MethodExport(String name, FunctionDescriptor signature,
                            boolean mutating, boolean defaultMethod) {
            this(name, signature, mutating, defaultMethod, signature.arity(), false);
        }

        public MethodExport(String name, FunctionDescriptor signature,
                            boolean mutating, boolean defaultMethod, int minimumArity) {
            this(name, signature, mutating, defaultMethod, minimumArity, false);
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

    public static ZeronLibraryIndex fromCompilation(final List<CompilationUnit> units,
                                                    final Function<Stmt.FunctionDeclaration, String> functionOwners,
                                                    final Function<Stmt.FunctionDeclaration, FunctionDescriptor> functionTypes,
                                                    final Function<Stmt.Var, TypeDescriptor> valueTypes,
                                                    final Function<Stmt.Var, String> valueOwners,
                                                    final String initializationOwner,
                                                    final boolean includeBundledSources) {
        final var exports = new ArrayList<ExportedDeclaration>();
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
                                extension.minimumCallArity(), extension.variadic()));
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
                        exports.add(new ClassExport(classDeclaration.name().lexeme(),
                                classDeclaration.typeParameters(),
                                classDeclaration.contractUses().stream()
                                        .map(use -> new ContractUseExport(use.name().lexeme(), use.typeArguments()))
                                        .toList(),
                                classDeclaration.constructor().isPublic()
                                        ? classDeclaration.canonicalConstructorTypes()
                                        : List.of(),
                                classDeclaration.constructor().isPublic(),
                                classDeclaration.isEffect(),
                                classDeclaration.namedConstructors().stream()
                                        .filter(Stmt.NamedConstructor::isPublic)
                                        .map(constructor -> new NamedConstructorExport(
                                                constructor.name().lexeme(), constructor.typeDescriptor(),
                                                constructor.variadic()))
                                        .toList(),
                                classDeclaration.methods().stream()
                                        .filter(Stmt.Method::isPublic)
                                        .map(method -> new MethodExport(method.name().lexeme(),
                                                method.typeDescriptor(), method.isMutating(), false,
                                                method.minimumArity(), method.variadic()))
                                        .toList(),
                                classDeclaration.properties().stream()
                                        .filter(Stmt.Property::isPublic)
                                        .map(property -> new PropertyExport(property.name().lexeme(),
                                                property.type(), property.isMutating()))
                                        .toList()));
                    }
                    case Stmt.ContractDecl contract when contract.isPublic() ->
                            exports.add(new ContractExport(contract.name().lexeme(), contract.typeParameters(),
                                    contract.methods().stream()
                                            .map(method -> new MethodExport(method.name().lexeme(),
                                                    method.typeDescriptor(), method.isMutating(),
                                                    method.isDefault(), method.minimumArity(),
                                                    method.variadic()))
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
                    default -> {}
                }
            }
        }
        exports.sort(Comparator.comparing(ExportedDeclaration::qualifiedName)
                .thenComparing(declaration -> declaration.getClass().getName()));
        return new ZeronLibraryIndex(StandardLibrary.API_VERSION, exports);
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
            final var indexEntry = jar.getJarEntry("META-INF/zeron/api-v14.bin");
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
        final var indexPath = root.resolve(Path.of("META-INF", "zeron", "api-v14.bin"));
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
                output.writeInt(classExport.namedConstructors().size());
                for (final var constructor : classExport.namedConstructors()) {
                    output.writeUTF(constructor.name());
                    writeFunction(output, constructor.signature(), context);
                    output.writeBoolean(constructor.variadic());
                }
                writeMethods(output, classExport.methods(), context);
                writeProperties(output, classExport.properties(), context);
            }
            case ContractExport contract -> {
                output.writeByte(3);
                output.writeUTF(contract.qualifiedName());
                final var context = new WriteContext();
                writeTypeParameters(output, contract.typeParameters(), context);
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
                yield new ExtensionExport(name, owner, namespace, receiverType, input.readInt(),
                        signature, input.readBoolean(), input.readInt(), input.readBoolean());
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
                final var constructors = new ArrayList<NamedConstructorExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    final var constructorName = input.readUTF();
                    final var signature = readFunction(input, context);
                    constructors.add(new NamedConstructorExport(constructorName, signature, input.readBoolean()));
                }
                final var methods = readMethods(input, context);
                yield new ClassExport(name, typeParameters, contracts, constructorParameters, canonicalPublic, effect,
                        constructors, methods, readProperties(input, context));
            }
            case 3 -> {
                final var name = input.readUTF();
                final var context = new ReadContext();
                final var typeParameters = readTypeParameters(input, context);
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
                yield new ContractExport(name, typeParameters, methods, properties, sealed, permitted);
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
            methods.add(new MethodExport(name, readFunction(input, context),
                    mutating, defaultMethod, minimumArity, variadic));
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

    private static String qualify(final String packageName, final String name) {
        return packageName == null || packageName.isEmpty() ? name : packageName + "." + name;
    }

    private static List<Token> parameterNames(final FunctionDescriptor function, final int line) {
        final var names = new ArrayList<Token>(function.arity());
        for (int i = 0; i < function.arity(); i++) names.add(token("arg" + i, line));
        return List.copyOf(names);
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
