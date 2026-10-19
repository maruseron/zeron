package com.maruseron.zeron.domain;

import java.util.List;
import java.util.Objects;

public record TypeConstructorDescriptor(NominalDescriptor baseType,
                                        List<TypeDescriptor> arguments) implements TypeDescriptor {
    public TypeConstructorDescriptor {
        Objects.requireNonNull(baseType);
        arguments = List.copyOf(arguments);
        if (arguments.stream().filter(InferDescriptor.class::isInstance).count() != 1) {
            throw new IllegalArgumentException("A constructor pattern must contain exactly one open slot.");
        }
    }

    @Override
    public String name() {
        return baseType.name();
    }

    @Override
    public String descriptor() {
        return "^" + baseType.descriptor() + " "
                + arguments.stream().map(TypeDescriptor::descriptor)
                        .collect(java.util.stream.Collectors.joining(" "));
    }

    public TypeDescriptor apply(final TypeDescriptor argument) {
        var replaced = false;
        final var applied = new java.util.ArrayList<TypeDescriptor>(arguments.size());
        for (final var typeArgument : arguments) {
            if (typeArgument instanceof InferDescriptor) {
                applied.add(argument);
                replaced = true;
            } else {
                applied.add(typeArgument);
            }
        }
        if (!replaced) throw new IllegalStateException("Constructor pattern has no open slot.");
        if (baseType.name().equals("Array")) {
            if (applied.size() != 1) throw new IllegalStateException("Array takes one type argument.");
            return TypeDescriptor.arrayOf(applied.getFirst());
        }
        return TypeDescriptor.genericOf(baseType, applied);
    }
}
