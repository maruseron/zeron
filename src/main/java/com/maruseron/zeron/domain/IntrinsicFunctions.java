package com.maruseron.zeron.domain;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/// Standard-library functions whose bodies the compiler replaces with an intrinsic operation.
///
/// A declaration is recognized by its qualified standard-library name. The simple name carries no
/// special meaning anywhere else, so an identically named user declaration remains an ordinary
/// function and must not silently become an intrinsic.
public final class IntrinsicFunctions {
    private static final Map<String, IntrinsicId> DECLARATIONS = Map.ofEntries(
            Map.entry("zeron.collections.allocateArray", IntrinsicId.ARRAY_ALLOC),
            Map.entry("zeron.internal.allocateArray", IntrinsicId.ARRAY_ALLOC),
            Map.entry("zeron.collections.clearArraySlot", IntrinsicId.ARRAY_CLEAR_SLOT),
            Map.entry("zeron.internal.allocateByteArray", IntrinsicId.BYTE_ARRAY_ALLOC),
            Map.entry("zeron.io.allocateByteArray", IntrinsicId.BYTE_ARRAY_ALLOC),
            Map.entry("zeron.internal.intToByte", IntrinsicId.INT_TO_BYTE),
            Map.entry("zeron.internal.byteToInt", IntrinsicId.BYTE_TO_INT),
            Map.entry("zeron.internal.unwrapSome", IntrinsicId.OPTION_UNWRAP_SOME));

    private static final Set<String> DECLARATION_SIMPLE_NAMES = DECLARATIONS.keySet().stream()
            .map(IntrinsicFunctions::simpleName)
            .collect(Collectors.toUnmodifiableSet());

    private IntrinsicFunctions() {}

    /// The intrinsic that replaces calls to this qualified function name, or `null` when the name
    /// is an ordinary function.
    public static IntrinsicId intrinsicFor(final String qualifiedName) {
        return qualifiedName == null ? null : DECLARATIONS.get(qualifiedName);
    }

    public static boolean isIntrinsicDeclaration(final String qualifiedName) {
        return qualifiedName != null && DECLARATIONS.containsKey(qualifiedName);
    }

    /// True when the simple name belongs to a standard-library intrinsic declaration. User code
    /// reusing the name must not be mistaken for that declaration.
    public static boolean isIntrinsicDeclarationName(final String simpleName) {
        return DECLARATION_SIMPLE_NAMES.contains(simpleName);
    }

    private static String simpleName(final String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }
}
