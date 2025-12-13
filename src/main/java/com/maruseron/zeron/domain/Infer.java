package com.maruseron.zeron.domain;

public final class Infer implements TypeDescriptor {

    public static final Infer INSTANCE = new Infer();

    // singleton
    private Infer() {}

    @Override
    public String descriptor() {
        return "<Infer>";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Infer";
    }
}
