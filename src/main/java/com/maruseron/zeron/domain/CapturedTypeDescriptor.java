package com.maruseron.zeron.domain;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public record CapturedTypeDescriptor(int id,
                                     TypeDescriptor lowerBound,
                                     TypeDescriptor upperBound) implements TypeDescriptor {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    public CapturedTypeDescriptor {
        Objects.requireNonNull(lowerBound);
        Objects.requireNonNull(upperBound);
    }

    public static CapturedTypeDescriptor create(final TypeDescriptor lowerBound,
                                                final TypeDescriptor upperBound) {
        return new CapturedTypeDescriptor(NEXT_ID.incrementAndGet(), lowerBound, upperBound);
    }

    @Override
    public String name() {
        return "capture#" + id;
    }

    @Override
    public String descriptor() {
        return "#" + id + ":" + lowerBound.descriptor() + ".." + upperBound.descriptor();
    }
}
