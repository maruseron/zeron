package com.maruseron.zeron.interpret;

import com.maruseron.zeron.domain.TypeDescriptor;

public record Bind(String name, TypeDescriptor typeDescriptor, Object value,
                   boolean isInitialized, boolean isFinal) {

    public Bind withValue(Object value) {
        return new Bind(name(), typeDescriptor(), value, true, isFinal());
    }
}
