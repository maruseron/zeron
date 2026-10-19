package com.maruseron.zeron.domain;

import java.util.Objects;

public record AppliedConstructorDescriptor(TypeParameterDescriptor constructor,
                                           TypeDescriptor argument) implements TypeDescriptor {
    public AppliedConstructorDescriptor {
        Objects.requireNonNull(constructor);
        Objects.requireNonNull(argument);
        if (!constructor.isHigherKinded()) {
            throw new IllegalArgumentException("Only higher-kinded parameters can be applied.");
        }
    }

    @Override
    public String name() {
        return constructor.name();
    }

    @Override
    public String descriptor() {
        return "^" + constructor.descriptor() + " " + argument.descriptor();
    }
}
