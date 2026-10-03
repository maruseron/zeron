package com.maruseron.zeron.compile;

import com.maruseron.zeron.UnitLiteral;
import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.analize.Bind;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.domain.FloatDescriptor;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.constantpool.ConstantPoolBuilder;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.constant.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class Compiler {
    private static final Path DEFAULT_OUTPUT_DIRECTORY = Paths.get("dist");
    private static final ClassDesc UNIT_VALUE_CLASS = ClassDesc.of("com.maruseron.zeron.runtime.UnitValue");

    private final ClassFile classFile = ClassFile.of();
    private final Resolver resolver;
    private final List<Stmt> declarations;
    private final List<CompilationUnit> compilationUnits;
    private final Path outputDirectory;
    private final boolean includeBundledSourcesInIndex;
    private final boolean libraryBuild;
    private final IdentityHashMap<Stmt, String> declarationOwners = new IdentityHashMap<>();
    private final Map<String, String> functionOwners = new HashMap<>();
    private final Set<String> metadataTypeNames = new HashSet<>();
    private final List<Path> javaClassPathRoots;
    private final FunctionBindingRegistry functionBindings;
    private LambdaCompilationPlan lambdaPlan;
    private final String mainClassName;
    private final String packageName;
    private String currentHolderName;
    private SymbolTable symbols = null;
    private TypeDescriptor lastEmittedType = null;
    private TypeDescriptor currentReturnType = null;
    private FunctionModel currentFunction = null;
    private int localSlotOffset;
    private int loopTemporaryCount;
    private final Deque<Label> loopExitLabels = new ArrayDeque<>();
    private final Deque<Label> loopContinueLabels = new ArrayDeque<>();

    public Compiler(List<Stmt> declarations) {
        this(declarations, "ZeronMain");
    }

    public Compiler(List<Stmt> declarations, String mainClassName) {
        this(declarations, mainClassName, "");
    }

    public Compiler(List<Stmt> declarations, String mainClassName, String packageName) {
        this(StandardLibrary.withBundledUnits(List.of(new CompilationUnit(null, packageName, declarations))),
            mainClassName, packageName, true, List.of(), DEFAULT_OUTPUT_DIRECTORY, false, false, List.of(),
                FunctionBindingRegistry.standard());
    }

    public static Compiler forCompilationUnits(final List<CompilationUnit> units,
                                               final String mainClassName,
                                               final String packageName) {
        return forCompilationUnits(units, mainClassName, packageName, List.of());
    }

    public static Compiler forCompilationUnits(final List<CompilationUnit> units,
                                               final String mainClassName,
                                               final String packageName,
                                               final List<ZeronLibraryIndex> libraries) {
        return forCompilationUnits(units, mainClassName, packageName, libraries, true);
    }

    public static Compiler forCompilationUnits(final List<CompilationUnit> units,
                                               final String mainClassName,
                                               final String packageName,
                                               final List<ZeronLibraryIndex> libraries,
                                               final boolean bundleStandardLibrarySources) {
                            return forCompilationUnits(units, mainClassName, packageName, libraries,
                                bundleStandardLibrarySources, List.of());
                            }

                            public static Compiler forCompilationUnits(final List<CompilationUnit> units,
                                                   final String mainClassName,
                                                   final String packageName,
                                                   final List<ZeronLibraryIndex> libraries,
                                                   final boolean bundleStandardLibrarySources,
                                                   final List<Path> javaClassPathRoots) {
                                return forCompilationUnits(units, mainClassName, packageName, libraries,
                                    bundleStandardLibrarySources, javaClassPathRoots, FunctionBindingRegistry.standard());
                                }

                                public static Compiler forCompilationUnits(final List<CompilationUnit> units,
                                                       final String mainClassName,
                                                       final String packageName,
                                                       final List<ZeronLibraryIndex> libraries,
                                                       final boolean bundleStandardLibrarySources,
                                                       final List<Path> javaClassPathRoots,
                                                       final FunctionBindingRegistry functionBindings) {
        final var combinedUnits = new ArrayList<>(units);
        for (int i = 0; i < libraries.size(); i++) {
            combinedUnits.addAll(libraries.get(i).toCompilationUnits("library-index-" + i));
        }
        final var compilationUnits = bundleStandardLibrarySources
                ? StandardLibrary.withBundledUnits(combinedUnits)
                : List.copyOf(combinedUnits);
        return new Compiler(compilationUnits, mainClassName,
            packageName, true, libraries, DEFAULT_OUTPUT_DIRECTORY, false, false, javaClassPathRoots,
            functionBindings);
    }

        public static Compiler forStandardLibrary(final Path outputDirectory) {
        return new Compiler(StandardLibrary.bundledUnits(), "zeron.$StandardLibrary", "zeron",
            true, List.of(), outputDirectory, true, true, List.of(), FunctionBindingRegistry.standard());
        }

    private Compiler(final List<CompilationUnit> units,
                     final String mainClassName,
                     final String packageName,
                     final boolean compilationUnits,
                 final List<ZeronLibraryIndex> libraries,
                 final Path outputDirectory,
                 final boolean includeBundledSourcesInIndex,
                     final boolean libraryBuild,
                     final List<Path> javaClassPathRoots,
                     final FunctionBindingRegistry functionBindings) {
        this.compilationUnits = List.copyOf(units);
        this.outputDirectory = outputDirectory;
        this.includeBundledSourcesInIndex = includeBundledSourcesInIndex;
        this.libraryBuild = libraryBuild;
        this.javaClassPathRoots = List.copyOf(javaClassPathRoots);
        this.functionBindings = Objects.requireNonNull(functionBindings);
        declarations = this.compilationUnits.stream().filter(unit -> !unit.metadataOnly())
            .flatMap(unit -> unit.declarations().stream()).toList();
        this.packageName = packageName == null ? "" : packageName;
        this.mainClassName = mainClassName;
        resolver = new Resolver("", this.javaClassPathRoots, this.functionBindings);
        for (final var unit : this.compilationUnits) {
            if (!unit.metadataOnly()) continue;
            for (final var declaration : unit.declarations()) {
                if (declaration instanceof Stmt.ClassDecl classDeclaration) {
                    metadataTypeNames.add(classDeclaration.name().lexeme());
                } else if (declaration instanceof Stmt.ContractDecl contractDeclaration) {
                    metadataTypeNames.add(contractDeclaration.name().lexeme());
                }
            }
        }
        indexDeclarationOwners();
        for (final var library : libraries) {
            for (final var declaration : library.declarations()) {
                if (declaration instanceof ZeronLibraryIndex.FunctionExport function) {
                    functionOwners.putIfAbsent(function.qualifiedName(), function.jvmOwner());
                }
            }
        }
    }

    public void resolve() {
        resolver.resolveUnits(compilationUnits);
        symbols = resolver.symbols;
    }

    public void compile() throws IOException {
        Files.createDirectories(outputDirectory);
        generateUnitValueClass();
        lambdaPlan = new LambdaCompilationPlan(declarations, symbols);
        for (final var shape : lambdaPlan.functionShapes().entrySet()) {
            generateGeneratedLambdaClass(shape.getKey(), shape.getValue());
        }

        for (var unitIndex = 0; unitIndex < compilationUnits.size(); unitIndex++) {
            final var unit = compilationUnits.get(unitIndex);
            if (unit.metadataOnly()) continue;
            final var isEntryHolder = unitIndex == 0;
            final var holderName = isEntryHolder ? mainClassName : holderNameFor(unit, unitIndex);
            final var unitDeclarations = unit.declarations();
            final var hasTopLevelStorage = unitDeclarations.stream()
                    .anyMatch(declaration -> declaration instanceof Stmt.FunctionDeclaration
                        || declaration instanceof Stmt.Var);
            if ((unitIndex != 0 || libraryBuild) && !hasTopLevelStorage) continue;
            final var holderPath = outputPath(holderName);
            Files.createDirectories(holderPath.getParent());
            currentHolderName = holderName;
            classFile.buildTo(holderPath.toAbsolutePath(), ClassDesc.of(holderName), builder -> {
                builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
                generateClass(builder, unitDeclarations, isEntryHolder);
            });
        }
        generateNominalTypes();
        final var libraryIndex = ZeronLibraryIndex.fromCompilation(compilationUnits, functionOwners,
                name -> symbols.getFunctionType(resolver.functionSymbolToken(name)), includeBundledSourcesInIndex);
        libraryIndex.writeTo(outputDirectory.resolve(Path.of("META-INF", "zeron", "api-v2.bin")));
    }

    private void indexDeclarationOwners() {
        for (var unitIndex = 0; unitIndex < compilationUnits.size(); unitIndex++) {
            final var unit = compilationUnits.get(unitIndex);
            if (unit.metadataOnly()) continue;
            final var owner = unitIndex == 0 ? mainClassName : holderNameFor(unit, unitIndex);
            for (final var declaration : unit.declarations()) {
                if (declaration instanceof Stmt.FunctionDeclaration function) {
                    declarationOwners.put(declaration, owner);
                    functionOwners.put(resolverQualifiedName(unit.packageName(), function.name().lexeme()), owner);
                } else if (declaration instanceof Stmt.Var) {
                    declarationOwners.put(declaration, owner);
                }
            }
        }
    }

    private String holderNameFor(final CompilationUnit unit, final int unitIndex) {
        final var sourcePath = Objects.requireNonNullElse(unit.sourcePath(),
                "anonymous" + unitIndex + ".zn").replace('\\', '/');
        final var fileName = Path.of(sourcePath).getFileName().toString().replaceFirst("\\.zn$", "")
                .replaceAll("[^A-Za-z0-9_$]", "_");
        final var owner = "$File$" + fileName + "$" + shortHash(sourcePath);
        return unit.packageName().isEmpty() ? owner : unit.packageName() + "." + owner;
    }

    private String shortHash(final String value) {
        try {
            final var bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    private String resolverQualifiedName(final String ownerPackage, final String name) {
        return ownerPackage == null || ownerPackage.isEmpty() ? name : ownerPackage + "." + name;
    }

    private void generateUnitValueClass() throws IOException {
        final var output = outputDirectory.resolve(Path.of("com", "maruseron", "zeron", "runtime", "UnitValue.class"));
        Files.createDirectories(output.getParent());
        ClassFile.of().buildTo(output, UNIT_VALUE_CLASS, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withField("INSTANCE", UNIT_VALUE_CLASS,
                    field -> field.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL));
            builder.withMethodBody("<init>", emptyVoidMethod(), ClassFile.ACC_PRIVATE, code -> {
                code.aload(0);
                code.invokespecial(ConstantDescs.CD_Object, "<init>", emptyVoidMethod());
                code.return_();
            });
            builder.withMethodBody("<clinit>", emptyVoidMethod(), ClassFile.ACC_STATIC, code -> {
                code.new_(UNIT_VALUE_CLASS);
                code.dup();
                code.invokespecial(UNIT_VALUE_CLASS, "<init>", emptyVoidMethod());
                code.putstatic(UNIT_VALUE_CLASS, "INSTANCE", UNIT_VALUE_CLASS);
                code.return_();
            });
            builder.withMethodBody("toString", MethodTypeDesc.of(ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, code -> {
                        code.ldc("Unit");
                        code.areturn();
                    });
        });
    }

    private void generateGeneratedLambdaClass(final FunctionShapeKey shapeKey,
                                              final FunctionDescriptor type) throws IOException {
        final var className = FunctionShapeNames.interfaceName(shapeKey);
        final var classDesc = ClassDesc.of(className);
        final var outFile = outputDirectory.resolve(className.replace('.', '/') + ".class");
        final var parent = outFile.getParent();
        if (parent != null) {
            try {
                java.nio.file.Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create lambda class dir", e);
            }
        }

        ClassFile.of().buildTo(outFile, classDesc, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
            cb.withMethod(
                    "invoke",
                    toJavaMethodDescriptor(type),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                    _ -> {});
        });
    }

    private void generateNominalTypes() throws IOException {
        for (final var contract : resolver.contracts().values()) {
            if (metadataTypeNames.contains(contract.name().lexeme())) continue;
            final var contractName = contract.name().lexeme();
            if (contractName.equals(mainClassName)) {
                throw new IllegalStateException("Contract name conflicts with generated program class: " + contractName);
            }
            final var contractPath = outputPath(contractName);
            Files.createDirectories(contractPath.getParent());
            classFile.buildTo(contractPath.toAbsolutePath(),
                    ClassDesc.of(contractName),
                    builder -> {
                        builder.withFlags((contract.isPublic() ? ClassFile.ACC_PUBLIC : 0)
                            | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
                        for (final var method : contract.methods()) {
                            builder.withMethod(method.name().lexeme(),
                                    toJavaMethodDescriptor(method.typeDescriptor()),
                                    ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                                    _ -> {});
                        }
                    });
        }

        for (final var declaration : resolver.classes().values()) {
            if (metadataTypeNames.contains(declaration.name().lexeme())) continue;
            final var className = declaration.name().lexeme();
            if (className.equals(mainClassName)) {
                throw new IllegalStateException("Class name conflicts with generated program class: " + className);
            }
            generateNominalClass(declaration);
        }
    }

    private void generateNominalClass(final Stmt.ClassDecl declaration) throws IOException {
        final var className = declaration.name().lexeme();
        final var classDesc = ClassDesc.of(className);
        final var interfaces = declaration.contractUses().stream()
            .map(contractUse -> ClassDesc.of(contractUse.name().lexeme()))
            .toList();
        final var classPath = outputPath(className);
        Files.createDirectories(classPath.getParent());
        classFile.buildTo(classPath.toAbsolutePath(),
                classDesc,
                builder -> {
                    builder.withFlags((declaration.isPublic() ? ClassFile.ACC_PUBLIC : 0) | ClassFile.ACC_FINAL);
                    if (!interfaces.isEmpty()) builder.withInterfaceSymbols(interfaces);
                    for (final var field : declaration.fields()) {
                        builder.withField(field.name().lexeme(), TypeDescriptor.toJavaClassDesc(field.type()),
                                ClassFile.ACC_PRIVATE);
                    }
                    final var constructorParameters = declaration.fields().stream()
                            .map(field -> TypeDescriptor.toJavaClassDesc(field.type()))
                            .toList();
                    final var constructorType = MethodTypeDesc.of(ConstantDescs.CD_void, constructorParameters);
                    builder.withMethodBody("<init>", constructorType,
                            declaration.constructor().isPublic() ? ClassFile.ACC_PUBLIC : ClassFile.ACC_PRIVATE,
                            code -> emitCanonicalConstructor(code, declaration, classDesc));
                    for (final var method : declaration.methods()) {
                        final var flags = method.isPublic() ? ClassFile.ACC_PUBLIC : ClassFile.ACC_PRIVATE;
                        builder.withMethodBody(method.name().lexeme(),
                                toJavaMethodDescriptor(method.typeDescriptor()), flags,
                                code -> emitClassMethod(code, declaration, method));
                    }
                        for (final var constructor : declaration.namedConstructors()) {
                        final var factoryType = (FunctionDescriptor) TypeSubstitution.erase(
                            constructor.typeDescriptor());
                        final var flags = (constructor.isPublic() ? ClassFile.ACC_PUBLIC : ClassFile.ACC_PRIVATE)
                            | ClassFile.ACC_STATIC;
                        builder.withMethodBody(constructor.name().lexeme(),
                            toJavaMethodDescriptor(factoryType), flags,
                            code -> emitNamedConstructor(code, constructor));
                        }
                        final var generatedBridges = new HashSet<String>();
                        for (final var contractUse : declaration.contractUses()) {
                        final var contract = resolver.contracts().get(contractUse.name().lexeme());
                        for (final var required : contract.methods()) {
                            final var implementation = declaration.methods().stream()
                                .filter(method -> method.name().lexeme().equals(required.name().lexeme()))
                                .findFirst()
                                .orElseThrow();
                            final var erasedContractMethod = (FunctionDescriptor) TypeSubstitution.erase(
                                required.typeDescriptor());
                            final var bridgeDescriptor = toJavaMethodDescriptor(erasedContractMethod);
                            final var implementationDescriptor = toJavaMethodDescriptor(
                                implementation.typeDescriptor());
                            final var bridgeKey = required.name().lexeme() + bridgeDescriptor.descriptorString();
                            if (bridgeDescriptor.equals(implementationDescriptor)
                                || !generatedBridges.add(bridgeKey)) continue;
                            builder.withMethodBody(required.name().lexeme(), bridgeDescriptor,
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC,
                                code -> emitContractBridge(code, classDesc, required, implementation));
                        }
                        }
                });
    }

                private void emitContractBridge(final CodeBuilder code,
                               final ClassDesc classDesc,
                               final Stmt.ContractMethod required,
                               final Stmt.Method implementation) {
                code.aload(0);
                var slot = 1;
                for (int i = 0; i < required.typeDescriptor().parameters().size(); i++) {
                    final var erasedParameter = TypeSubstitution.erase(
                        required.typeDescriptor().parameters().get(i));
                    final var implementationParameter = implementation.typeDescriptor().parameters().get(i);
                    loadLocal(code, slot, erasedParameter);
                    emitConversion(code, erasedParameter, implementationParameter);
                    slot += erasedParameter.isDoubleWidth() ? 2 : 1;
                }
                code.invokevirtual(classDesc, implementation.name().lexeme(),
                    toJavaMethodDescriptor(implementation.typeDescriptor()));
                final var erasedReturn = TypeSubstitution.erase(required.typeDescriptor().returnType());
                emitConversion(code, implementation.typeDescriptor().returnType(), erasedReturn);
                code.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(erasedReturn).descriptorString()));
                }

    private void emitCanonicalConstructor(final CodeBuilder code,
                                          final Stmt.ClassDecl declaration,
                                          final ClassDesc classDesc) {
        code.aload(0);
        code.invokespecial(ConstantDescs.CD_Object, "<init>", emptyVoidMethod());
        var slot = 1;
        for (final var field : declaration.fields()) {
            code.aload(0);
            loadLocal(code, slot, field.type());
            code.putfield(classDesc, field.name().lexeme(), TypeDescriptor.toJavaClassDesc(field.type()));
            slot += field.type().isDoubleWidth() ? 2 : 1;
        }
        code.return_();
    }

    private void emitClassMethod(final CodeBuilder code,
                                 final Stmt.ClassDecl owner,
                                 final Stmt.Method method) {
        final var previousReturnType = currentReturnType;
        final var previousOffset = localSlotOffset;
        currentReturnType = method.typeDescriptor().returnType();
        localSlotOffset = 0;
        beginScope();
        final var thisToken = new Token(TokenType.THIS, "this", null, method.name().line());
        final TypeDescriptor ownerType = owner.typeParameters().isEmpty()
            ? TypeDescriptor.of(owner.name().lexeme())
            : TypeDescriptor.genericOf(TypeDescriptor.ofName(owner.name().lexeme()),
                owner.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
        final var thisType = method.isMutating()
            ? new ReferenceDescriptor(ownerType)
            : ownerType;
        symbols.declareSymbol(Resolver.SYNTHETIC_VAR, thisToken, thisType, BindingMutability.IMMUTABLE);
        symbols.define(thisToken);
        for (int i = 0; i < method.parameters().size(); i++) {
            final var parameter = method.parameters().get(i);
            symbols.declareSymbol(Resolver.SYNTHETIC_VAR, parameter,
                    method.typeDescriptor().parameters().get(i), BindingMutability.IMMUTABLE);
            symbols.define(parameter);
        }
        try {
            emitStmts(code, method.body());
            if (currentReturnType instanceof UnitDescriptor) {
                emitUnitValue(code);
                code.areturn();
            }
        } finally {
            endScope();
            localSlotOffset = previousOffset;
            currentReturnType = previousReturnType;
        }
    }

    private void emitNamedConstructor(final CodeBuilder code,
                                      final Stmt.NamedConstructor constructor) {
        final var previousReturnType = currentReturnType;
        final var previousOffset = localSlotOffset;
        currentReturnType = constructor.typeDescriptor().returnType();
        localSlotOffset = 0;
        beginScope();
        for (int i = 0; i < constructor.parameters().size(); i++) {
            final var parameter = constructor.parameters().get(i);
            symbols.declareSymbol(Resolver.SYNTHETIC_VAR, parameter,
                    constructor.typeDescriptor().parameters().get(i), BindingMutability.IMMUTABLE);
            symbols.define(parameter);
        }
        try {
            emitStmts(code, constructor.body());
        } finally {
            endScope();
            localSlotOffset = previousOffset;
            currentReturnType = previousReturnType;
        }
    }

    private void loadLocal(final CodeBuilder code, final int slot, final TypeDescriptor type) {
        switch (TypeDescriptor.toJavaClassDesc(type).descriptorString()) {
            case "I", "Z" -> code.iload(slot);
            case "D" -> code.dload(slot);
            default -> code.aload(slot);
        }
    }

    public void generateClass(final ClassBuilder classBuilder, final List<Stmt> declarations) {
        generateClass(classBuilder, declarations, true);
    }

    private void generateClass(final ClassBuilder classBuilder,
                               final List<Stmt> declarations,
                               final boolean entryHolder) {
        record Initializer(Token name, TypeDescriptor type, Expr initializer) {}

        var hasMain = false;
        final var initializers = new ArrayList<Initializer>();
        for (final var declaration : declarations) {
            switch (declaration) {
                case Stmt.Var(Token name, _, Expr initializer, BindingMutability mutability) -> {
                    final var type = symbols.getSymbol(name).type();
                    final var foldedValue = ConstantFolder.fold(initializer);
                    final ConstantDesc value = ConstantFolder.isConstantFieldValue(type, foldedValue)
                            ? foldedValue
                            : null;
                    classBuilder.withField(
                            name.lexeme(),
                            TypeDescriptor.toJavaClassDesc(type),
                            fieldBuilder -> {
                                fieldBuilder.withFlags(mutability == BindingMutability.IMMUTABLE
                                        ? ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
                                        : ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC);
                                if (value != null) {
                                    fieldBuilder.with(ConstantValueAttribute.of(value));
                                } else {
                                    initializers.add(new Initializer(name, type, initializer));
                                }
                            });
                }
                case Stmt.FunctionDeclaration function -> {
                    final var name = function.name();
                    final var parameters = function.parameters();
                    final var typeDescriptor = function.typeDescriptor();
                    final var sourceFunction = function instanceof Stmt.Function source ? source : null;
                    final var externalFunction = function instanceof Stmt.ExternalFunction external
                            ? external
                            : null;
                    if (entryHolder && name.lexeme().equals("main")) hasMain = true;
                    final var functionToken = resolver.functionSymbolToken(name);
                    final var functionType = symbols.getFunctionType(functionToken);
                    classBuilder.withMethodBody(
                            name.lexeme(),
                            toJavaMethodDescriptor(functionType),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL,
                            composer -> {
                                beginScope();
                                final var previousReturnType = currentReturnType;
                                currentReturnType = functionType.returnType();
                                final var paramTypes = typeDescriptor.parameters();
                                for (int i = 0; i < parameters.size(); i++) {
                                    symbols.declareSymbol(declaration, parameters.get(i), paramTypes.get(i), BindingMutability.IMMUTABLE);
                                    symbols.define(parameters.get(i));
                                }
                                currentFunction = new FunctionModel(name.lexeme(), functionType);
                                final var binding = externalFunction == null
                                    ? null
                                    : resolver.externalFunctionBinding(externalFunction);
                                composer.transforming(
                                        (builder, element) -> {
                                            if (element instanceof Instruction i) {
                                                currentFunction.add(i, i.opcode().kind(), null, null, null);
                                            }
                                            builder.with(element);
                                        },
                                        builder -> {
                                            if (externalFunction != null) {
                                                emitExternalFunction(builder, externalFunction.typeDescriptor(), binding);
                                            } else {
                                                emitStmts(builder, sourceFunction.body());
                                            }
                                        });
                                if (externalFunction == null
                                        && functionType.returnType() instanceof UnitDescriptor) {
                                    emitUnitValue(composer);
                                    composer.areturn();
                                }
                                endScope();
                                currentReturnType = previousReturnType;
                                Zeron.debug(currentFunction.toString());
                            });
                }
                default -> {}
            }
        }

        if (entryHolder && hasMain) {
            classBuilder.withMethodBody(
                    "main",
                    emptyVoidMethod(),
                    ClassFile.ACC_STATIC,
                    composer -> {
                        composer.invokestatic(composer.constantPool().methodRefEntry(
                                ClassDesc.of(currentHolderName),
                                "main",
                                MethodTypeDesc.of(UNIT_VALUE_CLASS)));
                        composer.pop();
                        composer.return_();
                    });
        }

        if (!initializers.isEmpty()) {
            classBuilder.withMethodBody(
                    "<clinit>",
                    emptyVoidMethod(),
                    ClassFile.ACC_STATIC,
                    composer -> {
                        for (final var pair : initializers) {
                            final var name = pair.name();
                            final var type = pair.type();
                            final var initializer = pair.initializer();
                            emitExpr(composer, initializer);
                            emitConversion(composer, lastEmittedType, type);
                            composer.putstatic(ClassDesc.of(currentHolderName), name.lexeme(),
                                    TypeDescriptor.toJavaClassDesc(type));
                        }
                        composer.return_();
                    });
        }

                if (entryHolder) for (final var lambda : lambdaPlan.lambdaImplementations()) {
                    final var functionType = (FunctionDescriptor) lambda.getType();
                    final var implementationParameters = new ArrayList<TypeDescriptor>();
                    implementationParameters.addAll(lambdaPlan.captureTypes(lambda));
                    implementationParameters.addAll(functionType.parameters());
                    classBuilder.withMethodBody(
                        lambdaPlan.lambdaMethodName(lambda),
                        toJavaMethodDescriptor(functionType, implementationParameters),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        composer -> emitLambdaImplementation(composer, lambda));
                }

                if (entryHolder) for (final var adapter : lambdaPlan.adapters()) {
                    final var sourceType = adapter.source();
                    final var targetType = adapter.target();
                    final var parameters = new ArrayList<TypeDescriptor>();
                    parameters.add(sourceType);
                    parameters.addAll(targetType.parameters());
                    classBuilder.withMethodBody(
                        adapter.name(),
                        toJavaMethodDescriptor(targetType, parameters),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        composer -> emitFunctionAdapterImplementation(composer, sourceType, targetType));
                }

                    if (entryHolder) for (final var adapter : lambdaPlan.nullableAdapters()) {
                        final var sourceType = adapter.source();
                        final var targetType = adapter.target();
                        final var sourceClass = TypeDescriptor.toJavaClassDesc(sourceType);
                        final var targetClass = TypeDescriptor.toJavaClassDesc(targetType);
                        classBuilder.withMethodBody(
                            adapter.name(),
                            MethodTypeDesc.of(targetClass, sourceClass),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                            composer -> emitNullableFunctionAdapterImplementation(
                                composer, sourceType, targetType));
                    }

                    if (entryHolder) for (final var reference : lambdaPlan.functionReferences()) {
                        classBuilder.withMethodBody(
                                reference.helperName(),
                                toJavaMethodDescriptor(reference.targetType()),
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                                composer -> emitFunctionReferenceImplementation(composer, reference));
                    }
    }

    public void emitStmts(final CodeBuilder builder, final List<Stmt> statements) {
        for (final var statement : statements) {
            emitStmt(builder, statement);
        }
    }

    public void emitStmt(final CodeBuilder composer, final Stmt statement) {
        switch (statement) {
            case Stmt.Break _ -> {
                if (loopExitLabels.isEmpty()) {
                    throw new IllegalStateException("Resolved break has no enclosing loop.");
                }
                composer.goto_(loopExitLabels.peek());
            }
            case Stmt.Continue _ -> {
                if (loopContinueLabels.isEmpty()) {
                    throw new IllegalStateException("Resolved continue has no enclosing loop.");
                }
                composer.goto_(loopContinueLabels.peek());
            }
            case Stmt.Block(List<Stmt> statements) -> {
                beginScope();
                emitStmts(composer, statements);
                endScope();
            }
            case Stmt.If(Token paren, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                final var value = (Integer) ConstantFolder.fold(condition);
                if (value != null) {
                    if (value == 1) {
                        emitStmt(composer, thenBranch);
                    } else {
                        emitStmt(composer, elseBranch);
                    }
                } else {
                    emitExpr(composer, condition);
                    if (elseBranch != null) {
                        composer.ifThenElse(c -> emitStmt(c, thenBranch), c -> emitStmt(c, elseBranch));
                    } else {
                        composer.ifThen(c -> emitStmt(c, thenBranch));
                    }
                }
            }
            case Stmt.While(Token _, Expr condition, Stmt body) -> {
                final var loopStart = composer.newLabel();
                final var loopExit = composer.newLabel();
                composer.labelBinding(loopStart);
                emitExpr(composer, condition);
                composer.ifeq(loopExit);
                loopExitLabels.push(loopExit);
                loopContinueLabels.push(loopStart);
                try {
                    emitStmt(composer, body);
                } finally {
                    loopContinueLabels.pop();
                    loopExitLabels.pop();
                }
                composer.goto_(loopStart);
                composer.labelBinding(loopExit);
            }
            case Stmt.For(Token iterationBind, Token _, Expr iterable, Stmt body) ->
                    emitFor(composer, iterationBind, iterable, body);
            case Stmt.Expression(Expr expression) -> {
                if (expression instanceof Expr.PropertyAssignment assignment) {
                    emitPropertyAssignment(composer, assignment, false);
                } else if (expression instanceof Expr.IndexAssignment assignment) {
                    emitIntrinsicOperation(composer, assignment, false);
                } else {
                    emitExpr(composer, expression);
                    emitPop(composer, lastEmittedType);
                }
            }
            case Stmt.Return(Expr expression) -> {
                if (expression == null) {
                    emitUnitValue(composer);
                    lastEmittedType = TypeDescriptor.ofUnit();
                } else {
                    emitExpr(composer, expression);
                }
                emitConversion(composer, lastEmittedType, currentReturnType);
                composer.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(currentReturnType).descriptorString()));
            }
            case Stmt.Var(Token name, TypeDescriptor type, Expr initializer, BindingMutability mutability) -> {
                if (initializer != null) {
                    final ConstantDesc value = ConstantFolder.fold(initializer);
                    if (value != null) {
                        emitConstant(composer, value);
                        lastEmittedType = initializer.getType();
                    } else {
                        emitExpr(composer, initializer);
                    }
                    final var storedType = type instanceof InferDescriptor ? lastEmittedType : type;
                    emitConversion(composer, lastEmittedType, storedType);
                    final var lvt = symbols.declareSymbol(statement, name, storedType, mutability);
                    composer.storeLocal(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(storedType).descriptorString()), lvt + localSlotOffset);
                    symbols.define(name);
                } else {
                    final var lvt = symbols.declareSymbol(statement, name, type, mutability);
                    if (type.isNullable()) {
                        composer.aconst_null();
                        composer.storeLocal(TypeKind.REFERENCE, lvt + localSlotOffset);
                        symbols.define(name);
                    }
                }
            }
            default -> throw new UnsupportedOperationException();
        }
    }

    private void emitFor(final CodeBuilder composer,
                         final Token iterationBind,
                         final Expr iterable,
                         final Stmt body) {
        beginScope();
        try {
            final var iterableType = iterable.getType() instanceof ReferenceDescriptor reference
                    ? reference.baseType()
                    : iterable.getType();
            if (iterableType instanceof ArrayDescriptor) {
                emitArrayFor(composer, iterationBind, iterable, body);
            } else {
                emitProtocolFor(composer, iterationBind, iterable, body);
            }
        } finally {
            endScope();
        }
    }

    private void emitProtocolFor(final CodeBuilder composer,
                                final Token iterationBind,
                                final Expr iterable,
                                final Stmt body) {
        final var elementType = resolver.iterationElementType(iterationBind);
        final var protocol = resolver.iterationProtocol(iterationBind);
        final var iteratorName = protocol.iteratorName();
        final var iterableName = protocol.iterableName();
        final var iteratorType = new ReferenceDescriptor(TypeDescriptor.genericOf(
            TypeDescriptor.ofName(iteratorName), elementType));
        final var iterableContractType = TypeDescriptor.genericOf(
            TypeDescriptor.ofName(iterableName), elementType);

        emitExpr(composer, iterable);
        emitConversion(composer, lastEmittedType, iterableContractType);
        composer.invokeinterface(ClassDesc.of(iterableName), "iterator",
            MethodTypeDesc.of(ClassDesc.of(iteratorName)));
        lastEmittedType = iteratorType;
        final var iteratorLocal = declareLoopLocal(nextLoopTemporary("iterator"), iteratorType);
        composer.astore(iteratorLocal.lvt() + localSlotOffset);
        final var valueLocal = declareLoopLocal(iterationBind, elementType);

        final var loopStart = composer.newLabel();
        final var loopExit = composer.newLabel();
        composer.labelBinding(loopStart);
        composer.aload(iteratorLocal.lvt() + localSlotOffset);
        composer.invokeinterface(ClassDesc.of(iteratorName), "hasNext",
                MethodTypeDesc.of(ConstantDescs.CD_boolean));
        composer.ifeq(loopExit);

        composer.aload(iteratorLocal.lvt() + localSlotOffset);
        composer.invokeinterface(ClassDesc.of(iteratorName), "next",
                MethodTypeDesc.of(ConstantDescs.CD_Object));
        emitConversion(composer, TypeDescriptor.ofName("java.lang.Object"), elementType);
        composer.storeLocal(TypeKind.fromDescriptor(
                TypeDescriptor.toJavaClassDesc(elementType).descriptorString()),
                valueLocal.lvt() + localSlotOffset);

        loopExitLabels.push(loopExit);
        loopContinueLabels.push(loopStart);
        try {
            emitStmt(composer, body);
        } finally {
            loopContinueLabels.pop();
            loopExitLabels.pop();
        }
        composer.goto_(loopStart);
        composer.labelBinding(loopExit);
    }

    private void emitArrayFor(final CodeBuilder composer,
                              final Token iterationBind,
                              final Expr iterable,
                              final Stmt body) {
        final var iterableType = iterable.getType() instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : iterable.getType();
        if (!(iterableType instanceof ArrayDescriptor arrayType)) {
            throw new IllegalStateException("Resolved for-loop iterable is not an array.");
        }

        emitExpr(composer, iterable);
        emitConversion(composer, lastEmittedType, arrayType);
        final var arrayLocal = declareLoopLocal(nextLoopTemporary("array"), arrayType);
        composer.astore(arrayLocal.lvt() + localSlotOffset);

        composer.iconst_0();
        final var indexLocal = declareLoopLocal(nextLoopTemporary("index"), TypeDescriptor.ofInt());
        composer.istore(indexLocal.lvt() + localSlotOffset);
        final var valueLocal = declareLoopLocal(iterationBind, arrayType.elementType());

        final var loopStart = composer.newLabel();
        final var loopExit = composer.newLabel();
        composer.labelBinding(loopStart);
        composer.iload(indexLocal.lvt() + localSlotOffset);
        composer.aload(arrayLocal.lvt() + localSlotOffset);
        composer.arraylength();
        composer.if_icmpge(loopExit);

        composer.aload(arrayLocal.lvt() + localSlotOffset);
        composer.iload(indexLocal.lvt() + localSlotOffset);
        composer.aaload();
        emitArrayReadConversion(composer, arrayType.elementType());
        composer.storeLocal(TypeKind.fromDescriptor(
                TypeDescriptor.toJavaClassDesc(arrayType.elementType()).descriptorString()),
                valueLocal.lvt() + localSlotOffset);

        final var loopContinue = composer.newLabel();
        loopExitLabels.push(loopExit);
        loopContinueLabels.push(loopContinue);
        try {
            emitStmt(composer, body);
        } finally {
            loopContinueLabels.pop();
            loopExitLabels.pop();
        }

        composer.labelBinding(loopContinue);
        composer.iload(indexLocal.lvt() + localSlotOffset);
        composer.iconst_1();
        composer.iadd();
        composer.istore(indexLocal.lvt() + localSlotOffset);
        composer.goto_(loopStart);
        composer.labelBinding(loopExit);
    }

    private Bind declareLoopLocal(final Token name, final TypeDescriptor type) {
        symbols.declareSymbol(Resolver.SYNTHETIC_VAR, name, type, BindingMutability.IMMUTABLE);
        symbols.define(name);
        return symbols.getSymbol(name);
    }

    private Token nextLoopTemporary(final String name) {
        return new Token(TokenType.IDENTIFIER, "$for$" + name + "$" + loopTemporaryCount++, null, -1);
    }

    private Path outputPath(final String binaryName) {
        return outputDirectory.resolve(binaryName.replace('.', '/') + ".class");
    }

    private void emitPropertyAssignment(final CodeBuilder composer,
                                       final Expr.PropertyAssignment assignment,
                                       final boolean retainResult) {
        final var property = assignment.property;
        final var owner = nominalName(property.receiver.getType());
        final var erasedFieldType = TypeSubstitution.erase(
                declarationFieldType(owner, property.name.lexeme()));
        emitExpr(composer, property.receiver);
        emitExpr(composer, assignment.value);
        emitConversion(composer, lastEmittedType, erasedFieldType);
        composer.putfield(ClassDesc.of(owner), property.name.lexeme(),
                TypeDescriptor.toJavaClassDesc(erasedFieldType));
        if (retainResult) emitUnitValue(composer);
        lastEmittedType = TypeDescriptor.ofUnit();
    }

    private void emitIntrinsicOperation(final CodeBuilder composer,
                                        final Expr expression,
                                        final boolean retainResult) {
        final var operation = switch (expression) {
            case Expr.ArrayLiteral literal -> literal.intrinsicOperation();
            case Expr.Property property -> property.intrinsicOperation();
            case Expr.Index index -> index.intrinsicOperation();
            case Expr.IndexAssignment assignment -> assignment.intrinsicOperation();
            default -> null;
        };
        if (operation == null) {
            throw new IllegalStateException("Attempted to emit an unresolved intrinsic operation.");
        }

        switch (operation.id()) {
            case ARRAY_LITERAL -> emitArrayLiteral(composer, (Expr.ArrayLiteral) expression, operation);
            case ARRAY_LENGTH -> {
                final var property = (Expr.Property) expression;
                emitExpr(composer, property.receiver);
                composer.arraylength();
                lastEmittedType = operation.resultType();
            }
            case ARRAY_READ -> emitArrayRead(composer, (Expr.Index) expression, operation);
            case ARRAY_WRITE -> emitArrayWrite(composer,
                    (Expr.IndexAssignment) expression, operation, retainResult);
        }
    }

    private void emitArrayLiteral(final CodeBuilder composer,
                                  final Expr.ArrayLiteral literal,
                                  final ResolvedIntrinsicOperation operation) {
        final var elementType = operation.parameterTypes().getFirst();
        composer.ldc(literal.elements.size());
        composer.anewarray(ClassDesc.of("java.lang.Object"));
        for (int i = 0; i < literal.elements.size(); i++) {
            composer.dup();
            composer.ldc(i);
            final var element = literal.elements.get(i);
            emitExpr(composer, element);
            emitConversion(composer, lastEmittedType, elementType);
            emitBox(composer, lastEmittedType);
            composer.aastore();
        }
        lastEmittedType = operation.resultType();
    }

    private void emitArrayRead(final CodeBuilder composer,
                               final Expr.Index index,
                               final ResolvedIntrinsicOperation operation) {
        emitExpr(composer, index.array);
        composer.dup();
        composer.arraylength();
        emitExpr(composer, index.index);
        emitArrayBoundsCheck(composer);
        composer.aaload();
        emitArrayReadConversion(composer, operation.resultType());
    }

    private void emitArrayWrite(final CodeBuilder composer,
                                final Expr.IndexAssignment assignment,
                                final ResolvedIntrinsicOperation operation,
                                final boolean retainResult) {
        emitExpr(composer, assignment.array);
        composer.dup();
        composer.arraylength();
        emitExpr(composer, assignment.index);
        emitArrayBoundsCheck(composer);
        emitExpr(composer, assignment.value);
        final var elementType = operation.parameterTypes().get(2);
        emitConversion(composer, lastEmittedType, elementType);
        emitBox(composer, lastEmittedType);
        composer.aastore();
        if (retainResult) emitUnitValue(composer);
        lastEmittedType = operation.resultType();
    }

    private void emitExpr(final CodeBuilder composer, final Expr expr) {
        switch (expr) {
            case Expr.Property property -> {
                if (property.intrinsicOperation() != null) {
                    emitIntrinsicOperation(composer, property, true);
                } else {
                    final var owner = nominalName(property.receiver.getType());
                    final var fieldType = declarationFieldType(owner, property.name.lexeme());
                    emitExpr(composer, property.receiver);
                    composer.getfield(ClassDesc.of(owner), property.name.lexeme(),
                        TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(fieldType)));
                    emitConversion(composer, TypeSubstitution.erase(fieldType), property.getType());
                    lastEmittedType = property.getType();
                }
            }
            case Expr.PropertyAssignment assignment -> emitPropertyAssignment(composer, assignment, true);
            case Expr.ArrayLiteral literal -> emitIntrinsicOperation(composer, literal, true);
            case Expr.Index index -> emitIntrinsicOperation(composer, index, true);
            case Expr.IndexAssignment assignment -> emitIntrinsicOperation(composer, assignment, true);
            case Expr.Assignment assignment -> {
                final var binding = symbols.getSymbol(assignment.name);
                emitExpr(composer, assignment.value);
                emitConversion(composer, lastEmittedType, binding.type());
                duplicateValue(composer, binding.type());
                if (binding.lvt() == SymbolTable.GLOBAL) {
                    composer.putstatic(ClassDesc.of(holderForDeclaration(binding.declaration())), assignment.name.lexeme(),
                            TypeDescriptor.toJavaClassDesc(binding.type()));
                } else {
                    composer.storeLocal(
                            TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(binding.type()).descriptorString()),
                            binding.lvt() + localSlotOffset);
                }
                lastEmittedType = binding.type();
            }
            case Expr.Binary binary -> {
                if (binary.getType() instanceof BooleanDescriptor) {
                    emitComparison(composer, binary);
                } else switch (TypeDescriptor.toJavaClassDesc(binary.getType()).descriptorString()) {
                    case "I" -> {
                        emitExpr(composer, binary.left);
                        emitExpr(composer, binary.right);
                        switch (binary.operator.type()) {
                            case PLUS -> composer.iadd();
                            case MINUS -> composer.isub();
                            case STAR -> composer.imul();
                            case SLASH -> composer.idiv();
                            case PERCENT -> composer.irem();
                            case AMPERSAND -> composer.iand();
                            case PIPE -> composer.ior();
                            case CARET -> composer.ixor();
                            case SHIFT_LEFT -> composer.ishl();
                            case SHIFT_RIGHT -> composer.ishr();
                            case UNSIGNED_SHIFT_RIGHT -> composer.iushr();
                            default -> throw new IllegalStateException();
                        }
                        lastEmittedType = TypeDescriptor.ofInt();
                    }
                    case "D" -> {
                        emitExpr(composer, binary.left);
                        emitExpr(composer, binary.right);
                        switch (binary.operator.type()) {
                            case PLUS -> composer.dadd();
                            case MINUS -> composer.dsub();
                            case STAR -> composer.dmul();
                            case SLASH -> composer.ddiv();
                            case PERCENT -> composer.drem();
                            default -> throw new IllegalStateException();
                        }
                        lastEmittedType = TypeDescriptor.ofFloat();
                    }
                    case "Ljava/lang/String;" -> {
                        emitExpr(composer, binary.left);
                        emitExpr(composer, binary.right);
                        final var handle = MethodHandleDesc.of(
                                DirectMethodHandleDesc.Kind.STATIC,
                                ClassDesc.of("java.lang.invoke.StringConcatFactory"),
                                "makeConcatWithConstants",
                                MethodTypeDesc.of(
                                        ClassDesc.of("java.lang.invoke.CallSite"),
                                        ClassDesc.of("java.lang.invoke.MethodHandles$Lookup"),
                                        ConstantDescs.CD_String,
                                        ClassDesc.of("java.lang.invoke.MethodType"),
                                        ConstantDescs.CD_String,
                                        ConstantDescs.CD_Object.arrayType()).descriptorString());
                        final var dcsd = DynamicCallSiteDesc.of(
                                handle,
                                "makeConcatWithConstants",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                                "\u0001\u0001");
                        composer.invokedynamic(dcsd);
                        lastEmittedType = TypeDescriptor.ofString();
                    }
                }
            }
            case Expr.Grouping grouping -> emitExpr(composer, grouping.expression);
            case Expr.If iff -> emitIfExpression(composer, iff);
            case Expr.Logical logical -> emitLogical(composer, logical);
            case Expr.TypeTest test -> emitTypeTest(composer, test);
            case Expr.Cast cast -> emitCast(composer, cast);
            case Expr.Call call -> emitCall(composer, call);
            case Expr.MemberCall call -> emitMemberCall(composer, call);
            case Expr.Lambda lambda -> {
                final var functionType = (FunctionDescriptor) lambda.getType();
                final var captures = lambdaPlan.captures(lambda);
                final var captureTypes = lambdaPlan.captureTypes(lambda);
                final var implementationParameters = new ArrayList<>(captureTypes);
                implementationParameters.addAll(functionType.parameters());
                for (int i = 0; i < captures.size(); i++) {
                    emitVariable(composer, captures.get(i));
                    emitConversion(composer, lastEmittedType, captureTypes.get(i));
                }

                final var generatedInterface = TypeDescriptor.toJavaClassDesc(functionType);
                final var samMethodType = toJavaMethodDescriptor(functionType);
                final var implementationType = toJavaMethodDescriptor(functionType, implementationParameters);
                final var metafactoryType = MethodTypeDesc.of(
                    ClassDesc.of("java.lang.invoke.CallSite"),
                    ClassDesc.of("java.lang.invoke.MethodHandles$Lookup"),
                    ConstantDescs.CD_String,
                    ClassDesc.of("java.lang.invoke.MethodType"),
                    ClassDesc.of("java.lang.invoke.MethodType"),
                    ClassDesc.of("java.lang.invoke.MethodHandle"),
                    ClassDesc.of("java.lang.invoke.MethodType"));
                final var metafactory = MethodHandleDesc.of(
                    DirectMethodHandleDesc.Kind.STATIC,
                    ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
                    "metafactory",
                    metafactoryType.descriptorString());
                final var implementation = MethodHandleDesc.of(
                    DirectMethodHandleDesc.Kind.STATIC,
                    ClassDesc.of(mainClassName),
                    lambdaPlan.lambdaMethodName(lambda),
                        implementationType.descriptorString());
                composer.invokedynamic(DynamicCallSiteDesc.of(
                    metafactory,
                    "invoke",
                    MethodTypeDesc.of(generatedInterface,
                            captureTypes.stream().map(TypeDescriptor::toJavaClassDesc).toList()),
                    samMethodType,
                    implementation,
                    samMethodType));
                lastEmittedType = lambda.getType();
            }
            case Expr.Literal literal -> {
                switch (literal.value) {
                    case String s -> {
                        composer.ldc(s);
                        lastEmittedType = TypeDescriptor.ofString();
                    }
                    case Integer i -> {
                        composer.ldc(i);
                        lastEmittedType = TypeDescriptor.ofInt();
                    }
                    case Double d -> {
                        composer.ldc(d);
                        lastEmittedType = TypeDescriptor.ofFloat();
                    }
                    case Boolean b -> {
                        if (b) composer.iconst_1();
                        else composer.iconst_0();
                        lastEmittedType = TypeDescriptor.ofBoolean();
                    }
                    case UnitLiteral _ -> {
                        emitUnitValue(composer);
                        lastEmittedType = TypeDescriptor.ofUnit();
                    }
                    case null -> {
                        composer.aconst_null();
                        lastEmittedType = TypeDescriptor.ofNull();
                    }
                    default -> throw new IllegalStateException("Unsupported value");
                }
            }
            case Expr.Unary unary -> {
                if (unary.operator.type() == TokenType.NOT) {
                    emitExpr(composer, unary.right);
                    composer.iconst_1();
                    composer.ixor();
                    lastEmittedType = TypeDescriptor.ofBoolean();
                } else if (unary.operator.type() == TokenType.TILDE) {
                    emitExpr(composer, unary.right);
                    composer.iconst_m1();
                    composer.ixor();
                    lastEmittedType = TypeDescriptor.ofInt();
                } else {
                    emitExpr(composer, unary.right);
                    final var opcode = TypeDescriptor.toJavaClassDesc(unary.getType()).descriptorString();
                    if (unary.operator.type() == TokenType.MINUS) {
                        if (opcode.equals("I")) composer.ineg();
                        else if (opcode.equals("D")) composer.dneg();
                        else throw new IllegalStateException("Unary negation requires Int or Float.");
                    } else if (unary.operator.type() != TokenType.PLUS) {
                        throw new IllegalStateException("Unsupported unary operator.");
                    }
                    lastEmittedType = unary.getType();
                }
            }
            case Expr.Variable variable -> {
                if (variable.resolvedFunctionName() != null) {
                    emitFunctionReference(composer, variable);
                } else {
                    emitVariable(composer, variable.name);
                    if (!(variable.getType() instanceof InferDescriptor)
                            && !lastEmittedType.equals(variable.getType())) {
                        emitConversion(composer, lastEmittedType, variable.getType());
                    }
                }
            }
            default -> throw new UnsupportedOperationException();
        }
    }

    private void emitLogical(final CodeBuilder composer, final Expr.Logical logical) {
        final var shortCircuit = composer.newLabel();
        final var done = composer.newLabel();
        emitExpr(composer, logical.left);
        if (logical.operator.type() == TokenType.AND) composer.ifeq(shortCircuit);
        else composer.ifne(shortCircuit);
        emitExpr(composer, logical.right);
        composer.goto_(done);
        composer.labelBinding(shortCircuit);
        if (logical.operator.type() == TokenType.AND) composer.iconst_0();
        else composer.iconst_1();
        composer.labelBinding(done);
        lastEmittedType = TypeDescriptor.ofBoolean();
    }

    private void emitIfExpression(final CodeBuilder composer, final Expr.If iff) {
        final var elseLabel = composer.newLabel();
        final var doneLabel = composer.newLabel();
        emitExpr(composer, iff.condition);
        composer.ifeq(elseLabel);
        emitExpr(composer, iff.thenExpr);
        emitConversion(composer, lastEmittedType, iff.getType());
        composer.goto_(doneLabel);
        composer.labelBinding(elseLabel);
        emitExpr(composer, iff.elseExpr);
        emitConversion(composer, lastEmittedType, iff.getType());
        composer.labelBinding(doneLabel);
        lastEmittedType = iff.getType();
    }

    private void emitTypeTest(final CodeBuilder composer, final Expr.TypeTest test) {
        emitExpr(composer, test.value);
        emitBox(composer, lastEmittedType);
        composer.instanceOf(runtimeTypeTestClass(test.targetType));
        lastEmittedType = TypeDescriptor.ofBoolean();
    }

    private void emitCast(final CodeBuilder composer, final Expr.Cast cast) {
        emitExpr(composer, cast.value);
        emitBox(composer, lastEmittedType);
        final var targetClass = runtimeTypeTestClass(cast.targetType);
        if (cast.safe) {
            final var failed = composer.newLabel();
            final var done = composer.newLabel();
            composer.dup();
            composer.instanceOf(targetClass);
            composer.ifeq(failed);
            composer.checkcast(targetClass);
            composer.goto_(done);
            composer.labelBinding(failed);
            composer.pop();
            composer.aconst_null();
            composer.labelBinding(done);
            lastEmittedType = cast.getType();
            return;
        }

        if (cast.targetType instanceof IntDescriptor
                || cast.targetType instanceof FloatDescriptor
                || cast.targetType instanceof BooleanDescriptor) {
            emitUnboxOrCast(composer, cast.targetType);
        } else {
            composer.checkcast(targetClass);
        }
        lastEmittedType = cast.getType();
    }

    private ClassDesc runtimeTypeTestClass(final TypeDescriptor type) {
        return switch (type) {
            case IntDescriptor _ -> ConstantDescs.CD_Integer;
            case FloatDescriptor _ -> ConstantDescs.CD_Double;
            case BooleanDescriptor _ -> ConstantDescs.CD_Boolean;
            case AnyDescriptor _ -> ConstantDescs.CD_Object;
            case UnitDescriptor _ -> TypeDescriptor.toJavaClassDesc(type);
            case StringDescriptor _ -> TypeDescriptor.toJavaClassDesc(type);
            case NominalDescriptor _ -> TypeDescriptor.toJavaClassDesc(type);
            default -> throw new IllegalStateException("Unsupported runtime type-test target: " + type);
        };
    }

    private void emitComparison(final CodeBuilder composer, final Expr.Binary binary) {
        final var operandDescriptor = TypeDescriptor.toJavaClassDesc(binary.left.getType()).descriptorString();
        final var matched = composer.newLabel();
        final var done = composer.newLabel();
        emitExpr(composer, binary.left);
        emitExpr(composer, binary.right);

        switch (operandDescriptor) {
            case "I", "Z" -> branchIntegerComparison(composer, binary.operator.type(), matched);
            case "D" -> {
                switch (binary.operator.type()) {
                    case LESS, LESS_EQUAL -> composer.dcmpg();
                    default -> composer.dcmpl();
                }
                switch (binary.operator.type()) {
                    case EQUAL_EQUAL -> composer.ifeq(matched);
                    case BANG_EQUAL -> composer.ifne(matched);
                    case GREATER -> composer.ifgt(matched);
                    case GREATER_EQUAL -> composer.ifge(matched);
                    case LESS -> composer.iflt(matched);
                    case LESS_EQUAL -> composer.ifle(matched);
                    default -> throw new IllegalStateException("Unsupported comparison operator.");
                }
            }
            default -> {
                if (binary.operator.type() != TokenType.EQUAL_EQUAL
                        && binary.operator.type() != TokenType.BANG_EQUAL) {
                    throw new IllegalStateException("Relational comparison requires a numeric type.");
                }
                composer.invokestatic(ClassDesc.of("java.util.Objects"), "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean,
                                ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                if (binary.operator.type() == TokenType.EQUAL_EQUAL) composer.ifne(matched);
                else composer.ifeq(matched);
            }
        }

        composer.iconst_0();
        composer.goto_(done);
        composer.labelBinding(matched);
        composer.iconst_1();
        composer.labelBinding(done);
        lastEmittedType = TypeDescriptor.ofBoolean();
    }

    private void branchIntegerComparison(final CodeBuilder composer,
                                        final TokenType operator,
                                        final Label target) {
        switch (operator) {
            case EQUAL_EQUAL -> composer.if_icmpeq(target);
            case BANG_EQUAL -> composer.if_icmpne(target);
            case GREATER -> composer.if_icmpgt(target);
            case GREATER_EQUAL -> composer.if_icmpge(target);
            case LESS -> composer.if_icmplt(target);
            case LESS_EQUAL -> composer.if_icmple(target);
            default -> throw new IllegalStateException("Unsupported comparison operator.");
        }
    }

    private void emitCall(final CodeBuilder composer, final Expr.Call call) {
        if (call.resolvedFunctionName() != null) {
            final var functionName = call.resolvedFunctionName();
            final var functionToken = resolver.functionSymbolToken(functionName);
            final var functionType = (FunctionDescriptor) symbols.getFunction(functionToken).type();
            final var runtimeType = functionType.isGeneric()
                    ? (FunctionDescriptor) TypeSubstitution.erase(functionType)
                    : functionType;
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(composer, call.arguments.get(i));
                emitConversion(composer, lastEmittedType, functionType.parameters().get(i));
            }
                composer.invokestatic(ClassDesc.of(functionOwners.get(functionName)),
                    functionName.substring(functionName.lastIndexOf('.') + 1), toJavaMethodDescriptor(runtimeType));
            if (functionType.isGeneric()) {
                final var instantiated = TypeSubstitution.erase(call.getType());
                emitConversion(composer, runtimeType.returnType(), instantiated);
                lastEmittedType = instantiated;
            } else {
                lastEmittedType = functionType.returnType();
            }
            return;
        }

        final var binding = symbols.getSymbol(call.callee);
        final var bindingType = binding.type() instanceof ReferenceDescriptor reference
            ? reference.baseType()
            : binding.type();
        if (!(bindingType instanceof FunctionDescriptor functionType)) {
            throw new IllegalStateException("Resolved call target is not a function");
        }

        final var runtimeType = functionType.isGeneric()
                ? (FunctionDescriptor) TypeSubstitution.erase(functionType)
                : functionType;
        emitVariable(composer, call.callee);
        for (int i = 0; i < call.arguments.size(); i++) {
            emitExpr(composer, call.arguments.get(i));
            emitConversion(composer, lastEmittedType, runtimeType.parameters().get(i));
        }
        composer.invokeinterface(TypeDescriptor.toJavaClassDesc(runtimeType), "invoke",
                toJavaMethodDescriptor(runtimeType));
        if (functionType.isGeneric()) {
            final var instantiatedReturn = TypeSubstitution.erase(call.getType());
            emitConversion(composer, runtimeType.returnType(), instantiatedReturn);
            lastEmittedType = call.getType();
        } else {
            lastEmittedType = functionType.returnType();
        }
    }

        private void emitFunctionReference(final CodeBuilder composer, final Expr.Variable reference) {
        final var functionType = reference.specializedFunctionType();
        final var generatedInterface = TypeDescriptor.toJavaClassDesc(functionType);
        final var samMethodType = toJavaMethodDescriptor(functionType);
        final var helper = lambdaPlan.functionReference(reference);
        if (helper == null) {
            throw new IllegalStateException("Missing specialization bridge for "
                + reference.resolvedFunctionName());
        }
        final var metafactoryType = MethodTypeDesc.of(
            ClassDesc.of("java.lang.invoke.CallSite"),
            ClassDesc.of("java.lang.invoke.MethodHandles$Lookup"),
            ConstantDescs.CD_String,
            ClassDesc.of("java.lang.invoke.MethodType"),
            ClassDesc.of("java.lang.invoke.MethodType"),
            ClassDesc.of("java.lang.invoke.MethodHandle"),
            ClassDesc.of("java.lang.invoke.MethodType"));
        final var metafactory = MethodHandleDesc.of(
            DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
            "metafactory",
            metafactoryType.descriptorString());
        final var implementation = MethodHandleDesc.of(
            DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of(mainClassName),
            helper.helperName(),
            toJavaMethodDescriptor(helper.targetType()).descriptorString());
        composer.invokedynamic(DynamicCallSiteDesc.of(
            metafactory,
            "invoke",
            MethodTypeDesc.of(generatedInterface),
            samMethodType,
            implementation,
            samMethodType));
        lastEmittedType = functionType;
        }

        private void emitFunctionReferenceImplementation(final CodeBuilder composer,
                                 final LambdaCompilationPlan.FunctionReference reference) {
        final var sourceType = reference.sourceType();
        final var targetType = reference.targetType();
        var slot = 0;
        for (int i = 0; i < targetType.arity(); i++) {
            final var parameterType = targetType.parameters().get(i);
            loadLocal(composer, slot, parameterType);
            emitConversion(composer, parameterType, sourceType.parameters().get(i));
            slot += TypeDescriptor.toJavaClassDesc(parameterType).descriptorString().equals("D") ? 2 : 1;
        }
        final var owner = functionOwners.get(reference.functionName());
        if (owner == null) {
            throw new IllegalStateException("Missing JVM owner for " + reference.functionName());
        }
        composer.invokestatic(ClassDesc.of(owner),
            reference.functionName().substring(reference.functionName().lastIndexOf('.') + 1),
            toJavaMethodDescriptor(sourceType));
        emitConversion(composer, sourceType.returnType(), targetType.returnType());
        composer.return_(TypeKind.fromDescriptor(
            TypeDescriptor.toJavaClassDesc(targetType.returnType()).descriptorString()));
        }

    private void emitMemberCall(final CodeBuilder composer, final Expr.MemberCall call) {
        if (call.javaCallTarget() != null) {
            emitJavaMemberCall(composer, call);
            return;
        }
        if (call.name.lexeme().equals("new") && call.resolvedClassName() != null) {
            final var declaration = resolver.classes().get(call.resolvedClassName());
            if (declaration == null) throw new IllegalStateException("Resolved constructor class not found.");
            final var classDesc = ClassDesc.of(call.resolvedClassName());
            final var parameters = declaration.fields().stream()
                    .map(field -> TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(field.type())))
                    .toList();
            composer.new_(classDesc);
            composer.dup();
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(composer, call.arguments.get(i));
                emitConversion(composer, lastEmittedType,
                        TypeSubstitution.erase(declaration.fields().get(i).type()));
            }
            composer.invokespecial(classDesc, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, parameters));
            lastEmittedType = call.getType();
            return;
        }

            if (call.resolvedClassName() != null) {
                final var declaration = resolver.classes().get(call.resolvedClassName());
                final var namedConstructor = declaration == null ? null : declaration.namedConstructors().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(call.name.lexeme()))
                    .findFirst()
                    .orElse(null);
                if (namedConstructor != null) {
                final var erasedFactory = (FunctionDescriptor) TypeSubstitution.erase(
                    namedConstructor.typeDescriptor());
                for (int i = 0; i < call.arguments.size(); i++) {
                    emitExpr(composer, call.arguments.get(i));
                    emitConversion(composer, lastEmittedType,
                        TypeSubstitution.erase(namedConstructor.typeDescriptor().parameters().get(i)));
                }
                composer.invokestatic(ClassDesc.of(call.resolvedClassName()), call.name.lexeme(),
                    toJavaMethodDescriptor(erasedFactory));
                final var erasedReturnType = TypeSubstitution.erase(namedConstructor.typeDescriptor().returnType());
                emitConversion(composer, erasedReturnType, call.getType());
                lastEmittedType = call.getType();
                return;
                }
            }

        final var ownerName = call.resolvedOwnerName() != null
            ? call.resolvedOwnerName()
            : nominalName(call.receiver.getType());
        final var classOwner = resolver.classes().get(ownerName);
        final var classMethod = classOwner == null ? null : classOwner.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .findFirst()
                .orElse(null);
        final var contract = resolver.contracts().get(ownerName);
        final var contractMethod = contract == null ? null : contract.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .findFirst()
                .orElse(null);
        final var descriptor = classMethod != null
                ? classMethod.typeDescriptor()
                : contractMethod.typeDescriptor();
        final var resolvedDescriptor = call.resolvedDescriptor() == null
            ? descriptor
            : call.resolvedDescriptor();

        emitExpr(composer, call.receiver);
        if (call.receiverRequiresCast()) composer.checkcast(ClassDesc.of(ownerName));
        for (int i = 0; i < call.arguments.size(); i++) {
            emitExpr(composer, call.arguments.get(i));
            emitConversion(composer, lastEmittedType,
                TypeSubstitution.erase(descriptor.parameters().get(i)));
        }
        if (contractMethod != null) {
            composer.invokeinterface(ClassDesc.of(ownerName), call.name.lexeme(),
                    toJavaMethodDescriptor(descriptor));
        } else if (!classMethod.isPublic()) {
            composer.invokespecial(ClassDesc.of(ownerName), call.name.lexeme(),
                    toJavaMethodDescriptor(descriptor));
        } else {
            composer.invokevirtual(ClassDesc.of(ownerName), call.name.lexeme(),
                    toJavaMethodDescriptor(descriptor));
        }
        final var erasedReturnType = TypeSubstitution.erase(descriptor.returnType());
        emitConversion(composer, erasedReturnType, resolvedDescriptor.returnType());
        lastEmittedType = resolvedDescriptor.returnType();
    }

    private void emitJavaMemberCall(final CodeBuilder composer, final Expr.MemberCall call) {
        final var target = call.javaCallTarget();
        final var owner = ClassDesc.of(target.owner());
        final var descriptor = MethodTypeDesc.ofDescriptor(target.descriptor());
        if (target.kind() == JavaCallTarget.InvocationKind.CONSTRUCTOR) {
            composer.new_(owner);
            composer.dup();
        } else if (target.kind() != JavaCallTarget.InvocationKind.STATIC) {
            emitExpr(composer, call.receiver);
        }
        final var fixedParameterCount = target.varArgs()
                ? descriptor.parameterCount() - 1
                : descriptor.parameterCount();
        for (int i = 0; i < fixedParameterCount; i++) {
            emitExpr(composer, call.arguments.get(i));
            emitConversion(composer, lastEmittedType, call.resolvedDescriptor().parameters().get(i));
        }
        if (target.varArgs()) {
            emitJavaVarargsArray(composer, call, fixedParameterCount,
                    descriptor.parameterType(fixedParameterCount).componentType());
        }
        switch (target.kind()) {
            case CONSTRUCTOR -> composer.invokespecial(owner, "<init>", descriptor);
            case STATIC -> composer.invokestatic(owner, target.name(), descriptor);
            case INTERFACE -> composer.invokeinterface(owner, target.name(), descriptor);
            case VIRTUAL -> composer.invokevirtual(owner, target.name(), descriptor);
        }
        if (target.kind() != JavaCallTarget.InvocationKind.CONSTRUCTOR
            && descriptor.returnType().equals(ConstantDescs.CD_void)) {
            emitUnitValue(composer);
        }
        lastEmittedType = call.getType();
    }

    private void emitJavaVarargsArray(final CodeBuilder composer,
                                      final Expr.MemberCall call,
                                      final int fixedParameterCount,
                                      final ClassDesc componentType) {
        final var componentDescriptor = componentType.descriptorString();
        final var varargCount = call.arguments.size() - fixedParameterCount;
        composer.ldc(varargCount);
        switch (componentDescriptor) {
            case "I" -> composer.newarray(TypeKind.INT);
            case "D" -> composer.newarray(TypeKind.DOUBLE);
            case "Z" -> composer.newarray(TypeKind.BOOLEAN);
            default -> composer.anewarray(componentType);
        }
        for (int index = 0; index < varargCount; index++) {
            final var argumentIndex = fixedParameterCount + index;
            composer.dup();
            composer.ldc(index);
            emitExpr(composer, call.arguments.get(argumentIndex));
            emitConversion(composer, lastEmittedType,
                    call.resolvedDescriptor().parameters().get(argumentIndex));
            switch (componentDescriptor) {
                case "I" -> composer.iastore();
                case "D" -> composer.dastore();
                case "Z" -> composer.bastore();
                default -> composer.aastore();
            }
        }
    }

    private void emitExternalFunction(final CodeBuilder composer,
                                      final FunctionDescriptor signature,
                                      final FunctionBindingRegistry.Binding binding) {
        final var target = binding.target();
        final MethodTypeDesc methodDescriptor;
        if (target instanceof FunctionBindingRegistry.StaticMethod staticMethod) {
            methodDescriptor = staticMethod.descriptor();
            emitExternalFunctionArguments(composer, signature);
            composer.invokestatic(staticMethod.owner(), staticMethod.methodName(), methodDescriptor);
        } else if (target instanceof FunctionBindingRegistry.StaticFieldInstanceMethod fieldMethod) {
            methodDescriptor = fieldMethod.methodDescriptor();
            composer.getstatic(fieldMethod.fieldOwner(), fieldMethod.fieldName(), fieldMethod.fieldType());
            emitExternalFunctionArguments(composer, signature);
            composer.invokevirtual(fieldMethod.methodOwner(), fieldMethod.methodName(), methodDescriptor);
        } else {
            throw new IllegalStateException("Unsupported external function binding target.");
        }
        if (methodDescriptor.returnType().equals(ConstantDescs.CD_void)) {
            emitUnitValue(composer);
            composer.areturn();
        } else {
            composer.return_(TypeKind.fromDescriptor(methodDescriptor.returnType().descriptorString()));
        }
        lastEmittedType = signature.returnType();
    }

    private void emitExternalFunctionArguments(final CodeBuilder composer,
                                               final FunctionDescriptor signature) {
        var slot = 0;
        for (final var parameter : signature.parameters()) {
            loadLocal(composer, slot, parameter);
            slot += TypeDescriptor.toJavaClassDesc(parameter).descriptorString().equals("D") ? 2 : 1;
        }
    }

    private String nominalName(final TypeDescriptor type) {
        var baseType = type instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : type;
        if (baseType instanceof GenericDescriptor generic) baseType = generic.baseType();
        if (!(baseType instanceof NominalDescriptor nominal)) {
            throw new IllegalStateException("Expected a nominal receiver, found " + type);
        }
        return nominal.name();
    }

    private TypeDescriptor declarationFieldType(final String ownerName, final String fieldName) {
        final var declaration = resolver.classes().get(ownerName);
        if (declaration == null) throw new IllegalStateException("Field owner is not a class: " + ownerName);
        return declaration.fields().stream()
                .filter(field -> field.name().lexeme().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Resolved field not found: " + fieldName))
                .type();
    }

    private void emitLambdaImplementation(final CodeBuilder composer, final Expr.Lambda lambda) {
        if (!(lambda.getType() instanceof FunctionDescriptor functionType)) {
            throw new IllegalStateException("Lambda has no resolved function type");
        }

        final var captures = lambdaPlan.captures(lambda);
        final var allParameters = new ArrayList<TypeDescriptor>();
        allParameters.addAll(lambdaPlan.captureTypes(lambda));
        for (final var parameter : functionType.parameters()) {
            allParameters.add(parameter);
        }
        final var helperDescriptor = toJavaMethodDescriptor(functionType, allParameters);

        final var previousOffset = localSlotOffset;
        final var previousReturnType = currentReturnType;
        beginScope();
        localSlotOffset = 0;
        currentReturnType = functionType.returnType();
        try {
            for (int i = 0; i < captures.size(); i++) {
                final var capture = captures.get(i);
                final var captureType = lambdaPlan.captureTypes(lambda).get(i);
                symbols.declareSymbol(Resolver.SYNTHETIC_VAR, capture, captureType, BindingMutability.IMMUTABLE);
                symbols.define(capture);
            }
            for (int i = 0; i < lambda.params.size(); i++) {
                final var param = lambda.params.get(i);
                final var parameterType = functionType.parameters().get(i);
                symbols.declareSymbol(Resolver.SYNTHETIC_VAR, param, parameterType, BindingMutability.IMMUTABLE);
                symbols.define(param);
            }

            if (lambda.body.size() == 1 && lambda.body.getFirst() instanceof Stmt.Return ret) {
                if (ret.value() == null) {
                    emitUnitValue(composer);
                    lastEmittedType = TypeDescriptor.ofUnit();
                } else {
                    emitExpr(composer, ret.value());
                }
                emitConversion(composer, lastEmittedType, functionType.returnType());
                composer.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(functionType.returnType()).descriptorString()));
            } else {
                emitStmts(composer, lambda.body);
                if (functionType.returnType() instanceof UnitDescriptor) {
                    emitUnitValue(composer);
                }
                composer.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(functionType.returnType()).descriptorString()));
            }
        } finally {
            endScope();
            localSlotOffset = previousOffset;
            currentReturnType = previousReturnType;
        }
    }

    private static MethodTypeDesc toJavaMethodDescriptor(final FunctionDescriptor functionType,
                                                        final List<TypeDescriptor> parameters) {
        final var javaParameters = parameters.stream()
                .map(TypeDescriptor::toJavaClassDesc)
                .toList();
        return MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(functionType.returnType()), javaParameters);
    }

    private void emitVariable(final CodeBuilder composer, final Token name) {
        if (!symbols.containsSymbol(name)) {
            if (symbols.containsAnySymbol(name)) {
                final var binding = symbols.getAnySymbol(name);
                if (binding.lvt() == SymbolTable.GLOBAL) {
                    composer.getstatic(ClassDesc.of(holderForDeclaration(binding.declaration())), name.lexeme(),
                            TypeDescriptor.toJavaClassDesc(binding.type()));
                    lastEmittedType = binding.type();
                    return;
                }
            }
            Zeron.debug("emitVariable missing symbol: " + name.lexeme() + " locals=" + symbols.getLocals());
            throw new IllegalStateException("Unknown symbol: " + name.lexeme());
        }
        final var binding = symbols.getSymbol(name);
        if (binding.type() instanceof FunctionDescriptor) {
            composer.aload(binding.lvt());
            lastEmittedType = binding.type();
            return;
        }
        if (binding.lvt() == SymbolTable.GLOBAL) {
            composer.getstatic(ClassDesc.of(holderForDeclaration(binding.declaration())), name.lexeme(),
                    TypeDescriptor.toJavaClassDesc(binding.type()));
        } else {
            switch (TypeDescriptor.toJavaClassDesc(binding.type()).descriptorString()) {
                case "I", "Z" -> composer.iload(binding.lvt() + localSlotOffset);
                case "D" -> composer.dload(binding.lvt() + localSlotOffset);
                default -> composer.aload(binding.lvt() + localSlotOffset);
            }
        }
        lastEmittedType = binding.type();
    }

    private String holderForDeclaration(final Stmt declaration) {
        return declarationOwners.getOrDefault(declaration, currentHolderName == null ? mainClassName : currentHolderName);
    }

    private void emitBox(final CodeBuilder composer, final TypeDescriptor type) {
        if (type == null) return;
        final var boxer = switch (type) {
            case IntDescriptor _ -> "I";
            case FloatDescriptor _ -> "D";
            case BooleanDescriptor _ -> "B";
            default -> null;
        };
        if (boxer != null) {
            composer.invokestatic(getAutoboxingFor(composer.constantPool(), boxer));
        }
    }

    private void emitUnitValue(final CodeBuilder composer) {
        composer.getstatic(UNIT_VALUE_CLASS, "INSTANCE", UNIT_VALUE_CLASS);
    }

    private void emitPop(final CodeBuilder composer, final TypeDescriptor type) {
        switch (TypeDescriptor.toJavaClassDesc(type).descriptorString()) {
            case "V" -> {}
            case "D", "J" -> composer.pop2();
            default -> composer.pop();
        }
    }

    private void duplicateValue(final CodeBuilder composer, final TypeDescriptor type) {
        switch (TypeDescriptor.toJavaClassDesc(type).descriptorString()) {
            case "D", "J" -> composer.dup2();
            default -> composer.dup();
        }
    }

    private void emitConversion(final CodeBuilder composer,
                                final TypeDescriptor sourceType,
                                final TypeDescriptor targetType) {
        final var sourceFunction = functionView(sourceType);
        final var targetFunction = functionView(targetType);
        if (sourceFunction != null && targetFunction != null) {
            final var erasedSource = (FunctionDescriptor) TypeSubstitution.erase(sourceFunction);
            final var erasedTarget = (FunctionDescriptor) TypeSubstitution.erase(targetFunction);
            if (!TypeDescriptor.toJavaClassDesc(erasedSource).equals(TypeDescriptor.toJavaClassDesc(erasedTarget))) {
                if (isNullableFunctionView(sourceType) && isNullableFunctionView(targetType)) {
                    final var adapterName = lambdaPlan.nullableAdapterName(erasedSource, erasedTarget);
                    if (adapterName == null) {
                        throw new IllegalStateException("Missing nullable function adapter for "
                                + erasedSource + " to " + erasedTarget);
                    }
                    composer.invokestatic(ClassDesc.of(mainClassName), adapterName,
                            MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(erasedTarget),
                                    TypeDescriptor.toJavaClassDesc(erasedSource)));
                } else {
                    emitFunctionAdapter(composer, erasedSource, erasedTarget);
                }
            }
            lastEmittedType = targetType;
            return;
        }
        if (sourceType.equals(targetType) || sourceType instanceof NullDescriptor && targetType.isNullable()
            || resolver.isContractProjection(targetType, sourceType)) {
            lastEmittedType = targetType;
            return;
        }
        if (sourceType instanceof NullableDescriptor nullable
                && nullable.baseType().equals(targetType)
                && (targetType instanceof IntDescriptor
                || targetType instanceof FloatDescriptor
                || targetType instanceof BooleanDescriptor)) {
            emitUnboxOrCast(composer, targetType);
            lastEmittedType = targetType;
            return;
        }
        if (sourceType instanceof ReferenceDescriptor reference
                && targetType.equals(reference.baseType())) {
            lastEmittedType = targetType;
            return;
        }
        if (targetType instanceof NullableDescriptor nullable
                && (sourceType.equals(nullable.baseType())
                || sourceType instanceof ReferenceDescriptor reference
                && nullable.baseType().equals(reference.baseType()))) {
            emitBox(composer, sourceType);
            lastEmittedType = targetType;
            return;
        }
        final var sourceJavaType = TypeDescriptor.toJavaClassDesc(sourceType);
        final var targetJavaType = TypeDescriptor.toJavaClassDesc(targetType);
        if (sourceJavaType.equals(targetJavaType)) {
            lastEmittedType = targetType;
            return;
        }
        if (targetJavaType.equals(ConstantDescs.CD_Object)) {
            emitBox(composer, sourceType);
            lastEmittedType = targetType;
            return;
        }
        if (sourceJavaType.equals(ConstantDescs.CD_Object)) {
            emitUnboxOrCast(composer, targetType);
            lastEmittedType = targetType;
            return;
        }
        throw new IllegalStateException("Unsupported conversion from " + sourceType + " to " + targetType);
    }

    private FunctionDescriptor functionView(final TypeDescriptor type) {
        return switch (type) {
            case FunctionDescriptor function -> function;
            case NullableDescriptor nullable -> functionView(nullable.baseType());
            case ReferenceDescriptor reference -> functionView(reference.baseType());
            default -> null;
        };
    }

    private boolean isNullableFunctionView(final TypeDescriptor type) {
        return switch (type) {
            case NullableDescriptor nullable -> functionView(nullable.baseType()) != null
                    || isNullableFunctionView(nullable.baseType());
            case ReferenceDescriptor reference -> isNullableFunctionView(reference.baseType());
            default -> false;
        };
    }

    private void emitNullableFunctionAdapterImplementation(final CodeBuilder composer,
                                                           final FunctionDescriptor sourceType,
                                                           final FunctionDescriptor targetType) {
        final var nullValue = composer.newLabel();
        composer.aload(0);
        composer.invokestatic(ClassDesc.of("java.util.Objects"), "isNull",
                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object));
        composer.ifne(nullValue);
        composer.aload(0);
        emitFunctionAdapter(composer, sourceType, targetType);
        composer.return_(TypeKind.fromDescriptor(
                TypeDescriptor.toJavaClassDesc(targetType).descriptorString()));
        composer.labelBinding(nullValue);
        composer.aconst_null();
        composer.return_(TypeKind.fromDescriptor(
                TypeDescriptor.toJavaClassDesc(targetType).descriptorString()));
    }

    private void emitFunctionAdapter(final CodeBuilder composer,
                                    final FunctionDescriptor sourceType,
                                    final FunctionDescriptor targetType) {
        final var sourceInterface = TypeDescriptor.toJavaClassDesc(sourceType);
        final var targetInterface = TypeDescriptor.toJavaClassDesc(targetType);
        final var targetMethodType = toJavaMethodDescriptor(targetType);
        final var adapterName = lambdaPlan.adapterName(sourceType, targetType);
        if (adapterName == null) {
            throw new IllegalStateException("Missing function adapter for " + sourceType + " to " + targetType);
        }
        final var implementationParameters = new ArrayList<TypeDescriptor>();
        implementationParameters.add(sourceType);
        implementationParameters.addAll(targetType.parameters());
        final var metafactoryType = MethodTypeDesc.of(
                ClassDesc.of("java.lang.invoke.CallSite"),
                ClassDesc.of("java.lang.invoke.MethodHandles$Lookup"),
                ConstantDescs.CD_String,
                ClassDesc.of("java.lang.invoke.MethodType"),
                ClassDesc.of("java.lang.invoke.MethodType"),
                ClassDesc.of("java.lang.invoke.MethodHandle"),
                ClassDesc.of("java.lang.invoke.MethodType"));
        final var metafactory = MethodHandleDesc.of(
                DirectMethodHandleDesc.Kind.STATIC,
                ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
                "metafactory",
                metafactoryType.descriptorString());
        final var implementation = MethodHandleDesc.of(
            DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of(mainClassName),
            adapterName,
            toJavaMethodDescriptor(targetType, implementationParameters).descriptorString());
        composer.invokedynamic(DynamicCallSiteDesc.of(
                metafactory,
                "invoke",
                MethodTypeDesc.of(targetInterface, sourceInterface),
                targetMethodType,
                implementation,
            targetMethodType));
    }

        private void emitFunctionAdapterImplementation(final CodeBuilder composer,
                               final FunctionDescriptor sourceType,
                               final FunctionDescriptor targetType) {
        composer.aload(0);
        var slot = 1;
        for (int i = 0; i < targetType.arity(); i++) {
            final var targetParameter = targetType.parameters().get(i);
            loadLocal(composer, slot, targetParameter);
            emitConversion(composer, targetParameter, sourceType.parameters().get(i));
            slot += TypeDescriptor.toJavaClassDesc(targetParameter).descriptorString().equals("D") ? 2 : 1;
        }
        composer.invokeinterface(TypeDescriptor.toJavaClassDesc(sourceType), "invoke",
            toJavaMethodDescriptor(sourceType));
        emitConversion(composer, sourceType.returnType(), targetType.returnType());
        composer.return_(TypeKind.fromDescriptor(
            TypeDescriptor.toJavaClassDesc(targetType.returnType()).descriptorString()));
    }

    private void emitUnboxOrCast(final CodeBuilder composer, final TypeDescriptor targetType) {
        final var targetDescriptor = TypeDescriptor.toJavaClassDesc(targetType).descriptorString();
        switch (targetDescriptor) {
            case "I" -> {
                composer.checkcast(ConstantDescs.CD_Integer);
                composer.invokevirtual(ClassDesc.of("java.lang.Integer"), "intValue",
                        MethodTypeDesc.of(ConstantDescs.CD_int));
            }
            case "D" -> {
                composer.checkcast(ClassDesc.of("java.lang.Double"));
                composer.invokevirtual(ClassDesc.of("java.lang.Double"), "doubleValue",
                        MethodTypeDesc.of(ConstantDescs.CD_double));
            }
            case "Z" -> {
                composer.checkcast(ClassDesc.of("java.lang.Boolean"));
                composer.invokevirtual(ClassDesc.of("java.lang.Boolean"), "booleanValue",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean));
            }
            default -> composer.checkcast(targetJavaClass(targetType));
        }
    }

    private ClassDesc targetJavaClass(final TypeDescriptor targetType) {
        return TypeDescriptor.toJavaClassDesc(targetType);
    }

    private void emitArrayBoundsCheck(final CodeBuilder composer) {
        composer.swap();
        composer.invokestatic(
            ClassDesc.of("java.util.Objects"),
                "checkIndex",
            MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
    }

    private void emitArrayReadConversion(final CodeBuilder composer, final TypeDescriptor targetType) {
        final var valueType = targetType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : targetType;
        if (targetType instanceof NullableDescriptor) {
            composer.checkcast(TypeDescriptor.toJavaClassDesc(targetType));
        } else if (valueType instanceof IntDescriptor) {
            composer.checkcast(ConstantDescs.CD_Integer);
            composer.invokevirtual(ClassDesc.of("java.lang.Integer"), "intValue",
                    MethodTypeDesc.of(ConstantDescs.CD_int));
        } else if (valueType instanceof FloatDescriptor) {
            composer.checkcast(ConstantDescs.CD_Double);
            composer.invokevirtual(ClassDesc.of("java.lang.Double"), "doubleValue",
                    MethodTypeDesc.of(ConstantDescs.CD_double));
        } else if (valueType instanceof BooleanDescriptor) {
            composer.checkcast(ConstantDescs.CD_Boolean);
            composer.invokevirtual(ClassDesc.of("java.lang.Boolean"), "booleanValue",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean));
        } else {
            composer.checkcast(TypeDescriptor.toJavaClassDesc(targetType));
        }
        lastEmittedType = targetType;
    }

    private static boolean isConstantFieldValue(final TypeDescriptor type, final ConstantDesc value) {
        if (value == null) return false;
        return switch (TypeDescriptor.toJavaClassDesc(type).descriptorString()) {
            case "I", "Z", "D", "Ljava/lang/String;" -> true;
            default -> false;
        };
    }

    private void emitConstant(final CodeBuilder composer, final ConstantDesc value) {
        composer.loadConstant(value);
        lastEmittedType = ConstantFolder.typeForConstant(value);
    }

    private static MethodRefEntry getAutoboxingFor(final ConstantPoolBuilder cpb, final String type) {
        return switch (type) {
            case "I" -> cpb.methodRefEntry(Integer.class.describeConstable().orElseThrow(), "valueOf", MethodTypeDesc.ofDescriptor("(I)Ljava/lang/Integer;"));
            case "D" -> cpb.methodRefEntry(Double.class.describeConstable().orElseThrow(), "valueOf", MethodTypeDesc.ofDescriptor("(D)Ljava/lang/Double;"));
            case "B" -> cpb.methodRefEntry(Boolean.class.describeConstable().orElseThrow(), "valueOf", MethodTypeDesc.ofDescriptor("(Z)Ljava/lang/Boolean;"));
            default -> throw new IllegalArgumentException("Autoboxing not supported.");
        };
    }

    private static MethodTypeDesc emptyVoidMethod() {
        return MethodTypeDesc.ofDescriptor("()V");
    }

    private static MethodTypeDesc toJavaMethodDescriptor(FunctionDescriptor type) {
        final var returnType = TypeDescriptor.toJavaClassDesc(type.returnType());
        final var paramTypes = type.parameters().stream().map(TypeDescriptor::toJavaClassDesc).toList();
        return MethodTypeDesc.of(returnType, paramTypes);
    }

    private void beginScope() {
        symbols.beginScope();
    }

    private void endScope() {
        symbols.endScope();
    }

    private static void todo(final String message) {
        throw new UnsupportedOperationException(message);
    }
}
