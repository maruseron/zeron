package com.maruseron.zeron.diagnostic;

import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

public final class AnsiSyntaxHighlighter {
    private AnsiSyntaxHighlighter() {}

    public static String highlight(final String source) {
        final var tokens = Scanner.from(source).scanWithDiagnostics().tokens();
        final var output = new StringBuilder();
        var copiedThrough = 0;
        for (final var token : tokens) {
            if (token.type() == TokenType.EOF) continue;
            final var start = token.span().start().offset();
            final var end = token.span().end().offset();
            if (start == null || end == null || start < copiedThrough || end > source.length()) continue;

            output.append(source, copiedThrough, start);
            final var color = colorFor(token.type());
            final var text = source.substring(start, end);
            output.append(color == null ? text : AnsiColor.apply(text, color));
            copiedThrough = end;
        }
        output.append(source, copiedThrough, source.length());
        return output.toString();
    }

    private static String colorFor(final TokenType type) {
        return switch (type) {
            case STRING, TRUE, FALSE, NULL -> "32";
            case INT, DOUBLE -> "36";
            case LINE_COMMENT, BLOCK_COMMENT -> "90";
            case AND, AS, BREAK, CASE, CLASS, CONTRACT, CONSTRUCTOR, CONTINUE, DEFAULT, ELSE,
                    EFFECT, EXTENSION, EXTERNAL, FN, FOR, GET, HANDLE, IF, IMPLEMENT, IMPORT,
                    IN, IS, LET, LOOP, MATCH, MUT, NOT, NAMESPACE, OR, PACKAGE, PATTERN,
                    PRIVATE, PROPERTY, PUBLIC, RAISE, RAISES, RETURN, SEALED, SET, STATIC,
                    THEN, THIS, TYPE, TYPEOF, UNTIL, WITNESS, WHILE -> "35";
            case LEFT_PAREN, RIGHT_PAREN, LEFT_BRACE, RIGHT_BRACE, LEFT_BRACKET, RIGHT_BRACKET,
                    COMMA, SEMICOLON, TILDE, DOT, DOT_DOT, ELLIPSIS, COLON, COLON_COLON,
                    MINUS, MINUS_EQUAL, ARROW, PLUS, PLUS_EQUAL, PERCENT, PERCENT_EQUAL,
                    SLASH, SLASH_EQUAL, STAR, STAR_EQUAL, PIPE, PIPE_EQUAL, AMPERSAND,
                    AMPERSAND_EQUAL, CARET, CARET_EQUAL, SHIFT_LEFT, SHIFT_LEFT_EQUAL,
                    SHIFT_RIGHT, SHIFT_RIGHT_EQUAL, UNSIGNED_SHIFT_RIGHT,
                    UNSIGNED_SHIFT_RIGHT_EQUAL, BANG, BANG_EQUAL, HUH, HUH_DOT, HUH_HUH,
                    HUH_HUH_EQUAL, EQUAL, EQUAL_EQUAL, EQUAL_EQUAL_EQUAL, GREATER,
                    GREATER_EQUAL, LESS, LESS_EQUAL -> "33";
            default -> null;
        };
    }
}
