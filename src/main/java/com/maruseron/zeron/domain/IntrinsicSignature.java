package com.maruseron.zeron.domain;

import java.util.List;
import java.util.Objects;

public record IntrinsicSignature(List<TypeParameterDescriptor> typeParameters,
                                 List<TypeDescriptor> parameterTypes,
                                 TypeDescriptor returnType,
                                 boolean variadic) {
    public IntrinsicSignature {
        typeParameters = List.copyOf(typeParameters);
        parameterTypes = List.copyOf(parameterTypes);
        Objects.requireNonNull(returnType);
        if (variadic && parameterTypes.size() != 1) {
            throw new IllegalArgumentException("Variadic intrinsic signatures require one repeated parameter type.");
        }
    }
}