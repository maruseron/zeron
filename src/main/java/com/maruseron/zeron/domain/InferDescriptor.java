package com.maruseron.zeron.domain;

public final class InferDescriptor implements TypeDescriptor {

    public static final InferDescriptor INSTANCE = new InferDescriptor();

    // singleton
    private InferDescriptor() {}

    @Override
    public String name() {
        return "Infer";
    }

    @Override
    public String descriptor() {
        return "<Infer>";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Infer";
    }
}
