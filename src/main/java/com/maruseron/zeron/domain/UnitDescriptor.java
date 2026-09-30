package com.maruseron.zeron.domain;

public final class UnitDescriptor implements TypeDescriptor {

    public static final UnitDescriptor UNIT = new UnitDescriptor();

    // singleton
    private UnitDescriptor() {}

    @Override
    public String name() {
        return "Unit";
    }

    @Override
    public String toString() {
        return "TypeDescriptor.Unit";
    }
}
