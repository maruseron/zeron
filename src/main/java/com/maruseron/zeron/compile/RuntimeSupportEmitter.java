package com.maruseron.zeron.compile;

import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.FunctionShapeKey;
import com.maruseron.zeron.domain.FunctionShapeNames;
import com.maruseron.zeron.domain.TypeDescriptor;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;

final class RuntimeSupportEmitter {
    private static final ClassDesc UNIT_CLASS = ClassDesc.of("zeron.lang.Unit");
    private static final ClassDesc RAISED_EFFECT_CLASS = ClassDesc.of("zeron.runtime.RaisedEffect");
    private static final ClassDesc EQ_SUPPORT_CLASS = ClassDesc.of("zeron.runtime.EqSupport");
    private static final ClassDesc RUNTIME_EXCEPTION_CLASS = ClassDesc.of("java.lang.RuntimeException");

    private RuntimeSupportEmitter() {}

    static void emitUnitClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "lang", "Unit.class"));
        Files.createDirectories(output.getParent());
        ClassFile.of().buildTo(output, UNIT_CLASS, builder -> {
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

    static void emitRaisedEffectClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "runtime", "RaisedEffect.class"));
        Files.createDirectories(output.getParent());
        ClassFile.of().buildTo(output, RAISED_EFFECT_CLASS, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            builder.withSuperclass(RUNTIME_EXCEPTION_CLASS);
            builder.withField("payload", ConstantDescs.CD_Object,
                    field -> field.withFlags(ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL));
            builder.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void,
                    ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC, code -> {
                code.aload(0);
                code.invokespecial(RUNTIME_EXCEPTION_CLASS, "<init>", emptyVoidMethod());
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
        });
    }

    static void emitEqualitySupportClass(final Path outputDirectory) throws IOException {
        final var output = outputDirectory.resolve(Path.of("zeron", "runtime", "EqSupport.class"));
        Files.createDirectories(output.getParent());
        ClassFile.of().buildTo(output, EQ_SUPPORT_CLASS, builder -> {
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

    static void emitFunctionShape(final Path outputDirectory,
                                 final FunctionShapeKey shapeKey,
                                 final FunctionDescriptor type) throws IOException {
        final var className = FunctionShapeNames.interfaceName(shapeKey);
        final var classDesc = ClassDesc.of(className);
        final var outFile = outputDirectory.resolve(className.replace('.', '/') + ".class");
        final var parent = outFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        ClassFile.of().buildTo(outFile, classDesc, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
            builder.withMethod("invoke", methodDescriptor(type),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, _ -> {});
        });
    }

    private static MethodTypeDesc methodDescriptor(final FunctionDescriptor type) {
        final var returnType = TypeDescriptor.toJavaClassDesc(type.returnType());
        final var parameterTypes = type.parameters().stream().map(TypeDescriptor::toJavaClassDesc).toList();
        return MethodTypeDesc.of(returnType, parameterTypes);
    }

    private static MethodTypeDesc emptyVoidMethod() {
        return MethodTypeDesc.ofDescriptor("()V");
    }
}
