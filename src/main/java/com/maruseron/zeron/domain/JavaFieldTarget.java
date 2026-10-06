package com.maruseron.zeron.domain;

import java.util.Objects;

public record JavaFieldTarget(String owner, String name, String descriptor) {
    public JavaFieldTarget {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(name);
        Objects.requireNonNull(descriptor);
    }
}
