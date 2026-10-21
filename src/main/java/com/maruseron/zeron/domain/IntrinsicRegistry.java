package com.maruseron.zeron.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class IntrinsicRegistry {
    private static final IntrinsicRegistry STANDARD = createStandard();

    private final Map<IntrinsicId, IntrinsicDefinition> definitions;
    private final Map<String, IntrinsicDefinition> properties;

    private IntrinsicRegistry(final Collection<IntrinsicDefinition> definitions) {
        final var byId = new LinkedHashMap<IntrinsicId, IntrinsicDefinition>();
        final var byProperty = new LinkedHashMap<String, IntrinsicDefinition>();
        for (final var definition : definitions) {
            if (byId.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate intrinsic ID: " + definition.id().stableName());
            }
            if (definition.propertyName() != null
                    && byProperty.putIfAbsent(definition.propertyName(), definition) != null) {
                throw new IllegalArgumentException(
                        "Duplicate intrinsic property: " + definition.propertyName());
            }
        }
        this.definitions = Map.copyOf(byId);
        this.properties = Map.copyOf(byProperty);
    }

    public static IntrinsicRegistry standard() {
        return STANDARD;
    }

    public static IntrinsicRegistry of(final Collection<IntrinsicDefinition> definitions) {
        return new IntrinsicRegistry(definitions);
    }

    public IntrinsicDefinition require(final IntrinsicId id) {
        final var definition = definitions.get(Objects.requireNonNull(id));
        if (definition == null) {
            throw new IllegalArgumentException("Unregistered intrinsic ID: " + id.stableName());
        }
        return definition;
    }

    public IntrinsicDefinition property(final String name) {
        return properties.get(name);
    }

    private static IntrinsicRegistry createStandard() {
        final var element = new TypeParameterDescriptor(Integer.MIN_VALUE, "Element", List.of(), false,
                TypeParameterDescriptor.Variance.INVARIANT, true, false, true);
        final var optionValue = new TypeParameterDescriptor(Integer.MIN_VALUE + 1, "OptionValue", List.of(), false,
                TypeParameterDescriptor.Variance.INVARIANT, true, false, true);
        final var array = TypeDescriptor.arrayOf(element);
        final var typeParameters = java.util.List.of(element);
        return new IntrinsicRegistry(java.util.List.of(
                new IntrinsicDefinition(IntrinsicId.ARRAY_LITERAL,
                        new IntrinsicSignature(typeParameters, java.util.List.of(element),
                                new ReferenceDescriptor(array), true), null),
                new IntrinsicDefinition(IntrinsicId.ARRAY_FILL,
                        new IntrinsicSignature(typeParameters,
                                java.util.List.of(TypeDescriptor.ofInt(), element.toNullable()),
                                new ReferenceDescriptor(TypeDescriptor.arrayOf(element.toNullable())), false), null),
                new IntrinsicDefinition(IntrinsicId.ARRAY_ALLOC,
                        new IntrinsicSignature(typeParameters, java.util.List.of(TypeDescriptor.ofInt()),
                                new ReferenceDescriptor(array), false), null),
                new IntrinsicDefinition(IntrinsicId.ARRAY_CLEAR_SLOT,
                        new IntrinsicSignature(typeParameters,
                                java.util.List.of(new ReferenceDescriptor(array), TypeDescriptor.ofInt()),
                                TypeDescriptor.ofUnit(), false), null),
                new IntrinsicDefinition(IntrinsicId.OPTION_UNWRAP_SOME,
                        new IntrinsicSignature(java.util.List.of(optionValue),
                                java.util.List.of(TypeDescriptor.genericOf(
                                        TypeDescriptor.ofName("zeron.lang.Option"), optionValue)),
                                optionValue, false), null),
                new IntrinsicDefinition(IntrinsicId.INT_TO_FLOAT,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofInt()),
                                TypeDescriptor.ofFloat(), false), null),
                new IntrinsicDefinition(IntrinsicId.INT_TO_LONG,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofInt()),
                                TypeDescriptor.ofLong(), false), null),
                new IntrinsicDefinition(IntrinsicId.FLOAT_TO_INT_OPTION,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofFloat()),
                                TypeDescriptor.genericOf(TypeDescriptor.ofName("zeron.lang.Option"),
                                        TypeDescriptor.ofInt()), false), null),
                new IntrinsicDefinition(IntrinsicId.LONG_TO_INT_OPTION,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofLong()),
                                TypeDescriptor.genericOf(TypeDescriptor.ofName("zeron.lang.Option"),
                                        TypeDescriptor.ofInt()), false), null),
                new IntrinsicDefinition(IntrinsicId.ARRAY_LENGTH,
                        new IntrinsicSignature(typeParameters, java.util.List.of(array),
                                TypeDescriptor.ofInt(), false), "length"),
                new IntrinsicDefinition(IntrinsicId.ARRAY_READ,
                        new IntrinsicSignature(typeParameters,
                                java.util.List.of(array, TypeDescriptor.ofInt()), element, false), null),
                new IntrinsicDefinition(IntrinsicId.ARRAY_WRITE,
                        new IntrinsicSignature(typeParameters,
                                java.util.List.of(new ReferenceDescriptor(array), TypeDescriptor.ofInt(), element),
                    TypeDescriptor.ofUnit(), false), null),
                new IntrinsicDefinition(IntrinsicId.BYTE_ARRAY_ALLOC,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofInt()),
                                new ReferenceDescriptor(TypeDescriptor.ofByteArray()), false), null),
                new IntrinsicDefinition(IntrinsicId.BYTE_ARRAY_LENGTH,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofByteArray()),
                                TypeDescriptor.ofInt(), false), null),
                new IntrinsicDefinition(IntrinsicId.BYTE_ARRAY_READ,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofByteArray(), TypeDescriptor.ofInt()),
                                TypeDescriptor.ofByte(), false), null),
                new IntrinsicDefinition(IntrinsicId.BYTE_ARRAY_WRITE,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(new ReferenceDescriptor(TypeDescriptor.ofByteArray()),
                                        TypeDescriptor.ofInt(), TypeDescriptor.ofByte()),
                                TypeDescriptor.ofUnit(), false), null),
                new IntrinsicDefinition(IntrinsicId.INT_TO_BYTE,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofInt()),
                                TypeDescriptor.ofByte(), false), null),
                new IntrinsicDefinition(IntrinsicId.BYTE_TO_INT,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofByte()),
                                TypeDescriptor.ofInt(), false), null),
                new IntrinsicDefinition(IntrinsicId.CHARACTER_CODING_EXCEPTION_TO_IO_EXCEPTION,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofName(
                                        "java.nio.charset.CharacterCodingException")),
                                TypeDescriptor.ofName("java.io.IOException"), false), null),
                new IntrinsicDefinition(IntrinsicId.STRING_CODE_POINT_LENGTH,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofString().toNullable()),
                                TypeDescriptor.ofInt(), false), null),
                new IntrinsicDefinition(IntrinsicId.STRING_CODE_POINT_AT,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofString().toNullable(), TypeDescriptor.ofInt()),
                                TypeDescriptor.ofInt(), false), null),
                new IntrinsicDefinition(IntrinsicId.STRING_SUBSTRING_BY_CODE_POINTS,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofString().toNullable(),
                                        TypeDescriptor.ofInt(), TypeDescriptor.ofInt()),
                                TypeDescriptor.ofString().toNullable(), false), null),
                new IntrinsicDefinition(IntrinsicId.STRING_INDEX_OF_CODE_POINT,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofString().toNullable(), TypeDescriptor.ofChar()),
                                TypeDescriptor.ofInt(), false), null),
                new IntrinsicDefinition(IntrinsicId.STRING_FIRST_MALFORMED_OFFSET,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofString().toNullable()),
                                TypeDescriptor.ofInt(), false), null),
                new IntrinsicDefinition(IntrinsicId.STRING_CODE_POINT_AT_OFFSET,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofString().toNullable(), TypeDescriptor.ofInt()),
                                TypeDescriptor.ofChar(), false), null),
                new IntrinsicDefinition(IntrinsicId.STRING_CODE_POINT_WIDTH_AT_OFFSET,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofString().toNullable(), TypeDescriptor.ofInt()),
                                TypeDescriptor.ofInt(), false), null),
                new IntrinsicDefinition(IntrinsicId.CHAR_TO_INT,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofChar()), TypeDescriptor.ofInt(), false), null),
                new IntrinsicDefinition(IntrinsicId.INT_TO_CHAR,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofInt()), TypeDescriptor.ofChar(), false), null),
                new IntrinsicDefinition(IntrinsicId.CHAR_TO_STRING,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofChar()), TypeDescriptor.ofString(), false), null),
                new IntrinsicDefinition(IntrinsicId.BIG_INTEGER_FROM_LITERAL_TEXT,
                        new IntrinsicSignature(java.util.List.of(),
                                java.util.List.of(TypeDescriptor.ofString()),
                                new ReferenceDescriptor(TypeDescriptor.ofName("java.math.BigInteger")),
                                false), null)));
    }
}