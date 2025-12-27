package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.Objects;

public record Bind(Stmt declaration,
                   Token name,
                   int lvt,
                   TypeDescriptor type,
                   Width width,
                   boolean isInit,
                   boolean isFinal) {

    public Bind init() {
        return new Bind(declaration, name, lvt, type, width, true, isFinal);
    }

    public Bind withType(final TypeDescriptor type) {
        return new Bind(declaration, name, lvt, type, width, isInit, isFinal);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Bind b)) return false;

        return name.equals(b.name);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(declaration);
        result = 31 * result + Objects.hashCode(name);
        result = 31 * result + lvt;
        result = 31 * result + Objects.hashCode(type);
        result = 31 * result + Objects.hashCode(width);
        result = 31 * result + Boolean.hashCode(isInit);
        result = 31 * result + Boolean.hashCode(isFinal);
        return result;
    }

    @Override
    public String toString() {
        return "{ type: " + type + ", final: " + isFinal + " }";
    }
}
