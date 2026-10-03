package com.maruseron.zeron.domain;

import java.util.List;
import java.util.Objects;

public record ResolvedIntrinsicOperation(IntrinsicId id,
                                        List<TypeDescriptor> parameterTypes,
                                        TypeDescriptor resultType) {
    public ResolvedIntrinsicOperation {
        Objects.requireNonNull(id);
        parameterTypes = List.copyOf(parameterTypes);
        Objects.requireNonNull(resultType);
    }
}