package com.maruseron.zeron.domain;

public enum BindingMutability {
    IMMUTABLE,
    REASSIGNABLE;

    public boolean isReassignable() {
        return this == REASSIGNABLE;
    }
}