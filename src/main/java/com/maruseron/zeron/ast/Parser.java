package com.maruseron.zeron.ast;

import com.maruseron.zeron.UnitLiteral;
import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.ArrayList;
import java.util.List;

import static com.maruseron.zeron.scan.TokenType.*;

public final class Parser {
    private static class ParseError extends RuntimeException {}

    private final List<Token> tokens;
    private int current = 0;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Parser of(final List<Token> tokens) {
        return new Parser(tokens);
    }

    public List<Stmt> parse() {
        final var statements = new ArrayList<Stmt>();
        while (!isAtEnd()) {
            statements.add(declaration());
        }

        return statements;
    }

    private Stmt declaration() {
        try {
            if (match(LET)) return letDeclaration();

            return statement();
        } catch (ParseError error) {
            synchronize();
            return null;
        }
    }

    private Stmt letDeclaration() {
        boolean isFinal = !match(MUT);
        final var name = consume(IDENTIFIER, "Expect binding name.");

        Token type = null;
        if (match(LEFT_PAREN)) {
            // FUNCTION DECLARATION
            return null;
        } else if (match(COLON)) {
            // TODO: rework this into type consumer with modifiers like
            //       [], ? and &
            type = consume(IDENTIFIER, "Expect type name.");
        }

        Expr initializer = null;
        if (match(EQUAL)) {
            initializer = expression();
        }

        consume(SEMICOLON, "Expect ';' after variable declaration.");
        return new Let(name, type, initializer, isFinal);
    }

    private Stmt statement() {
        if (match(PRINT)) return printStatement();

        return expressionStatement();
    }

    private Stmt printStatement() {
        consume(LEFT_PAREN, "Expect '(' before expression.");
        final var value = expression();
        consume(RIGHT_PAREN, "Expect ')' after expression.");
        consume(SEMICOLON, "Expect ';' after expression.");
        return new Print(value);
    }

    private Stmt expressionStatement() {
        final var expr = expression();
        consume(SEMICOLON, "Expect ';' after expression.");
        return new Expression(expr);
    }

    private Expr expression() {
        return assignment();
    }

    private Expr assignment() {
        var expr = equality();

        if (match(EQUAL)) {
            final var equals = previous();
            final var value = assignment();

            if (expr instanceof Variable(Token name)) {
                return new Assignment(name, value);
            }

            error(equals, "Invalid assignment target.");
        }

        return expr;
    }

    private Expr equality() {
        var expr = comparison();

        while (match(BANG_EQUAL, EQUAL_EQUAL)) {
            final var operator = previous();
            final var right = comparison();
            expr = new Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr comparison() {
        var expr = term();

        while (match(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
            final var operator = previous();
            final var right = term();
            expr = new Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr term() {
        var expr = factor();

        while (match(MINUS, PLUS)) {
            final var operator = previous();
            final var right = factor();
            expr = new Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr factor() {
        var expr = unary();

        while (match(SLASH, STAR)) {
            final var operator = previous();
            final var right = unary();
            expr = new Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr unary() {
        if (match(NOT, MINUS)) {
            final var operator = previous();
            final var right = unary();
            return new Unary(operator, right);
        }

        return primary();
    }

    private Expr primary() {
        if (match(FALSE)) return new Literal(false);
        if (match(TRUE)) return new Literal(true);
        if (match(NULL)) return new Literal(null);
        if (match(UNIT)) return new Literal(new UnitLiteral());

        if (match(INT, DOUBLE, STRING)) {
            return new Literal(previous().literal());
        }

        if (match(IDENTIFIER)) {
            return new Variable(previous());
        }

        if (match(LEFT_PAREN)) {
            final var expr = expression();
            consume(RIGHT_PAREN, "Expect ')' after expression.");
            return new Grouping(expr);
        }

        throw error(peek(), "Expect expression.");
    }

    private boolean match(final TokenType... types) {
        for (final var type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }

        return false;
    }

    private Token consume(final TokenType type, final String message) {
        if (check(type)) return advance();

        throw error(peek(), message);
    }

    private boolean check(final TokenType type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private boolean isAtEnd() {
        return peek().type() == EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private ParseError error(final Token token, final String message) {
        Zeron.error(token, message);
        return new ParseError();
    }

    private void synchronize() {
        advance();

        while (!isAtEnd()) {
            if (previous().type() == SEMICOLON) return;

            switch (peek().type()) {
                case BREAK, CLASS, CONTRACT, LET, FOR, IF,
                     WHILE, UNTIL, LOOP, PRINT, RETURN -> { return; }
            }

            advance();
        }
    }
}
