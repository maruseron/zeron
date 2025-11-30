package com.maruseron.zeron.analize;

import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

public record Bind(Token declaration,
                   int lvt,
                   TypeDescriptor type,
                   Width width,
                   boolean isInit,
                   boolean isFinal) {

    public Bind init() {
        return new Bind(declaration, lvt, type, width, true, isFinal);
    }

    public Bind withType(final TypeDescriptor type) {
        return new Bind(declaration, lvt, type, width, isInit, isFinal);
    }

    @Override
    public String toString() {
        return "{ type: " + type + ", final: " + isFinal + " }";
    }
}
