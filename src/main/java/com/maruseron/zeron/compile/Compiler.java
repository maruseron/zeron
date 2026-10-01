package com.maruseron.zeron.compile;

import com.maruseron.zeron.UnitLiteral;
import com.maruseron.zeron.analize.Bind;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.domain.FloatDescriptor;
import com.maruseron.zeron.scan.Token;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.classfile.*;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.constantpool.ConstantPoolBuilder;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class Compiler {
    private static final Path OUTPUT_DIRECTORY = Paths.get("dist");

    private final ClassFile classFile = ClassFile.of();
    private final Resolver resolver = new Resolver();
    private final List<Stmt> declarations;
    private final Map<FunctionShapeKey, FunctionDescriptor> lambdaShapes = new LinkedHashMap<>();
    private final Map<String, FunctionShapeKey> lambdaShapeNames = new HashMap<>();
    private final List<Expr.Lambda> lambdaImplementations = new ArrayList<>();
    private final Map<Expr.Lambda, String> lambdaMethodNames = new IdentityHashMap<>();
    private final Map<Expr.Lambda, List<Token>> lambdaCaptures = new IdentityHashMap<>();
    private final String mainClassName;
    private SymbolTable symbols = null;
    private TypeDescriptor lastEmittedType = null;
    private TypeDescriptor currentReturnType = null;
    private FunctionModel currentFunction = null;
    private int localSlotOffset;

    public Compiler(List<Stmt> declarations) {
        this(declarations, "ZeronMain");
    }

    public Compiler(List<Stmt> declarations, String mainClassName) {
        this.declarations = declarations;
        this.mainClassName = mainClassName;
    }

    public void resolve() {
        resolver.resolve(declarations);
        symbols = resolver.symbols;
    }

    public void compile() throws IOException {
        Files.createDirectories(OUTPUT_DIRECTORY);
        collectLambdaShapes(declarations);
        collectLambdaCaptures();
        for (final var shape : lambdaShapes.entrySet()) {
            generateGeneratedLambdaClass(shape.getKey(), shape.getValue());
        }

        classFile.buildTo(
            OUTPUT_DIRECTORY.resolve(mainClassName + ".class").toAbsolutePath(),
                ClassDesc.of(mainClassName),
                cb -> generateClass(cb, declarations));
    }

    private void collectLambdaShapes(final List<Stmt> statements) {
        for (final var statement : statements) {
            collectLambdaShapes(statement);
        }
    }

    private void collectLambdaShapes(final Stmt statement) {
        switch (statement) {
            case Stmt.Function(Token _, List<Token> _, FunctionDescriptor type, List<Stmt> body) -> {
                for (final var parameter : type.parameters()) collectFunctionShapes(parameter);
                collectFunctionShapes(type.returnType());
                collectLambdaShapes(body);
            }
            case Stmt.Block(List<Stmt> statements) -> collectLambdaShapes(statements);
            case Stmt.If(Token _, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                collectLambdaShapes(condition);
                if (thenBranch != null) collectLambdaShapes(thenBranch);
                if (elseBranch != null) collectLambdaShapes(elseBranch);
            }
            case Stmt.Print(Expr expression) -> collectLambdaShapes(expression);
            case Stmt.Return(Expr value) -> { if (value != null) collectLambdaShapes(value); }
            case Stmt.Expression(Expr expression) -> collectLambdaShapes(expression);
            case Stmt.Var(Token _, TypeDescriptor type, Expr initializer, BindingMutability _) -> {
                collectFunctionShapes(type);
                if (initializer != null) collectLambdaShapes(initializer);
            }
            case Stmt.While(Token _, Expr condition, Stmt body) -> {
                collectLambdaShapes(condition);
                collectLambdaShapes(body);
            }
            case Stmt.For(Token _, Token _, Expr iterable, Stmt body) -> {
                collectLambdaShapes(iterable);
                collectLambdaShapes(body);
            }
            default -> {}
        }
    }

    private void collectFunctionShapes(final TypeDescriptor type) {
        switch (type) {
            case FunctionDescriptor function -> {
                final var shapeKey = FunctionShapeKey.of(function);
                final var interfaceName = FunctionShapeNames.interfaceName(shapeKey);
                final var previousKey = lambdaShapeNames.putIfAbsent(interfaceName, shapeKey);
                if (previousKey != null && !previousKey.equals(shapeKey)) {
                    throw new IllegalStateException("Function shape digest collision for " + interfaceName);
                }
                lambdaShapes.putIfAbsent(shapeKey, function);
                for (final var parameter : function.parameters()) collectFunctionShapes(parameter);
                collectFunctionShapes(function.returnType());
            }
            case ArrayDescriptor array -> collectFunctionShapes(array.elementType());
            case NullableDescriptor nullable -> collectFunctionShapes(nullable.baseType());
            case ReferenceDescriptor reference -> collectFunctionShapes(reference.baseType());
            case GenericDescriptor generic -> {
                for (final var parameter : generic.typeParameters()) collectFunctionShapes(parameter);
            }
            default -> {}
        }
    }

    private void collectLambdaShapes(final Expr expr) {
        if (expr != null) collectFunctionShapes(expr.getType());
        switch (expr) {
            case Expr.ArrayLiteral literal -> {
                for (final var element : literal.elements) collectLambdaShapes(element);
            }
            case Expr.ArrayLength length -> collectLambdaShapes(length.array);
            case Expr.Index index -> {
                collectLambdaShapes(index.array);
                collectLambdaShapes(index.index);
            }
            case Expr.IndexAssignment assignment -> {
                collectLambdaShapes(assignment.array);
                collectLambdaShapes(assignment.index);
                collectLambdaShapes(assignment.value);
            }
            case Expr.Lambda lambda -> {
                if (lambda.getType() instanceof FunctionDescriptor functionType) {
                    collectFunctionShapes(functionType);
                    if (!lambdaMethodNames.containsKey(lambda)) {
                        lambdaMethodNames.put(lambda, "$lambda$" + lambdaMethodNames.size());
                        lambdaImplementations.add(lambda);
                    }
                    collectLambdaShapes(lambda.body);
                }
            }
            case Expr.Binary binary -> {
                collectLambdaShapes(binary.left);
                collectLambdaShapes(binary.right);
            }
            case Expr.Call call -> {
                for (final var argument : call.arguments) collectLambdaShapes(argument);
            }
            case Expr.Grouping grouping -> collectLambdaShapes(grouping.expression);
            case Expr.If iff -> {
                collectLambdaShapes(iff.condition);
                collectLambdaShapes(iff.thenExpr);
                collectLambdaShapes(iff.elseExpr);
            }
            case Expr.Assignment assignment -> collectLambdaShapes(assignment.value);
            case Expr.Unary unary -> collectLambdaShapes(unary.right);
            case Expr.Variable _ -> {}
            case Expr.Literal _ -> {}
            case null -> {}
            default -> {}
        }
    }

    private void generateGeneratedLambdaClass(final FunctionShapeKey shapeKey,
                                              final FunctionDescriptor type) throws IOException {
        final var className = FunctionShapeNames.interfaceName(shapeKey);
        final var classDesc = ClassDesc.of(className);
        final var outFile = OUTPUT_DIRECTORY.resolve(className.replace('.', '/') + ".class");
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

    public void generateClass(final ClassBuilder classBuilder, final List<Stmt> declarations) {
        record Initializer(Token name, TypeDescriptor type, Expr initializer) {}

        var hasMain = false;
        final var initializers = new ArrayList<Initializer>();
        for (final var declaration : declarations) {
            switch (declaration) {
                case Stmt.Var(Token name, _, Expr initializer, BindingMutability mutability) -> {
                    final var type = symbols.getSymbol(name).type();
                    final var foldedValue = tryFold(initializer);
                    final ConstantDesc value = isConstantFieldValue(type, foldedValue)
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
                case Stmt.Function(Token name, List<Token> parameters,
                                   FunctionDescriptor typeDescriptor, List<Stmt> body) -> {
                    if (name.lexeme().equals("main")) hasMain = true;
                    classBuilder.withMethodBody(
                            name.lexeme(),
                            toJavaMethodDescriptor((FunctionDescriptor)symbols.getFunction(name).type()),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL,
                            composer -> {
                                beginScope();
                                final var previousReturnType = currentReturnType;
                                currentReturnType = symbols.getFunctionType(name).returnType();
                                final var paramTypes = typeDescriptor.parameters();
                                for (int i = 0; i < parameters.size(); i++) {
                                    symbols.declareSymbol(declaration, parameters.get(i), paramTypes.get(i), BindingMutability.IMMUTABLE);
                                    symbols.define(parameters.get(i));
                                }
                                currentFunction = new FunctionModel(name.lexeme(), symbols.getFunctionType(name));
                                composer.transforming(
                                        (builder, element) -> {
                                            if (element instanceof Instruction i) {
                                                currentFunction.add(i, i.opcode().kind(), null, null, null);
                                            }
                                            builder.with(element);
                                        },
                                        builder -> emitStmts(builder, body));
                                if (symbols.getFunctionType(name).returnType() instanceof UnitDescriptor) {
                                    composer.aconst_null();
                                    composer.areturn();
                                }
                                endScope();
                                currentReturnType = previousReturnType;
                                System.out.println(currentFunction);
                            });
                }
                default -> {}
            }
        }

        if (hasMain) {
            classBuilder.withMethodBody(
                    "main",
                    emptyVoidMethod(),
                    ClassFile.ACC_STATIC,
                    composer -> {
                        composer.invokestatic(composer.constantPool().methodRefEntry(
                                ClassDesc.of(mainClassName),
                                "main",
                                MethodTypeDesc.of(ConstantDescs.CD_Void)));
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
                            composer.putstatic(ClassDesc.of(mainClassName), name.lexeme(), TypeDescriptor.toJavaClassDesc(type));
                        }
                        composer.return_();
                    });
        }

                for (final var lambda : lambdaImplementations) {
                    final var functionType = (FunctionDescriptor) lambda.getType();
                    final var implementationParameters = new ArrayList<TypeDescriptor>();
                    for (final var capture : lambdaCaptures.getOrDefault(lambda, List.of())) {
                        implementationParameters.add(lookupCapture(capture).type());
                    }
                    implementationParameters.addAll(functionType.parameters());
                    classBuilder.withMethodBody(
                        lambdaMethodNames.get(lambda),
                        toJavaMethodDescriptor(functionType, implementationParameters),
                        ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        composer -> emitLambdaImplementation(composer, lambda));
                }
    }

    public void emitStmts(final CodeBuilder builder, final List<Stmt> statements) {
        for (final var statement : statements) {
            emitStmt(builder, statement);
        }
    }

    public void emitStmt(final CodeBuilder composer, final Stmt statement) {
        switch (statement) {
            case Stmt.Block(List<Stmt> statements) -> {
                beginScope();
                emitStmts(composer, statements);
                endScope();
            }
            case Stmt.If(Token paren, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                final var value = (Integer) tryFold(condition);
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
            case Stmt.Print(Expr expression) -> {
                composer.getstatic(getStdOut(composer.constantPool()));
                emitExpr(composer, expression);
                emitBox(composer, lastEmittedType);
                composer.invokevirtual(getPrintln(composer.constantPool()));
            }
            case Stmt.Expression(Expr expression) -> {
                emitExpr(composer, expression);
                emitPop(composer, lastEmittedType);
            }
            case Stmt.Return(Expr expression) -> {
                if (expression == null) {
                    composer.aconst_null();
                    lastEmittedType = TypeDescriptor.ofUnit();
                } else {
                    emitExpr(composer, expression);
                }
                emitConversion(composer, lastEmittedType, currentReturnType);
                composer.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(currentReturnType).descriptorString()));
            }
            case Stmt.Var(Token name, TypeDescriptor type, Expr initializer, BindingMutability mutability) -> {
                if (initializer != null) {
                    final ConstantDesc value = tryFold(initializer);
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

    private void emitExpr(final CodeBuilder composer, final Expr expr) {
        switch (expr) {
            case Expr.ArrayLiteral literal -> {
                composer.ldc(literal.elements.size());
                composer.anewarray(ClassDesc.of("java.lang.Object"));
                for (int i = 0; i < literal.elements.size(); i++) {
                    composer.dup();
                    composer.ldc(i);
                    final var element = literal.elements.get(i);
                    emitExpr(composer, element);
                    emitBox(composer, lastEmittedType);
                    composer.aastore();
                }
                lastEmittedType = literal.getType();
            }
            case Expr.ArrayLength length -> {
                emitExpr(composer, length.array);
                composer.arraylength();
                lastEmittedType = TypeDescriptor.ofInt();
            }
            case Expr.Index index -> {
                emitExpr(composer, index.array);
                composer.dup();
                composer.arraylength();
                emitExpr(composer, index.index);
                emitArrayBoundsCheck(composer);
                composer.aaload();
                emitArrayReadConversion(composer, index.getType());
            }
            case Expr.IndexAssignment assignment -> {
                emitExpr(composer, assignment.array);
                composer.dup();
                composer.arraylength();
                emitExpr(composer, assignment.index);
                emitArrayBoundsCheck(composer);
                emitExpr(composer, assignment.value);
                final var arrayType = (ArrayDescriptor)((ReferenceDescriptor)assignment.array.getType()).baseType();
                emitConversion(composer, lastEmittedType, arrayType.elementType());
                emitBox(composer, lastEmittedType);
                composer.aastore();
                composer.aconst_null();
                lastEmittedType = TypeDescriptor.ofUnit();
            }
            case Expr.Assignment assignment -> {
                final var binding = symbols.getSymbol(assignment.name);
                emitExpr(composer, assignment.value);
                emitConversion(composer, lastEmittedType, binding.type());
                duplicateValue(composer, binding.type());
                if (binding.lvt() == SymbolTable.GLOBAL) {
                    composer.putstatic(ClassDesc.of(mainClassName), assignment.name.lexeme(),
                            TypeDescriptor.toJavaClassDesc(binding.type()));
                } else {
                    composer.storeLocal(
                            TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(binding.type()).descriptorString()),
                            binding.lvt() + localSlotOffset);
                }
                lastEmittedType = binding.type();
            }
            case Expr.Binary binary -> {
                switch (TypeDescriptor.toJavaClassDesc(binary.getType()).descriptorString()) {
                    case "I" -> {
                        emitExpr(composer, binary.left);
                        emitExpr(composer, binary.right);
                        switch (binary.operator.type()) {
                            case PLUS -> composer.iadd();
                            case MINUS -> composer.isub();
                            case STAR -> composer.imul();
                            case SLASH -> composer.idiv();
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
            case Expr.Call call -> emitCall(composer, call);
            case Expr.Lambda lambda -> {
                final var functionType = (FunctionDescriptor) lambda.getType();
                final var captures = lambdaCaptures.getOrDefault(lambda, List.of());
                final var captureTypes = captures.stream()
                        .map(this::lookupCapture)
                        .map(Bind::type)
                        .toList();
                final var implementationParameters = new ArrayList<>(captureTypes);
                implementationParameters.addAll(functionType.parameters());
                for (int i = 0; i < captures.size(); i++) {
                    emitVariable(composer, captures.get(i));
                    emitConversion(composer, lastEmittedType, captureTypes.get(i));
                }

                final var generatedInterface = ClassDesc.of(FunctionShapeNames.interfaceName(functionType));
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
                    lambdaMethodNames.get(lambda),
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
                        composer.aconst_null();
                        lastEmittedType = TypeDescriptor.ofUnit();
                    }
                    case null -> {
                        composer.aconst_null();
                        lastEmittedType = TypeDescriptor.ofNull();
                    }
                    default -> throw new IllegalStateException("Unsupported value");
                }
            }
            case Expr.Unary unary -> todo("unary: implement resolver");
            case Expr.Variable variable -> emitVariable(composer, variable.name);
            default -> throw new UnsupportedOperationException();
        }
    }

    private void emitCall(final CodeBuilder composer, final Expr.Call call) {
        if (!symbols.containsSymbol(call.callee)) {
            final var functionType = (FunctionDescriptor) symbols.getFunction(call.callee).type();
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(composer, call.arguments.get(i));
                emitConversion(composer, lastEmittedType, functionType.parameters().get(i));
            }
            composer.invokestatic(ClassDesc.of(mainClassName), call.callee.lexeme(), toJavaMethodDescriptor(functionType));
            lastEmittedType = functionType.returnType();
            return;
        }

        final var binding = symbols.getSymbol(call.callee);
        if (!(binding.type() instanceof FunctionDescriptor functionType)) {
            throw new IllegalStateException("Resolved call target is not a function");
        }

        emitVariable(composer, call.callee);
        for (int i = 0; i < call.arguments.size(); i++) {
            emitExpr(composer, call.arguments.get(i));
            emitConversion(composer, lastEmittedType, functionType.parameters().get(i));
        }
        composer.invokeinterface(TypeDescriptor.toJavaClassDesc(functionType), "invoke", toJavaMethodDescriptor(functionType));
        lastEmittedType = functionType.returnType();
    }

    private void emitLambdaImplementation(final CodeBuilder composer, final Expr.Lambda lambda) {
        if (!(lambda.getType() instanceof FunctionDescriptor functionType)) {
            throw new IllegalStateException("Lambda has no resolved function type");
        }

        final var captures = lambdaCaptures.getOrDefault(lambda, List.of());
        final var allParameters = new ArrayList<TypeDescriptor>();
        for (final var capture : captures) {
            allParameters.add(lookupCapture(capture).type());
        }
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
            for (final var capture : captures) {
                final var captureType = lookupCapture(capture).type();
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
                    composer.aconst_null();
                    lastEmittedType = TypeDescriptor.ofUnit();
                } else {
                    emitExpr(composer, ret.value());
                }
                emitConversion(composer, lastEmittedType, functionType.returnType());
                composer.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(functionType.returnType()).descriptorString()));
            } else {
                emitStmts(composer, lambda.body);
                if (functionType.returnType() instanceof UnitDescriptor) {
                    composer.aconst_null();
                }
                composer.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(functionType.returnType()).descriptorString()));
            }
        } finally {
            endScope();
            localSlotOffset = previousOffset;
            currentReturnType = previousReturnType;
        }
    }

    private void collectLambdaCaptures() {
        for (final var lambda : lambdaImplementations) {
            final var captured = collectCapturedVariables(lambda);
            lambdaCaptures.put(lambda, captured);
        }
    }

    private List<Token> collectCapturedVariables(final Expr.Lambda lambda) {
        final var captured = new ArrayList<Token>();
        final var seen = new HashSet<String>();
        final var locals = new HashSet<String>();
        for (final var param : lambda.params) {
            locals.add(param.lexeme());
        }
        collectCapturedVariables(lambda.body, locals, captured, seen);
        return captured;
    }

    private void collectCapturedVariables(final List<Stmt> statements,
                                         final Set<String> locals,
                                         final List<Token> captured,
                                         final Set<String> seen) {
        for (final var statement : statements) {
            collectCapturedVariables(statement, locals, captured, seen);
        }
    }

    private void collectCapturedVariables(final Stmt statement,
                                         final Set<String> localNames,
                                         final List<Token> captured,
                                         final Set<String> seen) {
        switch (statement) {
            case Stmt.Block(List<Stmt> block) -> {
                final var nestedNames = new HashSet<>(localNames);
                collectCapturedVariables(block, nestedNames, captured, seen);
            }
            case Stmt.If(Token _, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                collectCapturedVariables(condition, localNames, captured, seen);
                if (thenBranch != null) collectCapturedVariables(thenBranch, new HashSet<>(localNames), captured, seen);
                if (elseBranch != null) collectCapturedVariables(elseBranch, new HashSet<>(localNames), captured, seen);
            }
            case Stmt.For(Token iterationBind, Token _, Expr iterable, Stmt body) -> {
                final var nestedNames = new HashSet<>(localNames);
                nestedNames.add(iterationBind.lexeme());
                collectCapturedVariables(iterable, nestedNames, captured, seen);
                collectCapturedVariables(body, nestedNames, captured, seen);
            }
            case Stmt.Print(Expr expression) -> collectCapturedVariables(expression, localNames, captured, seen);
            case Stmt.Return(Expr value) -> {
                if (value != null) collectCapturedVariables(value, localNames, captured, seen);
            }
            case Stmt.Expression(Expr expression) -> collectCapturedVariables(expression, localNames, captured, seen);
            case Stmt.Var(Token name, TypeDescriptor _, Expr initializer, BindingMutability _) -> {
                final var nestedNames = new HashSet<>(localNames);
                nestedNames.add(name.lexeme());
                if (initializer != null) collectCapturedVariables(initializer, nestedNames, captured, seen);
            }
            case Stmt.While(Token _, Expr condition, Stmt body) -> {
                collectCapturedVariables(condition, localNames, captured, seen);
                collectCapturedVariables(body, new HashSet<>(localNames), captured, seen);
            }
            case Stmt.Function(Token _, List<Token> parameters, FunctionDescriptor _, List<Stmt> body) -> {
                final var nestedNames = new HashSet<>(localNames);
                for (final var param : parameters) nestedNames.add(param.lexeme());
                collectCapturedVariables(body, nestedNames, captured, seen);
            }
            default -> {}
        }
    }

    private void collectCapturedVariables(final Expr expr,
                                         final Set<String> localNames,
                                         final List<Token> captured,
                                         final Set<String> seen) {
        if (expr == null) return;
        switch (expr) {
            case Expr.ArrayLiteral literal -> {
                for (final var element : literal.elements) collectCapturedVariables(element, localNames, captured, seen);
            }
            case Expr.ArrayLength length -> collectCapturedVariables(length.array, localNames, captured, seen);
            case Expr.Index index -> {
                collectCapturedVariables(index.array, localNames, captured, seen);
                collectCapturedVariables(index.index, localNames, captured, seen);
            }
            case Expr.IndexAssignment assignment -> {
                collectCapturedVariables(assignment.array, localNames, captured, seen);
                collectCapturedVariables(assignment.index, localNames, captured, seen);
                collectCapturedVariables(assignment.value, localNames, captured, seen);
            }
            case Expr.Assignment assignment -> collectCapturedVariables(assignment.value, localNames, captured, seen);
            case Expr.Binary binary -> {
                collectCapturedVariables(binary.left, localNames, captured, seen);
                collectCapturedVariables(binary.right, localNames, captured, seen);
            }
            case Expr.Call call -> {
                for (final var argument : call.arguments) {
                    collectCapturedVariables(argument, localNames, captured, seen);
                }
            }
            case Expr.Grouping grouping -> collectCapturedVariables(grouping.expression, localNames, captured, seen);
            case Expr.If iff -> {
                collectCapturedVariables(iff.condition, localNames, captured, seen);
                collectCapturedVariables(iff.thenExpr, localNames, captured, seen);
                collectCapturedVariables(iff.elseExpr, localNames, captured, seen);
            }
            case Expr.Lambda lambda -> {
                final var nestedNames = new HashSet<>(localNames);
                for (final var param : lambda.params) nestedNames.add(param.lexeme());
                collectCapturedVariables(lambda.body, nestedNames, captured, seen);
            }
            case Expr.Literal _ -> {}
            case Expr.Logical logical -> {
                collectCapturedVariables(logical.left, localNames, captured, seen);
                collectCapturedVariables(logical.right, localNames, captured, seen);
            }
            case Expr.Unary unary -> collectCapturedVariables(unary.right, localNames, captured, seen);
            case Expr.Variable variable -> {
                final var name = variable.name.lexeme();
                if (localNames.contains(name)) return;
                if ((symbols.containsSymbol(variable.name) || symbols.containsAnySymbol(variable.name))
                    && lookupCapture(variable.name).lvt() != SymbolTable.GLOBAL
                    && !seen.contains(name)) {
                    seen.add(name);
                    captured.add(variable.name);
                }
            }
            default -> {}
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
                    composer.getstatic(ClassDesc.of(mainClassName), name.lexeme(), TypeDescriptor.toJavaClassDesc(binding.type()));
                    lastEmittedType = binding.type();
                    return;
                }
            }
            System.out.println("emitVariable missing symbol: " + name.lexeme() + " locals=" + symbols.getLocals());
            throw new IllegalStateException("Unknown symbol: " + name.lexeme());
        }
        final var binding = symbols.getSymbol(name);
        if (binding.type() instanceof FunctionDescriptor) {
            composer.aload(binding.lvt());
            lastEmittedType = binding.type();
            return;
        }
        if (binding.lvt() == SymbolTable.GLOBAL) {
            composer.getstatic(ClassDesc.of(mainClassName), name.lexeme(), TypeDescriptor.toJavaClassDesc(binding.type()));
        } else {
            switch (TypeDescriptor.toJavaClassDesc(binding.type()).descriptorString()) {
                case "I", "Z" -> composer.iload(binding.lvt() + localSlotOffset);
                case "D" -> composer.dload(binding.lvt() + localSlotOffset);
                default -> composer.aload(binding.lvt() + localSlotOffset);
            }
        }
        lastEmittedType = binding.type();
    }

    private Bind lookupCapture(final Token name) {
        return symbols.containsSymbol(name) ? symbols.getSymbol(name) : symbols.getAnySymbol(name);
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
        if (sourceType.equals(targetType) || sourceType instanceof NullDescriptor && targetType.isNullable()) {
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
        throw new IllegalStateException("Unsupported conversion from " + sourceType + " to " + targetType);
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
        lastEmittedType = getTypeForConstant(value);
    }

    private ConstantDesc tryFold(final Expr expr) {
        return switch (expr) {
            case Expr.Literal lit -> {
                if (lit.value instanceof Boolean b) yield b ? 1 : 0;
                if (lit.value instanceof Constable constable) yield constable.describeConstable().orElseThrow();
                else yield null;
            }
            case Expr.Binary bin -> {
                final var left = tryFold(bin.left);
                final var right = tryFold(bin.right);
                if (left == null || right == null) yield null;
                if (left.getClass() != right.getClass()) yield null;
                switch (left) {
                    case Integer li -> {
                        yield switch (bin.operator.type()) {
                            case PLUS -> li + (Integer) right;
                            case MINUS -> li - (Integer) right;
                            case STAR -> li * (Integer) right;
                            case SLASH -> li / (Integer) right;
                            default -> throw new IllegalStateException();
                        };
                    }
                    case Double ld -> {
                        yield switch (bin.operator.type()) {
                            case PLUS -> ld + (Double) right;
                            case MINUS -> ld - (Double) right;
                            case STAR -> ld * (Double) right;
                            case SLASH -> ld / (Double) right;
                            default -> throw new IllegalStateException();
                        };
                    }
                    case String ls -> {
                        yield switch (bin.operator.type()) {
                            case PLUS -> ls + right;
                            default -> throw new IllegalStateException();
                        };
                    }
                    default -> {}
                }
                throw new IllegalStateException();
            }
            case null, default -> null;
        };
    }

    private static MethodRefEntry getAutoboxingFor(final ConstantPoolBuilder cpb, final String type) {
        return switch (type) {
            case "I" -> cpb.methodRefEntry(Integer.class.describeConstable().orElseThrow(), "valueOf", MethodTypeDesc.ofDescriptor("(I)Ljava/lang/Integer;"));
            case "D" -> cpb.methodRefEntry(Double.class.describeConstable().orElseThrow(), "valueOf", MethodTypeDesc.ofDescriptor("(D)Ljava/lang/Double;"));
            case "B" -> cpb.methodRefEntry(Boolean.class.describeConstable().orElseThrow(), "valueOf", MethodTypeDesc.ofDescriptor("(Z)Ljava/lang/Boolean;"));
            default -> throw new IllegalArgumentException("Autoboxing not supported.");
        };
    }

    private static FieldRefEntry getStdOut(final ConstantPoolBuilder cpb) {
        return cpb.fieldRefEntry(ClassDesc.ofDescriptor(System.class.descriptorString()), "out", ClassDesc.ofDescriptor("Ljava/io/PrintStream;"));
    }

    private static MethodRefEntry getPrintln(final ConstantPoolBuilder cpb) {
        return cpb.methodRefEntry(ClassDesc.ofDescriptor(PrintStream.class.descriptorString()), "println", MethodType.methodType(void.class, Object.class).describeConstable().orElseThrow());
    }

    private static MethodTypeDesc emptyVoidMethod() {
        return MethodTypeDesc.ofDescriptor("()V");
    }

    private static TypeDescriptor getTypeForConstant(final ConstantDesc value) {
        return switch (value) {
            case Integer _ -> TypeDescriptor.ofInt();
            case Double _ -> TypeDescriptor.ofFloat();
            case String _ -> TypeDescriptor.ofString();
            default -> throw new IllegalStateException();
        };
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
