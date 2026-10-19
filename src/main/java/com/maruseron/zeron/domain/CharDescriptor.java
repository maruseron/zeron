package com.maruseron.zeron.domain;

public final class CharDescriptor implements TypeDescriptor {
    public static final CharDescriptor CHAR = new CharDescriptor();

    private CharDescriptor() {}

    @Override
    public String name() {
        return "Char";
    }
}
