package com.maruseron.zeron.interpret;

import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.BindingMutability;
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
            var entry = values.get(name.lexeme());
            if (!entry.isInitialized()) {
                throw new RuntimeError(name,
                        "Attempted to read variable '" +
                        name.lexeme() + "' before initialization.");
            }
            return entry;
        }

        if (enclosing != null) return enclosing.get(name);

        throw new RuntimeError(name, "Undefined variable '" + name.lexeme() + "'.");
    }

    void assign(Token name, Object value) {
        if (values.containsKey(name.lexeme())) {
            final var entry = values.get(name.lexeme());
            if (!entry.mutability().isReassignable()) {
                throw new RuntimeError(name,
                        "Cannot reassign immutable binding '" + name.lexeme() + "'.");
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
            final boolean isInitialized, final BindingMutability mutability) {
        values.put(name, new Bind(name, typeDescriptor, value, isInitialized, mutability));
    }
}
