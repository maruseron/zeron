package com.maruseron.zeron.domain;

public interface Mutable {
    // T -> &T
    Nullable toMutable();
    boolean isMutable();
}
