package com.maruseron.zeron.domain;

import java.lang.constant.ClassDesc;

public final class JavaTypeMapping {
    private JavaTypeMapping() {}

    public static TypeDescriptor toZeronType(final ClassDesc javaType) {
        return switch (javaType.descriptorString()) {
            case "V" -> TypeDescriptor.ofUnit();
            case "I" -> TypeDescriptor.ofInt();
            case "D" -> TypeDescriptor.ofFloat();
            case "Z" -> TypeDescriptor.ofBoolean();
            case "Ljava/lang/String;" -> TypeDescriptor.ofString().toNullable();
            case "Ljava/lang/Integer;" -> TypeDescriptor.ofInt().toNullable();
            case "Ljava/lang/Double;" -> TypeDescriptor.ofFloat().toNullable();
            case "Ljava/lang/Boolean;" -> TypeDescriptor.ofBoolean().toNullable();
            case "Ljava/lang/Object;" -> TypeDescriptor.ofAny().toNullable();
            default -> referenceType(javaType.descriptorString());
        };
    }

    private static TypeDescriptor referenceType(final String descriptor) {
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) return null;
        final var binaryName = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        return new NullableDescriptor(new ReferenceDescriptor(TypeDescriptor.ofName(binaryName)));
    }
}
