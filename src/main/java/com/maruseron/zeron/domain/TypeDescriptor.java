package com.maruseron.zeron.domain;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.*;

public sealed interface TypeDescriptor
        permits InferDescriptor, NeverDescriptor, UnitDescriptor,
                IntDescriptor, LongDescriptor, ByteDescriptor, FloatDescriptor, BooleanDescriptor, StringDescriptor,
                CharDescriptor,
            AnyDescriptor, NominalDescriptor, FunctionDescriptor, GenericDescriptor, ArrayDescriptor,
                ByteArrayDescriptor,
                NullableDescriptor, NullDescriptor, ReferenceDescriptor,
                TypeParameterDescriptor, AppliedConstructorDescriptor, TypeConstructorDescriptor,
                TypeProjectionDescriptor, CapturedTypeDescriptor {

    // Contract
    String name();

    default TypeDescriptor toNullable() {
        if (this instanceof NullableDescriptor || this instanceof NullDescriptor) return this;
        return new NullableDescriptor(this);
    }

    default boolean isNullable() {
        return this instanceof NullableDescriptor;
    }

    // Overridable defaults
    default String descriptor() {
        final var modifierDescriptors = isNullable() ? "?" : "";
        return modifierDescriptors + ":" + name();
    }

    // General transforms
    default TypeDescriptor orElse(TypeDescriptor other) {
        return this instanceof InferDescriptor ? other : this;
    }

    // Checkers
    default boolean isDoubleWidth() {
        return false;
    }

    default boolean isWellFormed() {
        return !(this instanceof InferDescriptor); // && !(this instanceof TypeParameter tp && tp.isTypeParameter());
    }

    // Factories
    static TypeDescriptor of(String typeName) {
        return switch (typeName) {
            case "Never"   -> ofNever();
            case "Any"     -> ofAny();
            case "Infer"   -> ofInfer();
            case "Unit"    -> ofUnit();
            case "Int"     -> ofInt();
            case "Char"    -> ofChar();
            case "Long"    -> ofLong();
            case "Byte"    -> ofByte();
            case "Float"   -> ofFloat();
            case "Boolean" -> ofBoolean();
            case "String"  -> ofString();
            case "ByteArray" -> ofByteArray();
            default -> ofName(typeName);
        };
    }

    static NominalDescriptor ofName(String name) {
        return new NominalDescriptor(name);
    }

    static AnyDescriptor ofAny() {
        return AnyDescriptor.ANY;
    }

    static InferDescriptor ofInfer() {
        return InferDescriptor.INSTANCE;
    }

    static NeverDescriptor ofNever() {
        return NeverDescriptor.NEVER;
    }

    static NullDescriptor ofNull() {
        return NullDescriptor.NULL;
    }

    static UnitDescriptor ofUnit() {
        return UnitDescriptor.UNIT;
    }

    static IntDescriptor ofInt() {
        return IntDescriptor.INT;
    }

    static CharDescriptor ofChar() {
        return CharDescriptor.CHAR;
    }

    static LongDescriptor ofLong() {
        return LongDescriptor.LONG;
    }

    static ByteDescriptor ofByte() {
        return ByteDescriptor.BYTE;
    }

    static ByteArrayDescriptor ofByteArray() {
        return ByteArrayDescriptor.BYTE_ARRAY;
    }

    static FloatDescriptor ofFloat() {
        return FloatDescriptor.FLOAT;
    }

    static BooleanDescriptor ofBoolean() {
        return BooleanDescriptor.BOOLEAN;
    }

    static StringDescriptor ofString() {
        return StringDescriptor.STRING;
    }

    static GenericDescriptor genericOf(final NominalDescriptor baseType,
                                       final List<TypeDescriptor> typeParams) {
        return new GenericDescriptor(baseType, typeParams);
    }

    static ArrayDescriptor arrayOf(final TypeDescriptor elementType) {
        return new ArrayDescriptor(elementType);
    }

    static GenericDescriptor genericOf(final NominalDescriptor baseType,
                                       final TypeDescriptor... typeParameters) {
        return genericOf(baseType, List.of(typeParameters));
    }

    static FunctionDescriptor functionOf(final String name,
                                         final TypeDescriptor returnType,
                                         final TypeDescriptor... parameterTypes) {
        return new FunctionDescriptor(name, returnType, List.of(parameterTypes));
    }

    static FunctionDescriptor genericFunctionOf(final String name,
                                                final TypeDescriptor returnType,
                                                final List<TypeDescriptor> parameterTypes,
                                                final List<TypeParameterDescriptor> typeParameters) {
        return new FunctionDescriptor(name, returnType, parameterTypes, typeParameters);
    }

    static FunctionDescriptor functionWithEffectsOf(final String name,
                                                    final TypeDescriptor returnType,
                                                    final List<TypeDescriptor> parameterTypes,
                                                    final List<TypeParameterDescriptor> typeParameters,
                                                    final List<TypeDescriptor> raisedEffects) {
        return new FunctionDescriptor(name, returnType, parameterTypes, typeParameters, raisedEffects);
    }

    static FunctionDescriptor handlerOf(final TypeDescriptor returnType,
                                        final List<TypeDescriptor> handledEffects,
                                        final List<TypeDescriptor> raisedEffects) {
        return new FunctionDescriptor("", returnType, List.of(ofAny()), List.of(),
                raisedEffects, handledEffects).asHandler();
    }

    static FunctionDescriptor lambdaOf(final TypeDescriptor returnType,
                                       final TypeDescriptor parameterType) {
        return functionOf("", returnType, parameterType == null
                ? new TypeDescriptor[]{}
                : new TypeDescriptor[]{parameterType});
    }

    static ClassDesc toJavaClassDesc(final TypeDescriptor td) {
        return switch (td) {
            case InferDescriptor         _ ->
                    throw new IllegalArgumentException(
                            "Infer is not a valid concrete type");
            case NeverDescriptor         _ -> ConstantDescs.CD_void;
            case UnitDescriptor          _ -> ClassDesc.of("zeron.lang.Unit");
            case AnyDescriptor           _ -> ConstantDescs.CD_Object;
            case IntDescriptor           _ -> ConstantDescs.CD_int;
            case CharDescriptor          _ -> ConstantDescs.CD_int;
            case LongDescriptor          _ -> ConstantDescs.CD_long;
            case ByteDescriptor          _ -> ConstantDescs.CD_byte;
            case FloatDescriptor         _ -> ConstantDescs.CD_double;
            case BooleanDescriptor       _ -> ConstantDescs.CD_boolean;
            case StringDescriptor        _ -> ConstantDescs.CD_String;
            case NominalDescriptor      nd -> ClassDesc.of(nd.name());
            case TypeParameterDescriptor _ -> ConstantDescs.CD_Object;
            case AppliedConstructorDescriptor _ -> ConstantDescs.CD_Object;
            case TypeConstructorDescriptor _ -> ConstantDescs.CD_Object;
            case FunctionDescriptor     fd -> ClassDesc.of(FunctionShapeNames.interfaceName(
                    fd.isHandler() ? functionOf("", fd.returnType(), ofAny())
                            : (FunctionDescriptor) TypeSubstitution.erase(fd)));
            case ArrayDescriptor         _ -> ConstantDescs.CD_Object.arrayType();
            case ByteArrayDescriptor     _ -> ClassDesc.ofDescriptor("[B");
            case ReferenceDescriptor    rd -> toJavaClassDesc(rd.baseType());
            case NullableDescriptor     nd -> switch (nd.baseType()) {
                case UnitDescriptor    _ -> ClassDesc.of("zeron.lang.Unit");
                case IntDescriptor     _ -> ConstantDescs.CD_Integer;
                case CharDescriptor    _ -> ConstantDescs.CD_Integer;
                case LongDescriptor    _ -> ConstantDescs.CD_Long;
                case ByteDescriptor    _ -> ClassDesc.of("java.lang.Byte");
                case FloatDescriptor   _ -> ConstantDescs.CD_Double;
                case BooleanDescriptor _ -> ConstantDescs.CD_Boolean;
                case NeverDescriptor   _ -> ConstantDescs.CD_Object;
                default -> toJavaClassDesc(nd.baseType());
            };
            case NullDescriptor _ -> ConstantDescs.CD_Object;
            case GenericDescriptor generic -> ClassDesc.of(generic.baseType().name());
            case TypeProjectionDescriptor projection -> toJavaClassDesc(projection.bound());
            case CapturedTypeDescriptor capture -> toJavaClassDesc(capture.upperBound());
        };
    }

    static ClassDesc toJavaWrapper(final TypeDescriptor td) {
        return switch (td) {
            case InferDescriptor         _ ->
                    throw new IllegalArgumentException(
                            "Infer is not a valid concrete type");
            case NeverDescriptor         _ ->
                    throw new IllegalArgumentException(
                            "Illegal conversion: NeverDescriptor to java.constant.ClassDesc");
            case UnitDescriptor          _ -> ClassDesc.of("zeron.lang.Unit");
            case AnyDescriptor           _ -> ConstantDescs.CD_Object;
            case IntDescriptor           _ -> ConstantDescs.CD_Integer;
            case CharDescriptor          _ -> ConstantDescs.CD_Integer;
            case LongDescriptor          _ -> ConstantDescs.CD_Long;
            case ByteDescriptor          _ -> ClassDesc.of("java.lang.Byte");
            case FloatDescriptor         _ -> ConstantDescs.CD_Double;
            case BooleanDescriptor       _ -> ConstantDescs.CD_Boolean;
            case StringDescriptor        _ ->
                    throw new IllegalArgumentException(
                            "Illegal conversion: StringDescriptor to java.constant.ClassDesc");
            case NominalDescriptor       _ ->
                    throw new IllegalArgumentException(
                            "Illegal conversion: NominalDescriptor to java.constant.ClassDesc");
            case TypeParameterDescriptor _ -> ConstantDescs.CD_Object;
            case AppliedConstructorDescriptor _ -> ConstantDescs.CD_Object;
            case TypeConstructorDescriptor _ ->
                    throw new IllegalArgumentException("A type constructor is not a runtime value type.");
            case FunctionDescriptor      _ ->
                    throw new IllegalArgumentException(
                            "Illegal conversion: FunctionDescriptor to java.constant.ClassDesc");
            case ReferenceDescriptor    rd -> toJavaWrapper(rd.baseType());
            case ArrayDescriptor         _ -> ConstantDescs.CD_Object.arrayType();
            case ByteArrayDescriptor     _ -> ClassDesc.ofDescriptor("[B");
            case NullableDescriptor     nd -> toJavaClassDesc(nd);
            case NullDescriptor          _ -> ConstantDescs.CD_Object;
            case GenericDescriptor generic -> ClassDesc.of(generic.baseType().name());
            case TypeProjectionDescriptor projection -> toJavaWrapper(projection.bound());
            case CapturedTypeDescriptor capture -> toJavaWrapper(capture.upperBound());
        };
    }
}
