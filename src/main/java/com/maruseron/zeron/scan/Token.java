package com.maruseron.zeron.scan;

public record Token(
        TokenType type,
        String lexeme,
        Object literal,
        int line) {

    @Override
    public String toString() {
        return "Token[" + type + " " + lexeme + ": " + literal + "]";
    }
}
