package com.maruseron.zeron.interpret;

import com.maruseron.zeron.ast.Stmt;

import java.util.List;

public class ZeronFunction implements ZeronCallable {
    private final Stmt.Function declaration;

    public ZeronFunction(final Stmt.Function declaration) {
        this.declaration = declaration;
    }

    @Override public int arity() { return declaration.parameters().size(); }

    @Override public Object call(Interpreter interpreter, List<Object> arguments) {
        final var environment = new Environment(interpreter.globals);
        for (var i = 0; i < declaration.parameters().size(); i++) {

        }
        return null;
    }
}
