package com.maruseron.zeron.domain;

import java.util.Objects;

public record TypeProjectionDescriptor(Kind kind, TypeDescriptor bound) implements TypeDescriptor {
    public enum Kind {
        OUT,
        IN
    }

    public TypeProjectionDescriptor {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(bound);
    }

    @Override
    public String name() {
        return bound.name();
    }

    @Override
    public String descriptor() {
        return (kind == Kind.OUT ? "out:" : "in:") + bound.descriptor();
    }
}
