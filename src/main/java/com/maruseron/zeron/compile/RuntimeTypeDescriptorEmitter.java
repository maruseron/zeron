package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits the compiler's side of the ZEP-0015 runtime descriptor ABI.
 *
 * <p>A type without type parameters is emitted as a canonical string constant and looked up in
 * {@link zeron.runtime.AppliedTypeDescriptors}; the lookup returns a cached descriptor and does
 * not allocate. A type containing a reified caller parameter is composed from the hidden
 * descriptor parameters instead.
 */
final class RuntimeTypeDescriptorEmitter {
    static final String DESCRIPTOR_CLASS_NAME = "zeron.runtime.AppliedTypeDescriptor";
    static final String DESCRIPTORS_CLASS_NAME = "zeron.runtime.AppliedTypeDescriptors";
    static final ClassDesc DESCRIPTOR_CLASS = ClassDesc.of(DESCRIPTOR_CLASS_NAME);
    static final TypeDescriptor DESCRIPTOR_TYPE = TypeDescriptor.ofName(DESCRIPTOR_CLASS_NAME);

    private RuntimeTypeDescriptorEmitter() {
    }

    static List<TypeParameterDescriptor> reifiedParameters(
            final List<TypeParameterDescriptor> typeParameters) {
        return typeParameters.stream().filter(TypeParameterDescriptor::isReified).toList();
    }

    static List<TypeParameterDescriptor> reifiedParameters(final FunctionDescriptor function) {
        return reifiedParameters(function.typeParameters());
    }

    static List<Token> reifiedSlots(final List<TypeParameterDescriptor> typeParameters) {
        return reifiedParameters(typeParameters).stream().map(Expr::reifiedToken).toList();
    }

    static void emit(final CompilationContext context, final CodeBuilder code,
                     final TypeDescriptor type) {
        if (!containsTypeParameter(type)) {
            code.ldc(canonical(type));
            code.invokestatic(ClassDesc.of(DESCRIPTORS_CLASS_NAME), "lookup",
                    MethodTypeDesc.of(DESCRIPTOR_CLASS, ConstantDescs.CD_String));
            return;
        }
        switch (type) {
            case TypeParameterDescriptor parameter -> {
                if (!parameter.isReified()) {
                    throw new IllegalStateException(
                            "A non-reified type parameter reached descriptor emission: "
                                    + parameter.name());
                }
                BytecodeEmitter.emitVariable(context, code, Expr.reifiedToken(parameter));
            }
            case NullableDescriptor nullable -> {
                emit(context, code, nullable.baseType());
                code.invokevirtual(DESCRIPTOR_CLASS, "nullable",
                        MethodTypeDesc.of(DESCRIPTOR_CLASS));
            }
            case ReferenceDescriptor reference -> {
                emit(context, code, reference.baseType());
                code.invokevirtual(DESCRIPTOR_CLASS, "mutable",
                        MethodTypeDesc.of(DESCRIPTOR_CLASS));
            }
            case ArrayDescriptor array -> {
                emit(context, code, array.elementType());
                code.invokestatic(DESCRIPTOR_CLASS, "arrayOf",
                        MethodTypeDesc.of(DESCRIPTOR_CLASS, DESCRIPTOR_CLASS));
            }
            case GenericDescriptor generic -> {
                code.ldc(generic.baseType().name());
                emitArgumentArray(context, code, generic.typeParameters());
                code.invokestatic(DESCRIPTOR_CLASS, "of",
                        MethodTypeDesc.of(DESCRIPTOR_CLASS, ConstantDescs.CD_String,
                                DESCRIPTOR_CLASS.arrayType()));
            }
            default -> throw new IllegalStateException(
                    "Unsupported reified type composition: " + type);
        }
    }

    static void emitArguments(final CompilationContext context, final CodeBuilder code,
                              final List<TypeDescriptor> types) {
        for (final var type : types) emit(context, code, type);
    }

    private static void emitArgumentArray(final CompilationContext context, final CodeBuilder code,
                                          final List<TypeDescriptor> types) {
        code.loadConstant(types.size());
        code.anewarray(DESCRIPTOR_CLASS);
        for (int index = 0; index < types.size(); index++) {
            code.dup();
            code.loadConstant(index);
            emit(context, code, types.get(index));
            code.aastore();
        }
    }

    static boolean containsTypeParameter(final TypeDescriptor type) {
        return switch (type) {
            case TypeParameterDescriptor _ -> true;
            case NullableDescriptor nullable -> containsTypeParameter(nullable.baseType());
            case ReferenceDescriptor reference -> containsTypeParameter(reference.baseType());
            case ArrayDescriptor array -> containsTypeParameter(array.elementType());
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(RuntimeTypeDescriptorEmitter::containsTypeParameter);
            default -> false;
        };
    }

    static String canonical(final TypeDescriptor type) {
        return switch (type) {
            case NeverDescriptor _ -> "zeron.lang.Never";
            case AnyDescriptor _ -> "zeron.lang.Any";
            case UnitDescriptor _ -> "zeron.lang.Unit";
            case IntDescriptor _ -> "zeron.lang.Int";
            case CharDescriptor _ -> "zeron.lang.Char";
            case LongDescriptor _ -> "zeron.lang.Long";
            case ByteDescriptor _ -> "zeron.lang.Byte";
            case FloatDescriptor _ -> "zeron.lang.Float";
            case BooleanDescriptor _ -> "zeron.lang.Boolean";
            case StringDescriptor _ -> "zeron.lang.String";
            case ByteArrayDescriptor _ -> "zeron.lang.ByteArray";
            case NominalDescriptor nominal -> nominal.name();
            case GenericDescriptor generic -> generic.baseType().name() + generic.typeParameters()
                    .stream().map(RuntimeTypeDescriptorEmitter::canonical)
                    .collect(java.util.stream.Collectors.joining(",", "<", ">"));
            case NullableDescriptor nullable -> canonical(nullable.baseType()) + "?";
            case ReferenceDescriptor reference -> canonical(reference.baseType()) + "!";
            case ArrayDescriptor array -> canonical(array.elementType()) + "[]";
            default -> throw new IllegalArgumentException(
                    "Type has no exact runtime descriptor: " + type);
        };
    }
}
