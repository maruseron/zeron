package com.maruseron.zeron.domain;

import java.util.Objects;

public record IntrinsicDefinition(IntrinsicId id,
                                  IntrinsicSignature signature,
                                  String propertyName) {
    public IntrinsicDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(signature);
    }
}