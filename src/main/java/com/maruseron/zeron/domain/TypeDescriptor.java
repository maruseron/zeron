package com.maruseron.zeron.domain;

public record TypeDescriptor(String descriptor) {

    public boolean isNullable() {
        return descriptor().endsWith("?");
    }

    public boolean isMutable() {
        return descriptor().startsWith("&");
    }

    public boolean isArray() {
        return descriptor().endsWith("[]");
    }
}
