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
}
