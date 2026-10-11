package com.maruseron.zeron.domain;

import java.util.Objects;

public record JavaFieldTarget(String owner, String name, String descriptor,
                              boolean isStatic, boolean isFinal) {
    public JavaFieldTarget {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(name);
        Objects.requireNonNull(descriptor);
    }
}
