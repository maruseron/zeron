package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ContractComposition;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.FunctionShapeKey;
import com.maruseron.zeron.domain.FunctionShapeNames;
import com.maruseron.zeron.domain.NeverDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeSubstitution;
import com.maruseron.zeron.domain.ZeronLibraryIndex;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RuntimeSupportEmitter {
    private static final ClassDesc UNIT_CLASS = ClassDesc.of("zeron.lang.Unit");
    private static final ClassDesc RAISED_EFFECT_CLASS = ClassDesc.of("zeron.runtime.RaisedEffect");
    private static final ClassDesc RESOURCE_SUPPORT_CLASS = ClassDesc.of("zeron.runtime.ResourceSupport");
    private static final ClassDesc EQ_SUPPORT_CLASS = ClassDesc.of("zeron.runtime.EqSupport");
    private static final ClassDesc HASH_SUPPORT_CLASS = ClassDesc.of("zeron.runtime.HashSupport");
    private static final ClassDesc RUNTIME_EXCEPTION_CLASS = ClassDesc.of("java.lang.RuntimeException");
    private static final ClassDesc THROWABLE_CLASS = ClassDesc.of("java.lang.Throwable");
    private static final ClassDesc ARRAY_LIST_CLASS = ClassDesc.of("java.util.ArrayList");
    private static final ClassDesc DISPOSABLE_CLASS = ClassDesc.of("zeron.resource.Disposable");
    private static final ClassDesc ARRAY_ITERABLE_CLASS = ClassDesc.of("zeron.runtime.ArrayIterable");
    private static final ClassDesc ITERABLE_CLASS = ClassDesc.of("zeron.collections.Iterable");
    private static final ClassDesc ARRAY_ITERATOR_CLASS = ClassDesc.of("zeron.collections.ArrayIterator");
    private static final ClassDesc ITERATOR_CLASS = ClassDesc.of("zeron.collections.Iterator");
    private static final ClassDesc OBJECT_ARRAY_CLASS = ConstantDescs.CD_Object.arrayType();

    private RuntimeSupportEmitter() {}

    static void emitUnitClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "lang", "Unit.class"));
        Files.createDirectories(output.getParent());
        ClassFile.of().buildTo(output, UNIT_CLASS, builder -> {
            builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withField("INSTANCE", UNIT_CLASS,
                    field -> field.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL));
            builder.withMethodBody("<init>", emptyVoidMethod(), ClassFile.ACC_PRIVATE, code -> {
                code.aload(0);
                code.invokespecial(ConstantDescs.CD_Object, "<init>", emptyVoidMethod());
                code.return_();
            });
            builder.withMethodBody("<clinit>", emptyVoidMethod(), ClassFile.ACC_STATIC, code -> {
                code.new_(UNIT_CLASS);
                code.dup();
                code.invokespecial(UNIT_CLASS, "<init>", emptyVoidMethod());
                code.putstatic(UNIT_CLASS, "INSTANCE", UNIT_CLASS);
                code.return_();
            });
            builder.withMethodBody("toString", MethodTypeDesc.of(ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, code -> {
                        code.ldc("Unit");
                        code.areturn();
                    });
        });
    }

    static void emitArrayIterableSupportClass(final CompilationContext context) throws IOException {
        final var iterable = context.resolution.contracts().get("zeron.collections.Iterable");
        if (iterable == null) return;
        final var output = context.outputDirectory.resolve(
                Path.of("zeron", "runtime", "ArrayIterable.class"));
        Files.createDirectories(output.getParent());
        final var constructorType = MethodTypeDesc.of(ConstantDescs.CD_void, OBJECT_ARRAY_CLASS);
        final var wrapType = MethodTypeDesc.of(ITERABLE_CLASS, OBJECT_ARRAY_CLASS);
        final var iteratorType = MethodTypeDesc.of(ITERATOR_CLASS);
        final var arrayIteratorType = MethodTypeDesc.of(ARRAY_ITERATOR_CLASS, OBJECT_ARRAY_CLASS);
        final var iterableBoundTypes = arrayIterableBoundTypes(context);
        ensureArrayIterableFunctionShapes(context, iterableBoundTypes);
        final var emittedMethods = new java.util.HashSet<String>();
        ClassFile.of().buildTo(output, ARRAY_ITERABLE_CLASS, builder -> {
            builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC);
            builder.withInterfaceSymbols(List.of(ITERABLE_CLASS));
            builder.withField("values", OBJECT_ARRAY_CLASS,
                    field -> field.withFlags(ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL));
            builder.withMethodBody("<init>", constructorType, ClassFile.ACC_PUBLIC, code -> {
                code.aload(0);
                code.invokespecial(ConstantDescs.CD_Object, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void));
                code.aload(0);
                code.aload(1);
                code.putfield(ARRAY_ITERABLE_CLASS, "values", OBJECT_ARRAY_CLASS);
                code.return_();
            });
            builder.withMethodBody("wrap", wrapType,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, code -> {
                        code.new_(ARRAY_ITERABLE_CLASS);
                        code.dup();
                        code.aload(0);
                        code.invokespecial(ARRAY_ITERABLE_CLASS, "<init>", constructorType);
                        code.areturn();
                    });
            builder.withMethodBody("iterator", iteratorType, ClassFile.ACC_PUBLIC, code -> {
                code.aload(0);
                code.getfield(ARRAY_ITERABLE_CLASS, "values", OBJECT_ARRAY_CLASS);
                code.invokestatic(ARRAY_ITERATOR_CLASS, "from", arrayIteratorType);
                code.areturn();
            });
            for (final var iterableType : iterableBoundTypes) {
                final var iterableUse = ContractComposition.fromType(iterableType);
                final var iterableGeneric = (com.maruseron.zeron.domain.GenericDescriptor) iterableType;
                for (final var slot : ContractComposition.methods(
                        context.resolution.contracts(), iterableUse)) {
                    final var method = slot.method();
                    if (method.isStatic()) continue;
                    final var substituted = (FunctionDescriptor) TypeSubstitution.substitute(
                            method.typeDescriptor(), slot.applied().substitutions());
                    final var erased = (FunctionDescriptor) TypeSubstitution.erase(substituted);
                    final var methodType = BytecodeEmitter.toJavaMethodDescriptor(erased);
                    final var bridgeParameters = new ArrayList<ClassDesc>();
                    bridgeParameters.add(OBJECT_ARRAY_CLASS);
                    bridgeParameters.addAll(methodType.parameterList());
                    final var bridgeType = MethodTypeDesc.of(methodType.returnType(),
                            bridgeParameters.toArray(ClassDesc[]::new));
                    final var bridgeKey = method.name().lexeme() + bridgeType.descriptorString();
                    if (!emittedMethods.add(bridgeKey)) continue;
                    builder.withMethodBody("$bound$" + method.name().lexeme(), bridgeType,
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, code -> {
                                if (method.name().lexeme().equals("forEach")
                                        && substituted.parameters().size() == 1
                                        && substituted.parameters().getFirst() instanceof FunctionDescriptor callback) {
                                    emitArrayForEachBridge(context, code,
                                            iterableGeneric.typeParameters().getFirst(), callback);
                                    return;
                                }
                                code.new_(ARRAY_ITERABLE_CLASS);
                                code.dup();
                                code.aload(0);
                                code.invokespecial(ARRAY_ITERABLE_CLASS, "<init>", constructorType);
                                var slotIndex = 1;
                                for (final var parameter : methodType.parameterList()) {
                                    final var kind = TypeKind.fromDescriptor(parameter.descriptorString());
                                    code.loadLocal(kind, slotIndex);
                                    slotIndex += kind == TypeKind.LONG || kind == TypeKind.DOUBLE ? 2 : 1;
                                }
                                code.invokeinterface(ITERABLE_CLASS, method.name().lexeme(), methodType);
                                code.return_(TypeKind.fromDescriptor(methodType.returnType().descriptorString()));
                            });
                }
            }
        });
    }

    private static void emitArrayForEachBridge(final CompilationContext context,
                                               final java.lang.classfile.CodeBuilder code,
                                               final TypeDescriptor elementType,
                                               final FunctionDescriptor callback) {
        final var loop = code.newLabel();
        final var end = code.newLabel();
        code.iconst_0();
        code.istore(2);
        code.labelBinding(loop);
        code.iload(2);
        code.aload(0);
        code.arraylength();
        code.if_icmpge(end);
        code.aload(1);
        code.aload(0);
        code.iload(2);
        code.aaload();
        BytecodeEmitter.emitConversion(context, code, TypeDescriptor.ofAny(), elementType);
        final var erasedCallback = (FunctionDescriptor) TypeSubstitution.erase(callback);
        code.invokeinterface(TypeDescriptor.toJavaClassDesc(erasedCallback), "invoke",
                BytecodeEmitter.toJavaMethodDescriptor(erasedCallback));
        code.pop();
        code.iload(2);
        code.iconst_1();
        code.iadd();
        code.istore(2);
        code.goto_(loop);
        code.labelBinding(end);
        code.getstatic(UNIT_CLASS, "INSTANCE", UNIT_CLASS);
        code.areturn();
    }

    private static Set<TypeDescriptor> arrayIterableBoundTypes(final CompilationContext context) {
        final var result = new LinkedHashSet<TypeDescriptor>();
        for (final var declaration : context.topLevelDeclarations) {
            if (declaration instanceof Stmt.Function function) {
                addIterableBounds(function.typeDescriptor().typeParameters(), result);
            } else if (declaration instanceof Stmt.ClassDecl classDeclaration) {
                addIterableBounds(classDeclaration.typeParameters(), result);
                for (final var method : classDeclaration.methods()) {
                    addIterableBounds(method.typeDescriptor().typeParameters(), result);
                }
            } else if (declaration instanceof Stmt.ContractDecl contract) {
                addIterableBounds(contract.typeParameters(), result);
                for (final var method : contract.methods()) {
                    addIterableBounds(method.typeDescriptor().typeParameters(), result);
                }
            } else if (declaration instanceof Stmt.ExtensionMethod extension) {
                addIterableBounds(extension.receiverTypeParameters(), result);
                addIterableBounds(extension.methodTypeParameters(), result);
            }
        }
        for (final var library : context.libraries) {
            for (final var declaration : library.declarations()) {
                if (declaration instanceof ZeronLibraryIndex.FunctionExport function) {
                    addIterableBounds(function.signature().typeParameters(), result);
                } else if (declaration instanceof ZeronLibraryIndex.ExtensionExport extension) {
                    addIterableBounds(extension.signature().typeParameters(), result);
                } else if (declaration instanceof ZeronLibraryIndex.ClassExport classExport) {
                    addIterableBounds(classExport.typeParameters(), result);
                    for (final var method : classExport.methods()) {
                        addIterableBounds(method.signature().typeParameters(), result);
                    }
                    for (final var constructor : classExport.namedConstructors()) {
                        addIterableBounds(constructor.signature().typeParameters(), result);
                    }
                } else if (declaration instanceof ZeronLibraryIndex.ContractExport contract) {
                    addIterableBounds(contract.typeParameters(), result);
                    for (final var method : contract.methods()) {
                        addIterableBounds(method.signature().typeParameters(), result);
                    }
                }
            }
        }
        return Set.copyOf(result);
    }

    private static void ensureArrayIterableFunctionShapes(final CompilationContext context,
                                                          final Set<TypeDescriptor> iterableTypes)
            throws IOException {
        for (final var iterableType : iterableTypes) {
            final var iterableUse = ContractComposition.fromType(iterableType);
            for (final var slot : ContractComposition.methods(context.resolution.contracts(), iterableUse)) {
                final var substituted = (FunctionDescriptor) TypeSubstitution.substitute(
                        slot.method().typeDescriptor(), slot.applied().substitutions());
                if (!slot.method().name().lexeme().equals("forEach")
                        || substituted.parameters().size() != 1
                        || !(substituted.parameters().getFirst() instanceof FunctionDescriptor callback)) {
                    continue;
                }
                final var erasedCallback = (FunctionDescriptor) TypeSubstitution.erase(callback);
                final var key = FunctionShapeKey.of(erasedCallback);
                if (context.lambdaPlan.functionShapes().containsKey(key)) continue;
                context.registerGeneratedInterface(FunctionShapeNames.interfaceName(key));
                emitFunctionShape(context.outputDirectory, key, erasedCallback);
            }
        }
    }

    private static void addIterableBounds(final List<com.maruseron.zeron.domain.TypeParameterDescriptor> parameters,
                                         final Set<TypeDescriptor> target) {
        for (final var parameter : parameters) {
            for (final var bound : parameter.bounds()) {
                final var underlying = bound instanceof com.maruseron.zeron.domain.ReferenceDescriptor reference
                        ? reference.baseType() : bound;
                if (underlying instanceof com.maruseron.zeron.domain.GenericDescriptor generic
                        && generic.baseType().name().equals("zeron.collections.Iterable")
                        && generic.typeParameters().size() == 1) {
                    target.add(TypeDescriptor.genericOf(generic.baseType(),
                            generic.typeParameters().stream().map(TypeSubstitution::erase).toList()));
                }
            }
        }
    }

    static void emitLambdaTemplateSupportClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "runtime", "LambdaTemplateSupport.class"));
        Files.createDirectories(output.getParent());
        try (final var classBytes = RuntimeSupportEmitter.class.getResourceAsStream(
                "/zeron/runtime/LambdaTemplateSupport.class")) {
            if (classBytes == null) {
                throw new IOException("Missing compiled LambdaTemplateSupport runtime class.");
            }
            Files.copy(classBytes, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void emitStringUnicodeSupportClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "runtime", "StringUnicode.class"));
        Files.createDirectories(output.getParent());
        try (final var classBytes = RuntimeSupportEmitter.class.getResourceAsStream(
                "/zeron/runtime/StringUnicode.class")) {
            if (classBytes == null) {
                throw new IOException("Missing compiled StringUnicode runtime class.");
            }
            Files.copy(classBytes, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void emitBigIntegerSupportClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "runtime", "BigIntegerSupport.class"));
        Files.createDirectories(output.getParent());
        try (final var classBytes = RuntimeSupportEmitter.class.getResourceAsStream(
                "/zeron/runtime/BigIntegerSupport.class")) {
            if (classBytes == null) {
                throw new IOException("Missing compiled BigIntegerSupport runtime class.");
            }
            Files.copy(classBytes, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void emitNumericSupportClasses(final Path outputDirectory) throws IOException {
        emitJavaRuntimeClass(outputDirectory, "NumericSupport");
        emitJavaRuntimeClass(outputDirectory, "RationalValue");
    }

    static void emitJavaArraySupportClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "runtime", "JavaArraySupport.class"));
        Files.createDirectories(output.getParent());
        try (final var classBytes = RuntimeSupportEmitter.class.getResourceAsStream(
                "/zeron/runtime/JavaArraySupport.class")) {
            if (classBytes == null) {
                throw new IOException("Missing compiled JavaArraySupport runtime class.");
            }
            Files.copy(classBytes, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void emitHttpSupportClasses(final Path outputDirectory) throws IOException {
        final var classNames = List.of(
                "HttpSupport",
                "HttpSupport$ClientHandle",
                "HttpSupport$SendOutcome",
                "HttpSupport$Failure",
                "HttpSupport$ResponseHandle",
                "HttpSupport$TimedBodyInputStream",
                "HttpSupport$ReadEvent");
        for (final var className : classNames) {
            final var output = outputDirectory.resolve(
                    Path.of("zeron", "runtime", className + ".class"));
            Files.createDirectories(output.getParent());
            try (final var classBytes = RuntimeSupportEmitter.class.getResourceAsStream(
                    "/zeron/runtime/" + className + ".class")) {
                if (classBytes == null) {
                    throw new IOException("Missing compiled " + className + " runtime class.");
                }
                Files.copy(classBytes, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void emitRaisedEffectClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "runtime", "RaisedEffect.class"));
        Files.createDirectories(output.getParent());
        ClassFile.of().buildTo(output, RAISED_EFFECT_CLASS, builder -> {
            builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withSuperclass(RUNTIME_EXCEPTION_CLASS);
            builder.withField("payload", ConstantDescs.CD_Object,
                    field -> field.withFlags(ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL));
            builder.withField("suppressedOutcomes", ARRAY_LIST_CLASS,
                    field -> field.withFlags(ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL));
            builder.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void,
                    ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC, code -> {
                code.aload(0);
                code.invokespecial(RUNTIME_EXCEPTION_CLASS, "<init>", emptyVoidMethod());
                code.aload(0);
                code.new_(ARRAY_LIST_CLASS);
                code.dup();
                code.invokespecial(ARRAY_LIST_CLASS, "<init>", emptyVoidMethod());
                code.putfield(RAISED_EFFECT_CLASS, "suppressedOutcomes", ARRAY_LIST_CLASS);
                code.aload(0);
                code.aload(1);
                code.putfield(RAISED_EFFECT_CLASS, "payload", ConstantDescs.CD_Object);
                code.return_();
            });
            builder.withMethodBody("payload", MethodTypeDesc.of(ConstantDescs.CD_Object),
                    ClassFile.ACC_PUBLIC, code -> {
                        code.aload(0);
                        code.getfield(RAISED_EFFECT_CLASS, "payload", ConstantDescs.CD_Object);
                        code.areturn();
                    });
            builder.withMethodBody("addSuppressedOutcome",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, THROWABLE_CLASS),
                    ClassFile.ACC_PUBLIC, code -> {
                        final var done = code.newLabel();
                        code.aload(0);
                        code.getfield(RAISED_EFFECT_CLASS, "suppressedOutcomes", ARRAY_LIST_CLASS);
                        code.aload(1);
                        code.invokevirtual(ARRAY_LIST_CLASS, "add",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object));
                        code.pop();
                        code.aload(0);
                        code.aload(2);
                        code.if_acmpeq(done);
                        code.aload(0);
                        code.aload(2);
                        code.invokevirtual(THROWABLE_CLASS, "addSuppressed",
                                MethodTypeDesc.of(ConstantDescs.CD_void, THROWABLE_CLASS));
                        code.labelBinding(done);
                        code.return_();
                    });
            builder.withMethodBody("suppressedOutcomes",
                    MethodTypeDesc.of(ConstantDescs.CD_Object.arrayType()), ClassFile.ACC_PUBLIC, code -> {
                        code.aload(0);
                        code.getfield(RAISED_EFFECT_CLASS, "suppressedOutcomes", ARRAY_LIST_CLASS);
                        code.invokevirtual(ARRAY_LIST_CLASS, "toArray",
                                MethodTypeDesc.of(ConstantDescs.CD_Object.arrayType()));
                        code.areturn();
                    });
        });
    }

    static void emitEffectSupportClasses(final Path outputDirectory) throws IOException {
        emitRuntimeClass(outputDirectory, "EffectValue");
        emitRuntimeClass(outputDirectory, "EffectSupport");
    }

    private static void emitRuntimeClass(final Path outputDirectory, final String className) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "runtime", className + ".class"));
        Files.createDirectories(output.getParent());
        try (final var classBytes = RuntimeSupportEmitter.class.getResourceAsStream(
                "/zeron/runtime/" + className + ".class")) {
            if (classBytes == null) {
                throw new IOException("Missing compiled " + className + " runtime class.");
            }
            Files.copy(classBytes, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void emitJavaRuntimeClass(final Path outputDirectory, final String className) throws IOException {
        final var output = outputDirectory.resolve(
                Path.of("com", "maruseron", "zeron", "runtime", className + ".class"));
        Files.createDirectories(output.getParent());
        try (final var classBytes = RuntimeSupportEmitter.class.getResourceAsStream(
                "/com/maruseron/zeron/runtime/" + className + ".class")) {
            if (classBytes == null) {
                throw new IOException("Missing compiled " + className + " Java runtime class.");
            }
            Files.copy(classBytes, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void emitResourceSupportClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "runtime", "ResourceSupport.class"));
        Files.createDirectories(output.getParent());
        ClassFile.of().buildTo(output, RESOURCE_SUPPORT_CLASS, builder -> {
            builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withMethodBody("dispose", MethodTypeDesc.of(THROWABLE_CLASS,
                    ConstantDescs.CD_Object.arrayType(), ConstantDescs.CD_int, THROWABLE_CLASS),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                        final var loop = code.newLabel();
                        final var end = code.newLabel();
                        final var startDispose = code.newLabel();
                        final var endDispose = code.newLabel();
                        final var handler = code.newLabel();
                        final var hasPrimary = code.newLabel();
                        final var ordinaryPrimary = code.newLabel();
                        final var rawOutcome = code.newLabel();
                        final var outcomeReady = code.newLabel();
                        final var next = code.newLabel();

                        code.iload(1);
                        code.iconst_1();
                        code.isub();
                        code.istore(3);
                        code.labelBinding(loop);
                        code.iload(3);
                        code.iflt(end);
                        code.labelBinding(startDispose);
                        code.aload(0);
                        code.iload(3);
                        code.aaload();
                        code.checkcast(DISPOSABLE_CLASS);
                        code.invokeinterface(DISPOSABLE_CLASS, "dispose",
                                MethodTypeDesc.of(UNIT_CLASS));
                        code.pop();
                        code.labelBinding(endDispose);
                        code.goto_(next);

                        code.labelBinding(handler);
                        code.astore(4);
                        code.aload(2);
                        code.ifnonnull(hasPrimary);
                        code.aload(4);
                        code.astore(2);
                        code.goto_(next);

                        code.labelBinding(hasPrimary);
                        code.aload(2);
                        code.instanceOf(RAISED_EFFECT_CLASS);
                        code.ifeq(ordinaryPrimary);
                        code.aload(2);
                        code.checkcast(RAISED_EFFECT_CLASS);
                        code.aload(4);
                        code.instanceOf(RAISED_EFFECT_CLASS);
                        code.ifeq(rawOutcome);
                        code.aload(4);
                        code.checkcast(RAISED_EFFECT_CLASS);
                        code.invokevirtual(RAISED_EFFECT_CLASS, "payload",
                                MethodTypeDesc.of(ConstantDescs.CD_Object));
                        code.goto_(outcomeReady);
                        code.labelBinding(rawOutcome);
                        code.aload(4);
                        code.labelBinding(outcomeReady);
                        code.aload(4);
                        code.invokevirtual(RAISED_EFFECT_CLASS, "addSuppressedOutcome",
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object,
                                        THROWABLE_CLASS));
                        code.goto_(next);

                        code.labelBinding(ordinaryPrimary);
                        code.aload(2);
                        code.aload(4);
                        code.if_acmpeq(next);
                        code.aload(2);
                        code.aload(4);
                        code.invokevirtual(THROWABLE_CLASS, "addSuppressed",
                                MethodTypeDesc.of(ConstantDescs.CD_void, THROWABLE_CLASS));

                        code.labelBinding(next);
                        code.iload(3);
                        code.iconst_1();
                        code.isub();
                        code.istore(3);
                        code.goto_(loop);
                        code.labelBinding(end);
                        code.aload(2);
                        code.areturn();
                        code.exceptionCatch(startDispose, endDispose, handler, THROWABLE_CLASS);
                    });
        });
    }

    static void emitEqualitySupportClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "runtime", "EqSupport.class"));
        Files.createDirectories(output.getParent());
        ClassFile.of().buildTo(output, EQ_SUPPORT_CLASS, builder -> {
            builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withMethodBody("eqInt", MethodTypeDesc.of(ConstantDescs.CD_boolean,
                    ConstantDescs.CD_int, ConstantDescs.CD_int), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> {
                        final var unequal = code.newLabel();
                        code.iload(0);
                        code.iload(1);
                        code.if_icmpne(unequal);
                        code.iconst_1();
                        code.ireturn();
                        code.labelBinding(unequal);
                        code.iconst_0();
                        code.ireturn();
                    });
            builder.withMethodBody("eqBoolean", MethodTypeDesc.of(ConstantDescs.CD_boolean,
                    ConstantDescs.CD_boolean, ConstantDescs.CD_boolean),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                        final var unequal = code.newLabel();
                        code.iload(0);
                        code.iload(1);
                        code.if_icmpne(unequal);
                        code.iconst_1();
                        code.ireturn();
                        code.labelBinding(unequal);
                        code.iconst_0();
                        code.ireturn();
                    });
            builder.withMethodBody("eqLong", MethodTypeDesc.of(ConstantDescs.CD_boolean,
                    ConstantDescs.CD_long, ConstantDescs.CD_long),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                        final var unequal = code.newLabel();
                        code.lload(0);
                        code.lload(2);
                        code.lcmp();
                        code.ifne(unequal);
                        code.iconst_1();
                        code.ireturn();
                        code.labelBinding(unequal);
                        code.iconst_0();
                        code.ireturn();
                    });
            builder.withMethodBody("eqFloat", MethodTypeDesc.of(ConstantDescs.CD_boolean,
                    ConstantDescs.CD_double, ConstantDescs.CD_double),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                        code.dload(0);
                        code.dload(2);
                        code.invokestatic(ClassDesc.of("java.lang.Double"), "compare",
                                MethodTypeDesc.of(ConstantDescs.CD_int,
                                        ConstantDescs.CD_double, ConstantDescs.CD_double));
                        final var unequal = code.newLabel();
                        code.ifne(unequal);
                        code.iconst_1();
                        code.ireturn();
                        code.labelBinding(unequal);
                        code.iconst_0();
                        code.ireturn();
                    });
            builder.withMethodBody("eqString", MethodTypeDesc.of(ConstantDescs.CD_boolean,
                    ConstantDescs.CD_String, ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                        code.aload(0);
                        code.aload(1);
                        code.invokestatic(ClassDesc.of("java.util.Objects"), "equals",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean,
                                        ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                        code.ireturn();
                    });
            builder.withMethodBody("eqNullable", MethodTypeDesc.of(ConstantDescs.CD_boolean,
                    ConstantDescs.CD_Object, ConstantDescs.CD_Object),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                        code.aload(0);
                        code.aload(1);
                        code.invokestatic(ClassDesc.of("java.util.Objects"), "equals",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean,
                                        ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                        code.ireturn();
                    });
            builder.withMethodBody("eqNullableWithHandle", MethodTypeDesc.of(ConstantDescs.CD_boolean,
                    ConstantDescs.CD_Object, ConstantDescs.CD_Object,
                    ClassDesc.of("java.lang.invoke.MethodHandle")),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                        final var bothPresent = code.newLabel();
                        final var unequal = code.newLabel();
                        code.aload(0);
                        code.ifnonnull(bothPresent);
                        code.aload(1);
                        code.ifnonnull(unequal);
                        code.iconst_1();
                        code.ireturn();
                        code.labelBinding(bothPresent);
                        code.aload(1);
                        code.ifnull(unequal);
                        code.aload(2);
                        code.aload(0);
                        code.aload(1);
                        code.invokevirtual(ClassDesc.of("java.lang.invoke.MethodHandle"), "invoke",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean,
                                        ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                        code.ireturn();
                        code.labelBinding(unequal);
                        code.iconst_0();
                        code.ireturn();
                    });
            for (final var wrapper : new ClassDesc[]{
                    ClassDesc.of("java.lang.Integer"), ClassDesc.of("java.lang.Double"),
                    ClassDesc.of("java.lang.Boolean"), ConstantDescs.CD_String, UNIT_CLASS}) {
                builder.withMethodBody("eqNullable", MethodTypeDesc.of(ConstantDescs.CD_boolean,
                        wrapper, wrapper), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                    code.aload(0);
                    code.aload(1);
                    code.invokestatic(ClassDesc.of("java.util.Objects"), "equals",
                            MethodTypeDesc.of(ConstantDescs.CD_boolean,
                                    ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                    code.ireturn();
                });
            }

            builder.withMethodBody("eqUnit", MethodTypeDesc.of(ConstantDescs.CD_boolean,
                    UNIT_CLASS, UNIT_CLASS), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                        code.iconst_1();
                        code.ireturn();
                    });
        });
    }

    static void emitHashSupportClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "runtime", "HashSupport.class"));
        Files.createDirectories(output.getParent());
        ClassFile.of().buildTo(output, HASH_SUPPORT_CLASS, builder -> {
            builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withMethodBody("hashInt", MethodTypeDesc.of(ConstantDescs.CD_int,
                    ConstantDescs.CD_int), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                code.iload(0);
                code.ireturn();
            });
            builder.withMethodBody("hashLong", MethodTypeDesc.of(ConstantDescs.CD_int,
                    ConstantDescs.CD_long), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                code.lload(0);
                code.invokestatic(ClassDesc.of("java.lang.Long"), "hashCode",
                        MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_long));
                code.ireturn();
            });
            builder.withMethodBody("hashFloat", MethodTypeDesc.of(ConstantDescs.CD_int,
                    ConstantDescs.CD_double), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                code.dload(0);
                code.invokestatic(ClassDesc.of("java.lang.Double"), "hashCode",
                        MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_double));
                code.ireturn();
            });
            builder.withMethodBody("hashBoolean", MethodTypeDesc.of(ConstantDescs.CD_int,
                    ConstantDescs.CD_boolean), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                code.iload(0);
                code.invokestatic(ClassDesc.of("java.lang.Boolean"), "hashCode",
                        MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_boolean));
                code.ireturn();
            });
            builder.withMethodBody("hashString", MethodTypeDesc.of(ConstantDescs.CD_int,
                    ConstantDescs.CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                code.aload(0);
                code.invokevirtual(ConstantDescs.CD_String, "hashCode",
                        MethodTypeDesc.of(ConstantDescs.CD_int));
                code.ireturn();
            });
            builder.withMethodBody("hashUnit", MethodTypeDesc.of(ConstantDescs.CD_int,
                    UNIT_CLASS), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                code.iconst_0();
                code.ireturn();
            });
            builder.withMethodBody("hashNullableWithHandle", MethodTypeDesc.of(ConstantDescs.CD_int,
                    ConstantDescs.CD_Object, ClassDesc.of("java.lang.invoke.MethodHandle")),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                final var present = code.newLabel();
                code.aload(0);
                code.ifnonnull(present);
                code.iconst_0();
                code.ireturn();
                code.labelBinding(present);
                code.aload(1);
                code.aload(0);
                code.invokevirtual(ClassDesc.of("java.lang.invoke.MethodHandle"), "invoke",
                        MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_Object));
                code.ireturn();
            });
        });
    }

    static void emitFunctionShape(final Path outputDirectory,
                                 final FunctionShapeKey shapeKey,
                                 final FunctionDescriptor type) throws IOException {
        final var className = FunctionShapeNames.interfaceName(shapeKey);
        final var classDesc = ClassDesc.of(className);
        final var outFile = outputDirectory.resolve(className.replace('.', '/') + ".class");
        final var parent = outFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        ClassFile.of().buildTo(outFile, classDesc, builder -> {
            builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
            final var objectType = TypeDescriptor.ofName("java.lang.Object");
            final List<TypeDescriptor> objectParameters =
                    java.util.Collections.nCopies(type.arity(), objectType);
            final var objectShape = type.receiverType() == null
                    ? TypeDescriptor.functionWithEffectsOf(type.name(), objectType,
                            objectParameters, java.util.List.of(), java.util.List.of())
                    : TypeDescriptor.receiverFunctionOf(type.name(), objectType, objectType,
                            objectParameters, java.util.List.of(), java.util.List.of());
            final var objectShapeKey = FunctionShapeKey.of(objectShape);
            final var objectInterface = ClassDesc.of(FunctionShapeNames.interfaceName(objectShapeKey));
            if (!methodDescriptor(type).equals(methodDescriptor(objectShape))) {
                builder.withInterfaceSymbols(java.util.List.of(objectInterface));
            }
            builder.withMethod("invoke", methodDescriptor(type),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, _ -> {});
            if (!methodDescriptor(type).equals(methodDescriptor(objectShape))) {
                builder.withMethod("invoke", methodDescriptor(objectShape), ClassFile.ACC_PUBLIC,
                        methodBuilder -> methodBuilder.withCode(code -> {
                            code.aload(0);
                            for (int index = 0; index < type.invocationParameters().size(); index++) {
                                code.aload(index + 1);
                                final var parameter = type.invocationParameters().get(index);
                                emitObjectToFunctionType(code, parameter);
                            }
                            code.invokeinterface(classDesc, "invoke", methodDescriptor(type));
                            if (type.returnType() instanceof NeverDescriptor) {
                                code.aconst_null();
                            } else {
                                emitFunctionResultToObject(code, type.returnType());
                            }
                            code.areturn();
                        }));
            }
        });
    }

    private static void emitObjectToFunctionType(final java.lang.classfile.CodeBuilder code,
                                                 final TypeDescriptor type) {
        final var descriptor = TypeDescriptor.toJavaClassDesc(type);
        final var wrapper = switch (descriptor.descriptorString()) {
            case "I" -> ClassDesc.of("java.lang.Integer");
            case "J" -> ClassDesc.of("java.lang.Long");
            case "D" -> ClassDesc.of("java.lang.Double");
            case "Z" -> ClassDesc.of("java.lang.Boolean");
            case "B" -> ClassDesc.of("java.lang.Byte");
            default -> null;
        };
        if (wrapper == null) {
            if (!descriptor.equals(ConstantDescs.CD_Object)) code.checkcast(descriptor);
            return;
        }
        code.checkcast(wrapper);
        final var method = switch (descriptor.descriptorString()) {
            case "I" -> "intValue";
            case "J" -> "longValue";
            case "D" -> "doubleValue";
            case "Z" -> "booleanValue";
            case "B" -> "byteValue";
            default -> throw new IllegalStateException("Unsupported primitive function parameter.");
        };
        code.invokevirtual(wrapper, method, MethodTypeDesc.of(descriptor));
    }

    private static void emitFunctionResultToObject(final java.lang.classfile.CodeBuilder code,
                                                   final TypeDescriptor type) {
        final var descriptor = TypeDescriptor.toJavaClassDesc(type);
        final var wrapper = switch (descriptor.descriptorString()) {
            case "I" -> ClassDesc.of("java.lang.Integer");
            case "J" -> ClassDesc.of("java.lang.Long");
            case "D" -> ClassDesc.of("java.lang.Double");
            case "Z" -> ClassDesc.of("java.lang.Boolean");
            case "B" -> ClassDesc.of("java.lang.Byte");
            default -> null;
        };
        if (wrapper == null) return;
        code.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, descriptor));
    }

    private static MethodTypeDesc methodDescriptor(final FunctionDescriptor type) {
        final var returnType = TypeDescriptor.toJavaClassDesc(type.returnType());
        final var parameterTypes = type.invocationParameters().stream()
                .map(TypeDescriptor::toJavaClassDesc).toList();
        return MethodTypeDesc.of(returnType, parameterTypes);
    }

    private static MethodTypeDesc emptyVoidMethod() {
        return MethodTypeDesc.ofDescriptor("()V");
    }
}
