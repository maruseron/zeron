package com.maruseron.zeron.interpret;

import com.maruseron.zeron.scan.Token;

public final class BreakException extends RuntimeException {
    public final Token token;

    BreakException(Token token) {
        this.token = token;
    }
}