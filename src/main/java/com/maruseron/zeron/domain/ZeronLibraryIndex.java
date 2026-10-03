package com.maruseron.zeron.domain;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.ast.ImportDeclaration;
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

public record ZeronLibraryIndex(int standardLibraryApiVersion,
                                List<ExportedDeclaration> declarations) {
    private static final int MAGIC = 0x5A415049;
    public static final int VERSION = 2;
    private static final int MAX_ENTRIES = 1_000_000;
    private static final AtomicInteger READ_SCOPE_IDS = new AtomicInteger(-1);

    public ZeronLibraryIndex {
        declarations = List.copyOf(declarations);
    }

        public List<CompilationUnit> toCompilationUnits(final String sourceLabel) {
        final var declarationsByPackage = new LinkedHashMap<String, List<Stmt>>();
        for (final var exported : declarations) {
            final var packageName = packageName(exported.qualifiedName());
            final var statements = declarationsByPackage.computeIfAbsent(packageName, _ -> new ArrayList<>());
            switch (exported) {
            case FunctionExport function -> {
                final var functionName = token(simpleName(function.qualifiedName()));
                statements.add(new Stmt.Function(functionName,
                    parameterNames(function.signature(), functionName.line()),
                    function.signature(), List.of(), true));
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
                        List.of()))
                    .toList();
                final var methods = classExport.methods().stream()
                    .map(method -> new Stmt.Method(token(method.name()),
                        parameterNames(method.signature(), className.line()), method.signature(), true,
                        method.mutating(), List.of()))
                    .toList();
                statements.add(new Stmt.ClassDecl(className, classExport.typeParameters(), contractUses,
                    fields, constructor, namedConstructors, methods, true));
            }
            case ContractExport contract -> {
                final var contractName = token(contract.qualifiedName());
                final var methods = contract.methods().stream()
                    .map(method -> new Stmt.ContractMethod(token(method.name()),
                        parameterNames(method.signature(), contractName.line()), method.signature(),
                        method.mutating()))
                    .toList();
                statements.add(new Stmt.ContractDecl(contractName, contract.typeParameters(), methods, true));
            }
            }
        }
        return declarationsByPackage.entrySet().stream()
            .map(entry -> new CompilationUnit(sourceLabel + "#" + entry.getKey(), entry.getKey(),
                List.<ImportDeclaration>of(), entry.getValue(), true))
            .toList();
        }

    public sealed interface ExportedDeclaration permits FunctionExport, ClassExport, ContractExport {
        String qualifiedName();
    }

    public record FunctionExport(String qualifiedName,
                                 String jvmOwner,
                                 FunctionDescriptor signature) implements ExportedDeclaration {
        public FunctionExport {
            Objects.requireNonNull(qualifiedName);
            Objects.requireNonNull(jvmOwner);
            Objects.requireNonNull(signature);
        }
    }

    public record ClassExport(String qualifiedName,
                              List<TypeParameterDescriptor> typeParameters,
                              List<ContractUseExport> contracts,
                              List<TypeDescriptor> canonicalConstructorParameters,
                              boolean canonicalConstructorPublic,
                              List<NamedConstructorExport> namedConstructors,
                              List<MethodExport> methods) implements ExportedDeclaration {
        public ClassExport {
            Objects.requireNonNull(qualifiedName);
            typeParameters = List.copyOf(typeParameters);
            contracts = List.copyOf(contracts);
            canonicalConstructorParameters = List.copyOf(canonicalConstructorParameters);
            namedConstructors = List.copyOf(namedConstructors);
            methods = List.copyOf(methods);
        }
    }

    public record ContractExport(String qualifiedName,
                                 List<TypeParameterDescriptor> typeParameters,
                                 List<MethodExport> methods) implements ExportedDeclaration {
        public ContractExport {
            Objects.requireNonNull(qualifiedName);
            typeParameters = List.copyOf(typeParameters);
            methods = List.copyOf(methods);
        }
    }

    public record ContractUseExport(String qualifiedName, List<TypeDescriptor> typeArguments) {
        public ContractUseExport {
            Objects.requireNonNull(qualifiedName);
            typeArguments = List.copyOf(typeArguments);
        }
    }

    public record NamedConstructorExport(String name, FunctionDescriptor signature) {
        public NamedConstructorExport {
            Objects.requireNonNull(name);
            Objects.requireNonNull(signature);
        }
    }

    public record MethodExport(String name, FunctionDescriptor signature, boolean mutating) {
        public MethodExport {
            Objects.requireNonNull(name);
            Objects.requireNonNull(signature);
        }
    }

    public static ZeronLibraryIndex fromCompilation(final List<CompilationUnit> units,
                                                    final Map<String, String> functionOwners,
                                                    final Function<String, FunctionDescriptor> functionTypes) {
        return fromCompilation(units, functionOwners, functionTypes, false);
    }

    public static ZeronLibraryIndex fromCompilation(final List<CompilationUnit> units,
                                                    final Map<String, String> functionOwners,
                                                    final Function<String, FunctionDescriptor> functionTypes,
                                                    final boolean includeBundledSources) {
        final var exports = new ArrayList<ExportedDeclaration>();
        for (final var unit : units) {
            if (unit.metadataOnly()
                    || !includeBundledSources && StandardLibrary.isBundledSourcePath(unit.sourcePath())) continue;
            for (final var declaration : unit.declarations()) {
                switch (declaration) {
                    case Stmt.FunctionDeclaration function when function.isPublic() -> {
                        final var name = qualify(unit.packageName(), function.name().lexeme());
                        final var signature = functionTypes.apply(name);
                        final var owner = functionOwners.get(name);
                        if (signature == null || owner == null) {
                            throw new IllegalStateException("Missing resolved signature or owner for " + name);
                        }
                        exports.add(new FunctionExport(name, owner, signature));
                    }
                    case Stmt.ClassDecl classDeclaration when classDeclaration.isPublic() -> {
                        exports.add(new ClassExport(classDeclaration.name().lexeme(),
                                classDeclaration.typeParameters(),
                                classDeclaration.contractUses().stream()
                                        .map(use -> new ContractUseExport(use.name().lexeme(), use.typeArguments()))
                                        .toList(),
                                classDeclaration.constructor().isPublic()
                                        ? classDeclaration.fields().stream().map(Stmt.Field::type).toList()
                                        : List.of(),
                                classDeclaration.constructor().isPublic(),
                                classDeclaration.namedConstructors().stream()
                                        .filter(Stmt.NamedConstructor::isPublic)
                                        .map(constructor -> new NamedConstructorExport(
                                                constructor.name().lexeme(), constructor.typeDescriptor()))
                                        .toList(),
                                classDeclaration.methods().stream()
                                        .filter(Stmt.Method::isPublic)
                                        .map(method -> new MethodExport(method.name().lexeme(),
                                                method.typeDescriptor(), method.isMutating()))
                                        .toList()));
                    }
                    case Stmt.ContractDecl contract when contract.isPublic() ->
                            exports.add(new ContractExport(contract.name().lexeme(), contract.typeParameters(),
                                    contract.methods().stream()
                                            .map(method -> new MethodExport(method.name().lexeme(),
                                                    method.typeDescriptor(), method.isMutating()))
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
    }

    public static ZeronLibraryIndex readFromDirectory(final Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Zeron library root is not a class directory: " + root);
        }
        final var indexPath = root.resolve(Path.of("META-INF", "zeron", "api-v2.bin"));
        if (!Files.isRegularFile(indexPath)) {
            throw new IOException("Missing Zeron API index: " + indexPath);
        }
        final var index = readFrom(indexPath);
        if (index.standardLibraryApiVersion() != StandardLibrary.API_VERSION) {
            throw new IOException("Zeron library requires standard-library API version "
                    + index.standardLibraryApiVersion() + ", but this compiler provides version "
                    + StandardLibrary.API_VERSION + ".");
        }
        return index;
    }

    private static void writeDeclaration(final DataOutputStream output,
                                         final ExportedDeclaration declaration) throws IOException {
        switch (declaration) {
            case FunctionExport function -> {
                output.writeByte(1);
                output.writeUTF(function.qualifiedName());
                output.writeUTF(function.jvmOwner());
                writeFunction(output, function.signature(), new WriteContext());
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
                output.writeInt(classExport.canonicalConstructorParameters().size());
                for (final var type : classExport.canonicalConstructorParameters()) writeType(output, type, context);
                output.writeInt(classExport.namedConstructors().size());
                for (final var constructor : classExport.namedConstructors()) {
                    output.writeUTF(constructor.name());
                    writeFunction(output, constructor.signature(), context);
                }
                writeMethods(output, classExport.methods(), context);
            }
            case ContractExport contract -> {
                output.writeByte(3);
                output.writeUTF(contract.qualifiedName());
                final var context = new WriteContext();
                writeTypeParameters(output, contract.typeParameters(), context);
                writeMethods(output, contract.methods(), context);
            }
        }
    }

    private static ExportedDeclaration readDeclaration(final DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 1 -> new FunctionExport(input.readUTF(), input.readUTF(), readFunction(input, new ReadContext()));
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
                final var constructorParameters = new ArrayList<TypeDescriptor>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    constructorParameters.add(readType(input, context));
                }
                final var constructors = new ArrayList<NamedConstructorExport>();
                for (int i = 0, count = readCount(input); i < count; i++) {
                    constructors.add(new NamedConstructorExport(input.readUTF(), readFunction(input, context)));
                }
                yield new ClassExport(name, typeParameters, contracts, constructorParameters, canonicalPublic,
                        constructors, readMethods(input, context));
            }
            case 3 -> {
                final var name = input.readUTF();
                final var context = new ReadContext();
                final var typeParameters = readTypeParameters(input, context);
                yield new ContractExport(name, typeParameters, readMethods(input, context));
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
            writeFunction(output, method.signature(), context);
        }
    }

    private static List<MethodExport> readMethods(final DataInputStream input,
                                                   final ReadContext context) throws IOException {
        final var methods = new ArrayList<MethodExport>();
        for (int i = 0, count = readCount(input); i < count; i++) {
            final var name = input.readUTF();
            final var mutating = input.readBoolean();
            methods.add(new MethodExport(name, readFunction(input, context), mutating));
        }
        return methods;
    }

    private static void writeFunction(final DataOutputStream output,
                                      final FunctionDescriptor function,
                                      final WriteContext context) throws IOException {
        output.writeUTF(function.name());
        writeTypeParameters(output, function.typeParameters(), context);
        writeType(output, function.returnType(), context);
        output.writeInt(function.parameters().size());
        for (final var parameter : function.parameters()) writeType(output, parameter, context);
    }

    private static FunctionDescriptor readFunction(final DataInputStream input,
                                                   final ReadContext context) throws IOException {
        final var name = input.readUTF();
        final var typeParameters = readTypeParameters(input, context);
        final var returnType = readType(input, context);
        final var parameters = new ArrayList<TypeDescriptor>();
        for (int i = 0, count = readCount(input); i < count; i++) parameters.add(readType(input, context));
        return TypeDescriptor.genericFunctionOf(name, returnType, parameters, typeParameters);
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
            output.writeBoolean(parameter.bound() != null);
            if (parameter.bound() != null) writeType(output, parameter.bound(), context);
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
            if (input.readBoolean()) {
                final var bound = readType(input, context);
                final var old = parameters.get(i);
                final var parameter = new TypeParameterDescriptor(old.scopeId(), old.name(), bound);
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
