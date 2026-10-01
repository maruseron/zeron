package com.maruseron.zeron.domain;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.util.*;

public sealed interface TypeDescriptor
        permits InferDescriptor, NeverDescriptor, UnitDescriptor,
                IntDescriptor, FloatDescriptor, BooleanDescriptor, StringDescriptor,
                NominalDescriptor, FunctionDescriptor, GenericDescriptor, ArrayDescriptor,
                NullableDescriptor, NullDescriptor, ReferenceDescriptor {

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
            case "Infer"   -> ofInfer();
            case "Unit"    -> ofUnit();
            case "Int"     -> ofInt();
            case "Float"   -> ofFloat();
            case "Boolean" -> ofBoolean();
            case "String"  -> ofString();
            default -> ofName(typeName);
        };
    }

    static NominalDescriptor ofName(String name) {
        return new NominalDescriptor(name);
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

    static FunctionDescriptor lambdaOf(final TypeDescriptor returnType,
                                       final TypeDescriptor parameterType) {
        return functionOf("", returnType, parameterType == null
                ? new TypeDescriptor[]{}
                : new TypeDescriptor[]{parameterType});
    }

    static ClassDesc toJavaClassDesc(final TypeDescriptor td) {
        return switch (td) {
            case InferDescriptor    id ->
                    throw new IllegalArgumentException(
                            "Infer is not a valid concrete type");
            case NeverDescriptor    nd -> ConstantDescs.CD_void;
            case UnitDescriptor     ud -> ConstantDescs.CD_Void;
            case IntDescriptor      id -> ConstantDescs.CD_int;
            case FloatDescriptor    fd -> ConstantDescs.CD_double;
            case BooleanDescriptor  bd -> ConstantDescs.CD_boolean;
            case StringDescriptor   sd -> ConstantDescs.CD_String;
            case NominalDescriptor  nd -> ClassDesc.of(nd.name());
            case FunctionDescriptor fd -> ClassDesc.of(FunctionShapeNames.interfaceName(fd));
            case ArrayDescriptor _ -> ConstantDescs.CD_Object.arrayType();
            case ReferenceDescriptor rd -> toJavaClassDesc(rd.baseType());
            case NullableDescriptor nd -> switch (nd.baseType()) {
                case IntDescriptor _ -> ConstantDescs.CD_Integer;
                case FloatDescriptor _ -> ConstantDescs.CD_Double;
                case BooleanDescriptor _ -> ConstantDescs.CD_Boolean;
                case NeverDescriptor _ -> ConstantDescs.CD_Object;
                default -> toJavaClassDesc(nd.baseType());
            };
            case NullDescriptor _ -> ConstantDescs.CD_Object;
            case GenericDescriptor  gd ->
                    throw new UnsupportedOperationException(
                            "Generic descriptors to be implemented");
        };
    }

    static ClassDesc toJavaWrapper(final TypeDescriptor td) {
        return switch (td) {
            case InferDescriptor    id ->
                    throw new IllegalArgumentException(
                            "Infer is not a valid concrete type");
            case NeverDescriptor    nd ->
                    throw new IllegalArgumentException(
                            "Illegal conversion: NeverDescriptor to java.constant.ClassDesc");
            case UnitDescriptor     ud ->
                    throw new IllegalArgumentException(
                            "Illegal conversion: UnitDescriptor to java.constant.ClassDesc");
            case IntDescriptor      id -> ConstantDescs.CD_Integer;
            case FloatDescriptor    fd -> ConstantDescs.CD_Double;
            case BooleanDescriptor  bd -> ConstantDescs.CD_Boolean;
            case StringDescriptor   sd ->
                    throw new IllegalArgumentException(
                            "Illegal conversion: StringDescriptor to java.constant.ClassDesc");
            case NominalDescriptor  nd ->
                    throw new IllegalArgumentException(
                            "Illegal conversion: NominalDescriptor to java.constant.ClassDesc");
            case FunctionDescriptor fd ->
                    throw new IllegalArgumentException(
                            "Illegal conversion: FunctionDescriptor to java.constant.ClassDesc");
                case ReferenceDescriptor rd -> toJavaWrapper(rd.baseType());
                case ArrayDescriptor _ -> ConstantDescs.CD_Object.arrayType();
                case NullableDescriptor nd -> toJavaClassDesc(nd);
                case NullDescriptor _ -> ConstantDescs.CD_Object;
            case GenericDescriptor  gd ->
                    throw new UnsupportedOperationException(
                            "Generic descriptors to be implemented");
        };
    }
}
