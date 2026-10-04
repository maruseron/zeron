package com.maruseron.zeron.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
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
        final var element = new TypeParameterDescriptor(Integer.MIN_VALUE, "Element");
        final var optionValue = new TypeParameterDescriptor(Integer.MIN_VALUE + 1, "OptionValue");
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
                new IntrinsicDefinition(IntrinsicId.ARRAY_LENGTH,
                        new IntrinsicSignature(typeParameters, java.util.List.of(array),
                                TypeDescriptor.ofInt(), false), "length"),
                new IntrinsicDefinition(IntrinsicId.ARRAY_READ,
                        new IntrinsicSignature(typeParameters,
                                java.util.List.of(array, TypeDescriptor.ofInt()), element, false), null),
                new IntrinsicDefinition(IntrinsicId.ARRAY_WRITE,
                        new IntrinsicSignature(typeParameters,
                                java.util.List.of(new ReferenceDescriptor(array), TypeDescriptor.ofInt(), element),
                    TypeDescriptor.ofUnit(), false), null)));
    }
}