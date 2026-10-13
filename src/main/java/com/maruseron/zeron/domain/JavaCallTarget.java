package com.maruseron.zeron.domain;

import java.util.Objects;
import java.util.List;

public record JavaCallTarget(String owner, String name, String descriptor, InvocationKind kind,
                             boolean varArgs, List<ExceptionMapping> exceptionMappings) {
    public JavaCallTarget {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(name);
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(kind);
        exceptionMappings = List.copyOf(exceptionMappings);
    }

    public JavaCallTarget(final String owner, final String name, final String descriptor,
                          final InvocationKind kind, final boolean varArgs) {
        this(owner, name, descriptor, kind, varArgs, List.of());
    }

    public record ExceptionMapping(String exceptionType, String effectType,
                                   String constructorName, String constructorDescriptor,
                                   boolean canonicalConstructor) {}

    public enum InvocationKind {
        STATIC,
        VIRTUAL,
        INTERFACE,
        CONSTRUCTOR
    }
}
