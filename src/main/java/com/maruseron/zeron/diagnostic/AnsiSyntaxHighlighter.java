package com.maruseron.zeron.diagnostic;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.List;

public final class AnsiSyntaxHighlighter {
    private AnsiSyntaxHighlighter() {}

    public static String highlight(final String source) {
        final var scanResult = Scanner.from(source).scanWithDiagnostics();
        final var parser = Parser.of(scanResult);
        parser.parseCompilationUnitWithDiagnostics(null);
        final var forExpressionResultKeywords = java.util.Set.copyOf(
                parser.forExpressionResultKeywords());
        final var dataClassInitializerNames = java.util.Set.copyOf(parser.dataClassInitializerNames());
        final var tokens = scanResult.tokens();
        final var output = new StringBuilder();
        var copiedThrough = 0;
        for (var index = 0; index < tokens.size(); index++) {
            final var token = tokens.get(index);
            if (token.type() == TokenType.EOF) continue;
            final var start = token.span().start().offset();
            final var end = token.span().end().offset();
            if (start == null || end == null || start < copiedThrough || end > source.length()) continue;

            output.append(source, copiedThrough, start);
            var color = colorFor(token.type());
            if (color == null && token.type() == TokenType.IDENTIFIER
                    && previousSignificantIndex(tokens, index) >= 0
                    && tokens.get(previousSignificantIndex(tokens, index)).type() == TokenType.SUFFIXABLE_NUMBER) {
                color = "36";
            }
            if (color == null && dataClassInitializerNames.contains(token)) color = "36";
            if (color == null && (forExpressionResultKeywords.contains(token)
                    || isContextualKeyword(tokens, index))) color = "35";
            final var text = source.substring(start, end);
            output.append(color == null ? text : AnsiColor.apply(text, color));
            copiedThrough = end;
        }
        output.append(source, copiedThrough, source.length());
        return output.toString();
    }

    private static boolean isContextualKeyword(final List<Token> tokens, final int index) {
        final var token = tokens.get(index);
        if (token.type() != TokenType.IDENTIFIER) return false;
        return switch (token.lexeme()) {
            case "data" -> followsDataClassDeclaration(tokens, index);
            case "deriving" -> followsDerivingClause(tokens, index);
            case "with" -> followsHandleExpression(tokens, index) || followsSuppressedArm(tokens, index);
            case "handler" -> followsHandlerSyntax(tokens, index);
            case "get" -> followsPropertyBody(tokens, index);
            case "suffix" -> followsSuffixImport(tokens, index) || followsSuffixDeclaration(tokens, index);
            case "integer", "decimal" -> followsSuffixCategory(tokens, index);
            case "string" -> followsSuffixStringInput(tokens, index);
            case "out" -> isTypeProjection(tokens, index);
            case "nullable" -> isGenericAdmissibilityMarker(tokens, index);
            case "implementation" -> followsHigherKindedImplementation(tokens, index);
            case "delegating" -> followsDelegatingDeclaration(tokens, index);
            default -> false;
        };
    }

    private static boolean followsDelegatingDeclaration(final List<Token> tokens, final int index) {
        final var next = nextSignificantIndex(tokens, index);
        if (next < 0) return false;
        if (tokens.get(next).type() == TokenType.WITNESS) return true;
        return tokens.get(next).type() == TokenType.IDENTIFIER
                && tokens.get(next).lexeme().equals("implementation")
                && followsHigherKindedImplementation(tokens, next);
    }

    private static boolean followsDataClassDeclaration(final List<Token> tokens, final int index) {
        final var next = nextSignificantIndex(tokens, index);
        return next >= 0 && tokens.get(next).type() == TokenType.CLASS;
    }

    private static boolean followsHigherKindedImplementation(final List<Token> tokens, final int index) {
        var cursor = nextSignificantIndex(tokens, index);
        if (cursor < 0 || tokens.get(cursor).type() != TokenType.IDENTIFIER) return false;
        cursor = nextSignificantIndex(tokens, cursor);
        if (cursor < 0 || tokens.get(cursor).type() != TokenType.LESS) return false;

        var genericDepth = 0;
        var hasConstructorPattern = false;
        for (; cursor >= 0; cursor = nextSignificantIndex(tokens, cursor)) {
            final var token = tokens.get(cursor);
            if (token.type() == TokenType.IDENTIFIER && token.lexeme().equals("_")) {
                hasConstructorPattern = true;
            }
            switch (token.type()) {
                case LESS -> genericDepth++;
                case GREATER -> genericDepth--;
                case SHIFT_RIGHT -> genericDepth -= 2;
                case UNSIGNED_SHIFT_RIGHT -> genericDepth -= 3;
                default -> { }
            }
            if (genericDepth <= 0) {
                final var body = nextSignificantIndex(tokens, cursor);
                return genericDepth == 0 && hasConstructorPattern && body >= 0
                        && tokens.get(body).type() == TokenType.LEFT_BRACE;
            }
        }
        return false;
    }

    private static boolean isGenericAdmissibilityMarker(final List<Token> tokens, final int index) {
        final var previous = previousSignificantIndex(tokens, index);
        final var next = nextSignificantIndex(tokens, index);
        if (previous < 0 || next < 0 || tokens.get(previous).type() != TokenType.COLON) {
            return false;
        }
        final var nextType = tokens.get(next).type();
        if (nextType != TokenType.PLUS && nextType != TokenType.COMMA
                && nextType != TokenType.GREATER) {
            return false;
        }

        var nestedTypes = 0;
        for (var cursor = previousSignificantIndex(tokens, previous); cursor >= 0;
             cursor = previousSignificantIndex(tokens, cursor)) {
            final var type = tokens.get(cursor).type();
            if (type == TokenType.GREATER) {
                nestedTypes++;
            } else if (type == TokenType.SHIFT_RIGHT) {
                nestedTypes += 2;
            } else if (type == TokenType.UNSIGNED_SHIFT_RIGHT) {
                nestedTypes += 3;
            } else if (type == TokenType.LESS) {
                if (nestedTypes > 0) {
                    nestedTypes--;
                } else {
                    return beginsTypeParameterList(tokens, cursor);
                }
            }
        }
        return false;
    }

    private static boolean beginsTypeParameterList(final List<Token> tokens, final int lessThanIndex) {
        final var previous = previousSignificantIndex(tokens, lessThanIndex);
        if (previous < 0) return false;
        if (tokens.get(previous).type() == TokenType.EXTENSION) return true;
        if (tokens.get(previous).type() != TokenType.IDENTIFIER) return false;
        final var declaration = previousSignificantIndex(tokens, previous);
        if (declaration < 0) return false;
        return switch (tokens.get(declaration).type()) {
            case CLASS, CONTRACT, EFFECT, FN, TYPE -> true;
            default -> false;
        };
    }

    private static boolean isTypeProjection(final List<Token> tokens, final int index) {
        final var previous = previousSignificantIndex(tokens, index);
        final var next = nextSignificantIndex(tokens, index);
        return previous >= 0 && next >= 0
                && (tokens.get(previous).type() == TokenType.LESS
                        || tokens.get(previous).type() == TokenType.COMMA)
                && tokens.get(next).type() != TokenType.COMMA
                && tokens.get(next).type() != TokenType.GREATER;
    }

    private static boolean followsSuffixImport(final List<Token> tokens, final int index) {
        final var previous = previousSignificantIndex(tokens, index);
        return previous >= 0 && tokens.get(previous).type() == TokenType.IMPORT;
    }

    private static boolean followsSuffixDeclaration(final List<Token> tokens, final int index) {
        final var name = nextSignificantIndex(tokens, index);
        if (name < 0 || tokens.get(name).type() != TokenType.IDENTIFIER) return false;
        final var body = nextSignificantIndex(tokens, name);
        return body >= 0 && tokens.get(body).type() == TokenType.LEFT_BRACE;
    }

    private static boolean followsSuffixCategory(final List<Token> tokens, final int index) {
        final var next = nextSignificantIndex(tokens, index);
        return next >= 0 && tokens.get(next).type() == TokenType.COLON
                && insideSuffixDeclaration(tokens, index);
    }

    private static boolean followsSuffixStringInput(final List<Token> tokens, final int index) {
        final var previous = previousSignificantIndex(tokens, index);
        final var next = nextSignificantIndex(tokens, index);
        return previous >= 0 && next >= 0
                && tokens.get(previous).type() == TokenType.COLON
                && tokens.get(next).type() == TokenType.IDENTIFIER
                && insideSuffixDeclaration(tokens, index);
    }

    private static boolean insideSuffixDeclaration(final List<Token> tokens, final int index) {
        var nestedBraces = 0;
        for (var cursor = previousSignificantIndex(tokens, index); cursor >= 0;
             cursor = previousSignificantIndex(tokens, cursor)) {
            final var type = tokens.get(cursor).type();
            if (type == TokenType.RIGHT_BRACE) {
                nestedBraces++;
            } else if (type == TokenType.LEFT_BRACE) {
                if (nestedBraces > 0) {
                    nestedBraces--;
                    continue;
                }
                final var name = previousSignificantIndex(tokens, cursor);
                final var keyword = name < 0 ? -1 : previousSignificantIndex(tokens, name);
                return name >= 0 && keyword >= 0
                        && tokens.get(name).type() == TokenType.IDENTIFIER
                        && tokens.get(keyword).type() == TokenType.IDENTIFIER
                        && tokens.get(keyword).lexeme().equals("suffix");
            }
        }
        return false;
    }

    private static boolean followsDerivingClause(final List<Token> tokens, final int index) {
        var next = nextSignificantIndex(tokens, index);
        if (next < 0 || tokens.get(next).type() != TokenType.IDENTIFIER) return false;
        next = nextSignificantIndex(tokens, next);
        while (next >= 0 && tokens.get(next).type() == TokenType.COMMA) {
            next = nextSignificantIndex(tokens, next);
            if (next < 0 || tokens.get(next).type() != TokenType.IDENTIFIER) return false;
            next = nextSignificantIndex(tokens, next);
        }
        return next >= 0 && (tokens.get(next).type() == TokenType.EQUAL
                || tokens.get(next).type() == TokenType.LEFT_BRACE);
    }

    private static boolean followsHandleExpression(final List<Token> tokens, final int index) {
        final var previous = previousSignificantIndex(tokens, index);
        final var next = nextSignificantIndex(tokens, index);
        if (previous < 0 || next < 0 || tokens.get(previous).type() != TokenType.RIGHT_PAREN) {
            return false;
        }

        var depth = 0;
        for (var cursor = previous; cursor >= 0; cursor = previousSignificantIndex(tokens, cursor)) {
            final var type = tokens.get(cursor).type();
            if (type == TokenType.RIGHT_PAREN) {
                depth++;
            } else if (type == TokenType.LEFT_PAREN) {
                depth--;
                if (depth == 0) {
                    final var beforeOpeningParen = previousSignificantIndex(tokens, cursor);
                    return beforeOpeningParen >= 0
                            && tokens.get(beforeOpeningParen).type() == TokenType.HANDLE;
                }
            }
        }
        return false;
    }

    private static boolean followsHandlerSyntax(final List<Token> tokens, final int index) {
        final var next = nextSignificantIndex(tokens, index);
        if (next < 0) return false;
        if (tokens.get(next).type() == TokenType.LEFT_BRACE) return true;
        if (tokens.get(next).type() != TokenType.IDENTIFIER) return false;
        final var body = nextSignificantIndex(tokens, next);
        return body >= 0 && tokens.get(body).type() == TokenType.LEFT_BRACE;
    }

    private static boolean followsSuppressedArm(final List<Token> tokens, final int index) {
        final var next = nextSignificantIndex(tokens, index);
        return next >= 0 && tokens.get(next).type() == TokenType.IDENTIFIER
                && tokens.get(next).lexeme().equals("suppressed");
    }

    private static boolean followsPropertyBody(final List<Token> tokens, final int index) {
        final var previous = previousSignificantIndex(tokens, index);
        final var next = nextSignificantIndex(tokens, index);
        if (previous < 0 || next < 0 || tokens.get(previous).type() != TokenType.LEFT_BRACE) {
            return false;
        }
        final var nextType = tokens.get(next).type();
        return nextType == TokenType.EQUAL || nextType == TokenType.LEFT_BRACE;
    }

    private static int previousSignificantIndex(final List<Token> tokens, final int index) {
        for (var cursor = index - 1; cursor >= 0; cursor--) {
            if (!tokens.get(cursor).type().isComment()) return cursor;
        }
        return -1;
    }

    private static int nextSignificantIndex(final List<Token> tokens, final int index) {
        for (var cursor = index + 1; cursor < tokens.size(); cursor++) {
            if (!tokens.get(cursor).type().isComment() && tokens.get(cursor).type() != TokenType.EOF) {
                return cursor;
            }
        }
        return -1;
    }

    private static String colorFor(final TokenType type) {
        return switch (type) {
            case STRING, CHAR, TRUE, FALSE, NULL -> "32";
            case INT, LONG, DOUBLE, SUFFIXABLE_NUMBER -> "36";
            case LINE_COMMENT, BLOCK_COMMENT -> "90";
            case AND, AS, BREAK, CASE, CLASS, CONTRACT, CONSTRUCTOR, CONTINUE, DEFAULT, ELSE,
                    EFFECT, EXTENSION, EXTERNAL, FN, FOR, HANDLE, IF, IMPLEMENT, IMPORT,
                    IN, IS, LET, LOOP, MATCH, MUT, NOT, NAMESPACE, OR, PACKAGE, PATTERN,
                    OBJECT, PRIVATE, PROPERTY, PUBLIC, RAISE, RAISES, RETURN, SEALED, STATIC,
                    THEN, THIS, THROWS, TYPE, TYPEOF, UNTIL, USING, WITNESS, WHILE -> "35";
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
