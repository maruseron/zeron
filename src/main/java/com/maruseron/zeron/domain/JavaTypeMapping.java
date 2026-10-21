package com.maruseron.zeron.domain;

import java.lang.constant.ClassDesc;

public final class JavaTypeMapping {
    private JavaTypeMapping() {}

    public static TypeDescriptor toZeronType(final ClassDesc javaType) {
        return switch (javaType.descriptorString()) {
            case "V" -> TypeDescriptor.ofUnit();
            case "I" -> TypeDescriptor.ofInt();
            case "J" -> TypeDescriptor.ofLong();
            case "B" -> TypeDescriptor.ofByte();
            case "D" -> TypeDescriptor.ofFloat();
            case "Z" -> TypeDescriptor.ofBoolean();
            case "F" -> TypeDescriptor.ofFloat();
            case "Ljava/lang/String;" -> TypeDescriptor.ofString().toNullable();
            case "Ljava/lang/Integer;" -> TypeDescriptor.ofInt().toNullable();
            case "Ljava/lang/Long;" -> TypeDescriptor.ofLong().toNullable();
            case "Ljava/lang/Double;" -> TypeDescriptor.ofFloat().toNullable();
            case "Ljava/lang/Boolean;" -> TypeDescriptor.ofBoolean().toNullable();
            case "Ljava/lang/Object;" -> TypeDescriptor.ofAny().toNullable();
            default -> referenceType(javaType.descriptorString());
        };
    }

    public static TypeDescriptor toZeronParameterType(final ClassDesc javaType) {
        final var descriptor = javaType.descriptorString();
        if (descriptor.equals("F") || descriptor.equals("V")) return null;
        return switch (descriptor) {
            case "[B" -> TypeDescriptor.ofByteArray().toNullable();
            case "[I" -> TypeDescriptor.arrayOf(TypeDescriptor.ofInt()).toNullable();
            case "[J" -> TypeDescriptor.arrayOf(TypeDescriptor.ofLong()).toNullable();
            case "[D" -> TypeDescriptor.arrayOf(TypeDescriptor.ofFloat()).toNullable();
            case "[Z" -> TypeDescriptor.arrayOf(TypeDescriptor.ofBoolean()).toNullable();
            case "[Ljava/lang/Object;" ->
                    TypeDescriptor.arrayOf(TypeDescriptor.ofAny().toNullable()).toNullable();
            default -> toZeronType(javaType);
        };
    }

    public static TypeDescriptor toZeronReturnType(final ClassDesc javaType) {
        if (javaType.descriptorString().equals("V")) return TypeDescriptor.ofUnit();
        if (javaType.descriptorString().equals("F")) return TypeDescriptor.ofFloat();
        return toZeronParameterType(javaType);
    }

    public static TypeDescriptor toZeronMethodType(final ClassDesc javaType) {
        return toZeronParameterType(javaType);
    }

    private static TypeDescriptor referenceType(final String descriptor) {
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) return null;
        final var binaryName = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        return new NullableDescriptor(new ReferenceDescriptor(TypeDescriptor.ofName(binaryName)));
    }
}
