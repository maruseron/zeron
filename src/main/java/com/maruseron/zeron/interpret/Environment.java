package com.maruseron.zeron.interpret;

import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.HashMap;
import java.util.Map;

public final class Environment {
    final Environment enclosing;
    private final Map<String, Bind> values = new HashMap<>();

    Environment() {
        enclosing = null;
    }

    Environment(Environment enclosing) {
        this.enclosing = enclosing;
    }

    Bind get(final Token name) {
        if (values.containsKey(name.lexeme())) {
            return values.get(name.lexeme());
        }

        if (enclosing != null) return enclosing.get(name);

        throw new RuntimeError(name, "Undefined variable '" + name.lexeme() + "'.");
    }

    void assign(Token name, Object value) {
        if (values.containsKey(name.lexeme())) {
            final var entry = values.get(name.lexeme());
            if (entry.isFinal()) {
                throw new RuntimeError(name,
                        "Cannot reassign final variable '" + name.lexeme() + "'.");
            }
            values.put(name.lexeme(), entry.withValue(value));
            return;
        }

        if (enclosing != null) {
            enclosing.assign(name, value);
            return;
        }

        throw new RuntimeError(name, "Undefined variable '" + name.lexeme() + "'.");
    }

    void define(final String name, final TypeDescriptor typeDescriptor, final Object value,
                final boolean isInitialized, final boolean isFinal) {
        values.put(name, new Bind(name, typeDescriptor, value, isInitialized, isFinal));
    }
}
