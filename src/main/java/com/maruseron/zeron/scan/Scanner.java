package com.maruseron.zeron.scan;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.Severity;
import com.maruseron.zeron.diagnostic.SourcePosition;
import com.maruseron.zeron.diagnostic.SourceSpan;

import java.math.BigInteger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.maruseron.zeron.scan.TokenType.*;
import static java.util.Map.entry;

public final class Scanner {
    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int start = 0;
    private int current = 0;
    private int line = 1;
    private int column = 1;
    private final String sourcePath;
    private SourcePosition tokenStart;
    private ScanResult scanResult;

    private static final Map<String, TokenType> keywords = Map.ofEntries(
            entry("as",          AS),
            entry("and",         AND),
            entry("break",       BREAK),
            entry("case",        CASE),
            entry("class",       CLASS),
            entry("contract",    CONTRACT),
            entry("constructor", CONSTRUCTOR),
            entry("continue",    CONTINUE),
            entry("default",     DEFAULT),
            entry("else",        ELSE),
            entry("effect",      EFFECT),
            entry("extension",   EXTENSION),
            entry("external",    EXTERNAL),
            entry("false",       FALSE),
            entry("fn",          FN),
            entry("for",         FOR),
            entry("handle",      HANDLE),
            entry("if",          IF),
            entry("implement",   IMPLEMENT),
            entry("import",      IMPORT),
            entry("in",          IN),
            entry("is",          IS),
            entry("let",         LET),
            entry("loop",        LOOP),
            entry("match",       MATCH),
            entry("mut",         MUT),
            entry("not",         NOT),
            entry("null",        NULL),
            entry("namespace",    NAMESPACE),
            entry("object",      OBJECT),
            entry("or",          OR),
            entry("package",     PACKAGE),
            entry("pattern",     PATTERN),
            entry("property",    PROPERTY),
            entry("public",      PUBLIC),
            entry("private",     PRIVATE),
            entry("raise",       RAISE),
            entry("raises",      RAISES),
            entry("throws",      THROWS),
            entry("return",      RETURN),
            entry("sealed",      SEALED),
            entry("static",       STATIC),
            entry("then",        THEN),
            entry("this",        THIS),
            entry("true",        TRUE),
            entry("type",        TYPE),
            entry("typeof",      TYPEOF),
            entry("until",       UNTIL),
            entry("using",       USING),
            entry("witness",     WITNESS),
            entry("while",       WHILE));

    Scanner(final String source, final String sourcePath) {
        this.source = source;
        this.sourcePath = sourcePath;
    }

    public static Scanner from(final String source) {
        return from(source, null);
    }

    public static Scanner from(final String source, final String sourcePath) {
        return new Scanner(source, sourcePath);
    }

    public ScanResult scanWithDiagnostics() {
        if (scanResult != null) return scanResult;
        while (!isAtEnd()) {
            start = current;
            tokenStart = position();
            scanToken();
        }

        final var eof = position();
        tokens.add(new Token(EOF, "", null, new SourceSpan(sourcePath, eof, eof)));
        scanResult = new ScanResult(tokens, diagnostics);
        return scanResult;
    }

    public List<Token> scanTokens() {
        final var result = scanWithDiagnostics();
        result.diagnostics().forEach(Zeron::reportParseDiagnostic);
        return result.tokens();
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
            case '|' -> addToken(match('=') ? PIPE_EQUAL : PIPE);
            case '&' -> addToken(match('=') ? AMPERSAND_EQUAL : AMPERSAND);
            case '^' -> addToken(match('=') ? CARET_EQUAL : CARET);
            case '~' -> addToken(TILDE);

            // multiple
            case '.' -> {
                if (match('.')) addToken(match('.') ? ELLIPSIS : DOT_DOT);
                else addToken(DOT);
            }
            case ':' -> addToken(match(':') ? COLON_COLON : COLON);
            case '-' -> addToken(
                    match('=') ? MINUS_EQUAL :
                    match('>') ? ARROW       : MINUS);
            case '+' -> addToken(match('=') ? PLUS_EQUAL : PLUS);
            case '%' -> addToken(match('=') ? PERCENT_EQUAL : PERCENT);
            case '/' -> {
                // single line comment
                if (match('/')) {
                    while (peek() != '\n' && peek() != '\r' && !isAtEnd()) advance();
                    addToken(LINE_COMMENT);
                } else if (match('*')) {
                    while (!isAtEnd() && !(peek() == '*' && peekNext() == '/')) {
                        advance();
                    }
                    if (isAtEnd()) {
                        addToken(BLOCK_COMMENT);
                        recordDiagnostic(DiagnosticCatalog.UNTERMINATED_BLOCK_COMMENT,
                                span(tokenStart, position()), "Unterminated block comment.");
                    } else {
                        advance();
                        advance();
                        addToken(BLOCK_COMMENT);
                    }
                } else {
                    // matched /= ? if not just /
                    addToken(match('=') ? SLASH_EQUAL : SLASH);
                }
            }
            case '*' -> addToken(match('=') ? STAR_EQUAL : STAR);
            case '!' -> addToken(match('=') ? BANG_EQUAL : BANG);
            case '?' -> {
                if (match('.')) addToken(HUH_DOT);
                else if (match('?')) addToken(match('=') ? HUH_HUH_EQUAL : HUH_HUH);
                else addToken(HUH);
            }
            case '=' -> {
                if (match('=')) addToken(match('=') ? EQUAL_EQUAL_EQUAL : EQUAL_EQUAL);
                else addToken(EQUAL);
            }
            case '>' -> {
                if (source.startsWith(">>=", current)) {
                    advance();
                    advance();
                    advance();
                    addToken(UNSIGNED_SHIFT_RIGHT_EQUAL);
                } else if (source.startsWith(">=", current)) {
                    advance();
                    advance();
                    addToken(SHIFT_RIGHT_EQUAL);
                } else {
                    addToken(match('=') ? GREATER_EQUAL : GREATER);
                }
            }
            case '<' -> {
                if (match('<')) addToken(match('=') ? SHIFT_LEFT_EQUAL : SHIFT_LEFT);
                else addToken(match('=') ? LESS_EQUAL : LESS);
            }

            // whitespace
            case ' ', '\r', '\t' -> {}
            case '\n' -> {
                // addNewline();
            }

            case '"' -> string();
            case '\'' -> character();
            default -> {
                if (isDigit(c)) number();
                else if (isAlpha(c)) identifier();
                else recordDiagnostic(DiagnosticCatalog.UNEXPECTED_CHARACTER,
                        span(tokenStart, position()), "Unexpected character: " + c);
            }
        }
    }

    private void string() {
        while (peek() != '"' && !isAtEnd()) {
            advance();
        }

        if (isAtEnd()) {
            recordDiagnostic(DiagnosticCatalog.UNTERMINATED_STRING,
                    span(tokenStart, position()), "Unterminated string.");
            addToken(STRING, source.substring(start + 1, current));
            return;
        }

        // close the string
        advance();

        addToken(STRING, source.substring(start + 1, current - 1));
    }

    private void character() {
        var valid = true;
        var closed = false;
        var scalarCount = 0;
        var scalar = 0;
        var reason = "A character literal must contain exactly one Unicode scalar value.";

        while (!isAtEnd()) {
            final var currentCharacter = advance();
            if (currentCharacter == '\'') {
                closed = true;
                break;
            }
            if (currentCharacter == '\n' || currentCharacter == '\r') {
                valid = false;
                reason = "A character literal cannot contain a line break.";
                break;
            }

            final int value;
            if (currentCharacter == '\\') {
                if (isAtEnd()) {
                    valid = false;
                    reason = "Incomplete escape sequence in character literal.";
                    break;
                }
                final var escape = advance();
                value = switch (escape) {
                    case '\\' -> '\\';
                    case '\'' -> '\'';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> scanScalarEscape();
                    default -> {
                        valid = false;
                        reason = "Unsupported escape sequence in character literal.";
                        yield -1;
                    }
                };
                if (value < 0) {
                    valid = false;
                    reason = "Malformed Unicode scalar escape in character literal.";
                    continue;
                }
            } else if (Character.isHighSurrogate(currentCharacter)
                    && Character.isLowSurrogate(peek())) {
                value = Character.toCodePoint(currentCharacter, advance());
            } else if (Character.isSurrogate(currentCharacter)) {
                valid = false;
                reason = "A character literal cannot contain a surrogate code point.";
                continue;
            } else {
                value = currentCharacter;
            }

            scalarCount++;
            if (scalarCount == 1) scalar = value;
            else {
                valid = false;
                reason = "A character literal must contain exactly one Unicode scalar value.";
            }
        }

        if (!closed) {
            valid = false;
            reason = "Unterminated character literal.";
        } else if (scalarCount != 1) {
            valid = false;
            reason = "A character literal must contain exactly one Unicode scalar value.";
        }

        if (!valid) {
            recordDiagnostic(DiagnosticCatalog.INVALID_CHARACTER_LITERAL,
                    span(tokenStart, position()), reason);
        }
        addToken(CHAR, scalarCount == 1 ? scalar : 0);
    }

    private int scanScalarEscape() {
        if (!match('{')) return -1;
        var digits = 0;
        var value = 0;
        var valid = true;
        while (!isAtEnd() && peek() != '}' && peek() != '\'' && peek() != '\n' && peek() != '\r') {
            final var digit = Character.digit(advance(), 16);
            if (digit < 0 || value > (0x10FFFF - digit) / 16) {
                valid = false;
            } else {
                value = value * 16 + digit;
            }
            digits++;
        }
        if (!match('}')) return -1;
        if (!valid || digits == 0 || value > Character.MAX_CODE_POINT
                || value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE) {
            return -1;
        }
        return value;
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
        } else if (match('L')) {
            final var value = new BigInteger(source.substring(start, current - 1));
            if (value.compareTo(BigInteger.ONE.shiftLeft(63)) > 0) {
                addToken(LONG, BigInteger.ZERO);
                recordDiagnostic(DiagnosticCatalog.INTEGER_LITERAL_OUT_OF_RANGE,
                        span(tokenStart, position()),
                        "Long literal is outside the supported Long range.");
            } else {
                addToken(LONG, value);
            }
        } else {
            try {
                addToken(INT, Integer.parseInt(source.substring(start, current)));
            } catch (NumberFormatException _) {
                addToken(INT, 0);
                recordDiagnostic(DiagnosticCatalog.INTEGER_LITERAL_OUT_OF_RANGE,
                        span(tokenStart, position()),
                        "Integer literal is outside the supported Int range.");
            }
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

        advance();
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
        final var character = source.charAt(current++);
        if (character == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return character;
    }

    private void addToken(final TokenType type) {
        addToken(type, null);
    }

    private void addToken(final TokenType type, final Object literal) {
        tokens.add(new Token(
                type,
                source.substring(start, current),
                literal,
                span(tokenStart, position())));
    }

    private SourcePosition position() {
        return new SourcePosition(line, column, current);
    }

    private SourceSpan span(final SourcePosition startPosition, final SourcePosition endPosition) {
        return new SourceSpan(sourcePath, startPosition, endPosition);
    }

    private void recordDiagnostic(final DiagnosticCatalog.Entry entry,
                                  final SourceSpan span,
                                  final String message) {
        diagnostics.add(new Diagnostic(entry.code(), Severity.ERROR, message, span,
                List.of(), List.of(), List.of()));
    }
}
