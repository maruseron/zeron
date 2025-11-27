package com.maruseron.zeron.interpret;

import java.util.List;

public interface ZeronCallable {
    int arity();
    Object call(final Interpreter interpreter, final List<Object> arguments);
}
