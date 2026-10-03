package com.maruseron.zeron.domain;

import java.util.Objects;

public record JavaCallTarget(String owner, String name, String descriptor, InvocationKind kind,
                             boolean varArgs) {
    public JavaCallTarget {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(name);
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(kind);
    }

    public enum InvocationKind {
        STATIC,
        VIRTUAL,
        INTERFACE,
        CONSTRUCTOR
    }
}
