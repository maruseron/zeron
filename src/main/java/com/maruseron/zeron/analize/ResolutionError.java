package com.maruseron.zeron.analize;

import com.maruseron.zeron.scan.Token;

public final class ResolutionError extends RuntimeException {
    public final Token token;

    ResolutionError(Token token, String message) {
        super(message);
        this.token = token;
    }
}