package com.maruseron.zeron.domain;

import java.util.List;
import java.util.Set;

public final class BuiltinTypes {
    private static final List<String> COMPLETION_NAMES = List.of(
            "Any", "Array", "Boolean", "Byte", "ByteArray", "Char", "Float", "Infer", "Int", "Long",
            "Never", "String", "Unit");

    public static final Set<String> NAMES = Set.copyOf(COMPLETION_NAMES);
    public static final List<String> COMPLETION_CANDIDATES = COMPLETION_NAMES.stream()
            .filter(name -> !name.equals("Infer"))
            .toList();

    private BuiltinTypes() {}

    public static boolean supportsExtensionReceiver(final TypeDescriptor receiverType) {
        return receiverType instanceof ArrayDescriptor
                || receiverType instanceof ByteDescriptor
                || receiverType instanceof ByteArrayDescriptor
                || receiverType instanceof UnitDescriptor
                || receiverType instanceof IntDescriptor
                || receiverType instanceof CharDescriptor
                || receiverType instanceof LongDescriptor
                || receiverType instanceof FloatDescriptor
                || receiverType instanceof BooleanDescriptor
                || receiverType instanceof StringDescriptor;
    }

    /// The package that owns canonical evidence for an intrinsic target. All intrinsic targets,
    /// including `Array`, are canonically owned by `zeron.lang`; defaults for an intrinsic type are
    /// allowed only in this package.
    public static String canonicalWitnessPackage(final TypeDescriptor type) {
        if (type == null) return null;
        final var base = type instanceof TypeConstructorDescriptor constructor
                ? constructor.baseType() : type;
        final var name = base.name();
        if (base instanceof ArrayDescriptor || base instanceof ByteDescriptor || base instanceof ByteArrayDescriptor
                || base instanceof UnitDescriptor || base instanceof IntDescriptor
                || base instanceof CharDescriptor || base instanceof LongDescriptor
                || base instanceof FloatDescriptor || base instanceof BooleanDescriptor
                || base instanceof StringDescriptor || "Int".equals(name) || "Long".equals(name)
                || "Float".equals(name) || "Boolean".equals(name) || "String".equals(name)
                || "Char".equals(name) || "Byte".equals(name) || "ByteArray".equals(name)
                || "Unit".equals(name) || "Array".equals(name)) {
            return "zeron.lang";
        }
        return null;
    }

    public static boolean isIntrinsicTarget(final TypeDescriptor type) {
        return canonicalWitnessPackage(type) != null;
    }
}
