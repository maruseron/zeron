package com.maruseron.zeron.domain;

/**
 * Compiler support for anonymous existentials that survive storage.
 *
 * <p>The source type remains an applied type containing a marked anonymous parameter. A value
 * whose source type directly stores a holder-backed existential uses the reserved holder ABI at
 * runtime; parameter-position anonymous existentials keep their hidden-parameter ABI.
 */
public final class StoredExistentialSupport {
    public static final String HOLDER_PACKAGE = "zeron.lang.existential";
    public static final String HOLDER_CLASS = HOLDER_PACKAGE + ".ExistsN";

    public static String holderClassName(final int slotCount) {
        if (slotCount < 1) throw new IllegalArgumentException("A holder needs at least one slot.");
        return slotCount <= 3 ? HOLDER_PACKAGE + ".Exists" + slotCount : HOLDER_CLASS;
    }

    public static int holderSlotCount(final TypeDescriptor type) {
        var slots = 0;
        for (final var binder : directBinders(type)) {
            if (!needsHolder(binder)) continue;
            slots += binder.isReified() ? 1 : 0;
            slots += binder.bounds().size();
        }
        return slots;
    }

    public static TypeDescriptor holderType(final TypeDescriptor sourceType) {
        return TypeDescriptor.ofName(holderClassName(holderSlotCount(sourceType)));
    }

    private StoredExistentialSupport() {
    }

    /** Whether this exact type argument is an anonymous existential in a storing position. */
    public static boolean isStoredBinder(final TypeDescriptor type) {
        return type instanceof TypeParameterDescriptor parameter
                && parameter.isStoredExistential();
    }

    /** Whether this binder needs a wrapper to preserve a descriptor or evidence after storage. */
    public static boolean needsHolder(final TypeParameterDescriptor parameter) {
        return parameter.isStoredExistential()
                && (parameter.isReified() || !parameter.bounds().isEmpty());
    }

    /** Whether this applied type is itself represented by a holder at runtime. */
    public static java.util.List<TypeParameterDescriptor> directBinders(final TypeDescriptor type) {
        final var base = type instanceof NullableDescriptor nullable ? nullable.baseType() : type;
        if (!(base instanceof GenericDescriptor generic)) return java.util.List.of();
        return generic.typeParameters().stream()
                .filter(TypeParameterDescriptor.class::isInstance)
                .map(TypeParameterDescriptor.class::cast)
                .filter(TypeParameterDescriptor::isStoredExistential)
                .toList();
    }

    public static boolean requiresHolder(final TypeDescriptor type) {
        return switch (type) {
            case NullableDescriptor nullable -> requiresHolder(nullable.baseType());
            case ReferenceDescriptor reference -> requiresHolder(reference.baseType());
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(argument -> argument instanceof TypeParameterDescriptor parameter
                            && needsHolder(parameter));
            default -> false;
        };
    }

    /** Whether this type contains any stored anonymous existential, including nested positions. */
    public static boolean containsStoredBinder(final TypeDescriptor type) {
        return switch (type) {
            case TypeParameterDescriptor parameter -> parameter.isStoredExistential();
            case NullableDescriptor nullable -> containsStoredBinder(nullable.baseType());
            case ReferenceDescriptor reference -> containsStoredBinder(reference.baseType());
            case ArrayDescriptor array -> containsStoredBinder(array.elementType());
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .anyMatch(StoredExistentialSupport::containsStoredBinder);
            case AppliedConstructorDescriptor applied -> containsStoredBinder(applied.argument());
            case TypeConstructorDescriptor constructor -> constructor.arguments().stream()
                    .anyMatch(StoredExistentialSupport::containsStoredBinder);
            case TypeProjectionDescriptor projection -> containsStoredBinder(projection.bound());
            case CapturedTypeDescriptor capture -> containsStoredBinder(capture.lowerBound())
                    || containsStoredBinder(capture.upperBound());
            case FunctionDescriptor function -> (function.receiverType() != null
                    && containsStoredBinder(function.receiverType()))
                    || containsStoredBinder(function.returnType())
                    || function.parameters().stream().anyMatch(StoredExistentialSupport::containsStoredBinder);
            default -> false;
        };
    }

    public static boolean isHolderType(final TypeDescriptor type) {
        return switch (type) {
            case NominalDescriptor nominal -> {
                final var simpleName = nominal.name().substring(nominal.name().lastIndexOf('.') + 1);
                yield simpleName.equals("ExistsN") || simpleName.equals("Exists1")
                        || simpleName.equals("Exists2") || simpleName.equals("Exists3");
            }
            case NullableDescriptor nullable -> isHolderType(nullable.baseType());
            default -> false;
        };
    }

    /**
     * The storage representation of a source type. Direct holder-backed applications become the
     * reserved holder; nested stored existentials keep their enclosing applied type because the
     * holder is carried by the nested value, not by its container.
     */
    public static TypeDescriptor storageType(final TypeDescriptor type) {
        if (!requiresHolder(type)) return type;
        return type.isNullable() ? holderType(type).toNullable() : holderType(type);
    }

    /**
     * The value representation of a type at a generic boundary. A direct holder-backed
     * application becomes its holder; the arguments of an enclosing generic keep their own
     * representations. This is used when a generic payload such as {@code Option<Box<: Render>>}
     * has already stored a holder in an erased Java slot.
     */
    public static TypeDescriptor runtimeRepresentation(final TypeDescriptor type) {
        return switch (type) {
            case NullableDescriptor nullable ->
                    runtimeRepresentation(nullable.baseType()).toNullable();
            case ReferenceDescriptor reference ->
                    new ReferenceDescriptor(runtimeRepresentation(reference.baseType()));
            case ArrayDescriptor array ->
                    TypeDescriptor.arrayOf(runtimeRepresentation(array.elementType()));
            case GenericDescriptor generic -> requiresHolder(generic)
                    ? holderType(generic)
                    : TypeDescriptor.genericOf(generic.baseType(),
                            generic.typeParameters().stream()
                                    .map(StoredExistentialSupport::runtimeRepresentation)
                                    .toList());
            default -> type;
        };
    }
}
