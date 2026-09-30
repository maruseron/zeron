package com.maruseron.zeron.interpret;

import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.BindingMutability;

public record Bind(String name, TypeDescriptor typeDescriptor, Object value,
                   boolean isInitialized, BindingMutability mutability) {

    public Bind withValue(Object value) {
        return new Bind(name(), typeDescriptor(), value, true, mutability());
    }
}
