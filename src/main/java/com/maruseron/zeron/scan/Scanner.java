package com.maruseron.zeron.scan;

import com.maruseron.zeron.Zeron;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.maruseron.zeron.scan.TokenType.*;
import static java.util.Map.entry;

public final class Scanner {
    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private int start = 0;
    private int current = 0;
    private int line = 1;

    private static final Map<String, TokenType> keywords = Map.ofEntries(
            entry("and",         AND),
            entry("break",       BREAK),
            entry("class",       CLASS),
            entry("contract",    CONTRACT),
            entry("constructor", CONSTRUCTOR),
            entry("else",        ELSE),
            entry("false",       FALSE),
            entry("fn",          FN),
            entry("for",         FOR),
            entry("get",         GET),
            entry("if",          IF),
            entry("implement",   IMPLEMENT),
            entry("in",          IN),
            entry("is",          IS),
            entry("let",         LET),
            entry("loop",        LOOP),
            entry("match",       MATCH),
            entry("mut",         MUT),
            entry("not",         NOT),
            entry("null",        NULL),
            entry("or",          OR),
            entry("public",      PUBLIC),
            entry("print",       PRINT),
            entry("private",     PRIVATE),
            entry("return",      RETURN),
            entry("set",         SET),
            entry("then",        THEN),
            entry("this",        THIS),
            entry("true",        TRUE),
            entry("type",        TYPE),
            entry("typeof",      TYPEOF),
            entry("unit",        UNIT),
            entry("until",       UNTIL),
            entry("while",       WHILE));

    Scanner (String source) {
        this.source = source;
    }

    public static Scanner from(final String source) {
        return new Scanner(source);
    }

    public List<Token> scanTokens() {
        while (!isAtEnd()) {
            start = current;
            scanToken();
        }

        tokens.add(new Token(EOF, "", null, line));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            // single
            case '(' -> addToken(LEFT_PAREN);
            case ')' -> addToken(RIGHT_PAREN);
            case '{' -> addToken(LEFT_BRACE);
            case '}' -> addToken(RIGHT_BRACE);
            case '[' -> addToken(LEFT_BRACKET);
            case ']' -> addToken(RIGHT_BRACKET);
            case ',' -> addToken(COMMA);
            case ';' -> addToken(SEMICOLON);
            case '|' -> addToken(PIPE);
            case '&' -> addToken(AMPERSAND);

            // multiple
            case '.' -> addToken(match('.') ? DOT_DOT : DOT);
            case ':' -> addToken(match(':') ? COLON_COLON : COLON);
            case '-' -> addToken(
                    match('=') ? MINUS_EQUAL :
                    match('>') ? ARROW       : MINUS);
            case '+' -> addToken(match('=') ? PLUS_EQUAL : PLUS);
            case '/' -> {
                // single line comment
                if (match('/')) {
                    // matched a //
                    while (peek() != '\n' && !isAtEnd()) advance();
                } else if (match('*')) {
                    // matched a /*
                    while (peek() != '*' && peekNext() != '/' && !isAtEnd()) {
                        if (advance() == '\n') line++;
                    }
                    advance();
                    advance();
                } else {
                    // matched /= ? if not just /
                    addToken(match('=') ? SLASH_EQUAL : SLASH);
                }
            }
            case '*' -> addToken(match('=') ? STAR_EQUAL : STAR);
            case '!' -> addToken(match('=') ? BANG_EQUAL : BANG);
            case '?' -> addToken(match('.') ? HUH_DOT : HUH);
            case '=' -> addToken(match('=') ? EQUAL_EQUAL : EQUAL);
            case '>' -> addToken(match('=') ? GREATER_EQUAL : GREATER);
            case '<' -> addToken(match('=') ? LESS_EQUAL : LESS);

            // whitespace
            case ' ', '\r', '\t' -> {}
            case '\n' -> {
                // addNewline();
                line++;
            }

            case '"' -> string();
            case char _ when isDigit(c) -> number();
            case char _ when isAlpha(c) -> identifier();
            default -> Zeron.error(line, "Unexpected character: " + c);
        }
    }

    private void string() {
        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') line++;
            advance();
        }

        if (isAtEnd()) {
            Zeron.error(line, "Unterminated string.");
            return;
        }

        // close the string
        advance();

        addToken(STRING, source.substring(start + 1, current - 1));
    }

    private void number() {
        while (isDigit(peek())) advance();

        var isDecimal = false;
        if (peek() == '.' && isDigit(peekNext())) {
            isDecimal = true;
            // consume the .
            do advance();
            while (isDigit(peek()));
        }

        if (isDecimal) {
            addToken(DOUBLE, Double.parseDouble(source.substring(start, current)));
        } else {
            addToken(INT, Integer.parseInt(source.substring(start, current)));
        }
    }

    private void identifier() {
        while (isAlphanumeric(peek())) advance();

        final var type = Optional.ofNullable(
                keywords.get(source.substring(start, current)));

        type.ifPresentOrElse(this::addToken, () -> addToken(IDENTIFIER));
    }

    private boolean match(final char expected) {
        if (isAtEnd()) return false;
        if (source.charAt(current) != expected) return false;

        current++;
        return true;
    }

    private char peek() {
        if (isAtEnd()) return '\0';
        return source.charAt(current);
    }

    private char peekNext() {
        final var lookingFor = current + 1;
        if (lookingFor >= source.length()) return '\0';
        return source.charAt(lookingFor);
    }

    private boolean isAlpha(final char c) {
        return (c >= 'a' && c <= 'z') ||
               (c >= 'A' && c <= 'Z') ||
                c == '_';
    }

    private boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isAlphanumeric(final char c) {
        return isAlpha(c) || isDigit(c);
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private char advance() {
        return source.charAt(current++);
    }

    private void addNewline() {
        tokens.add(new Token(NEWLINE, "", null, line));
    }

    private void addToken(final TokenType type) {
        addToken(type, null);
    }

    private void addToken(final TokenType type, final Object literal) {
        tokens.add(new Token(
                type,
                source.substring(start, current),
                literal,
                line));
    }
}
