package com.maruseron.zeron.interpret;

import com.maruseron.zeron.scan.Token;

public final class RuntimeError extends RuntimeException {
    public final Token token;

    RuntimeError(Token token, String message) {
        super(message);
        this.token = token;
    }
}