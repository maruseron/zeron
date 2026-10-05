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
