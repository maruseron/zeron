package com.maruseron.zeron.scan;

import com.maruseron.zeron.diagnostic.SourceSpan;

import java.util.Objects;

public record Token(
        TokenType type,
        String lexeme,
        Object literal,
        SourceSpan span) {

    public Token {
        Objects.requireNonNull(type);
        Objects.requireNonNull(lexeme);
        Objects.requireNonNull(span);
    }

    public Token(final TokenType type, final String lexeme, final Object literal, final int line) {
        this(type, lexeme, literal, SourceSpan.line(null, Math.max(1, line)));
    }

    public int line() {
        return span.start().line();
    }

    @Override
    public String toString() {
        return "Token[" + type + " " + lexeme + ": " + literal + "]";
    }
}
