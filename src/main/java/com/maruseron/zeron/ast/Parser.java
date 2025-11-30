package com.maruseron.zeron.ast;

import com.maruseron.zeron.IntRangeLiteral;
import com.maruseron.zeron.UnitLiteral;
import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.ArrayList;
import java.util.List;

import static com.maruseron.zeron.scan.TokenType.*;

public final class Parser {
    private static class ParseError extends RuntimeException {}

    private final List<Token> tokens;
    private int current = 0;

    private record LoopMarker(LoopMarker enclosing) {}
    private record LevelMarker(LevelMarker enclosing) {}

    private LoopMarker loopMarker   = null;
    private LevelMarker levelMarker = null;

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

            if (levelMarker != null) return statement();
            throw error(peek(), "Expected declaration at top level.");
        } catch (ParseError error) {
            synchronize();
            return null;
        }
    }

    private Stmt letDeclaration() {
        boolean isFinal = !match(MUT);
        final var name = consume(IDENTIFIER, "Expect binding name.");

        TypeDescriptor type = TypeDescriptor.inferred();
        if (match(LEFT_PAREN)) {
            if (!isFinal)
                error(previous(), "Function declaration cannot be mutable.");

            return function(name);
        } else if (match(COLON)) {
            type = collectType();
        }

        Expr initializer = null;
        if (match(EQUAL)) {
            initializer = expression();
        }

        consume(SEMICOLON, "Expect ';' after variable declaration.");
        return new Stmt.Var(name, type, initializer, isFinal);
    }

    private Stmt.Function function(final Token name) {
        levelMarker = new LevelMarker(levelMarker);
        final var descriptorString = new StringBuilder("$ ");
        final var parameterNames = new ArrayList<Token>();
        final var parameterTypes = new ArrayList<TypeDescriptor>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (parameterNames.size() >= 254) {
                    error(peek(), "Can't have more than 254 parameters.");
                }
                parameterNames.add(consume(IDENTIFIER, "Expect parameter name."));
                consume(COLON, "Expect ':' after parameter name.");
                parameterTypes.add(collectType());
            } while (match(COMMA));
        }
        consume(RIGHT_PAREN, "Expect ')' after parameters.");
        String returnType = "<infer>";
        if (match(COLON)) {
            returnType = collectType().descriptor();
        }
        descriptorString.append(parameterTypes.size()).append(" ");
        parameterTypes.forEach(it -> descriptorString.append(it).append(" "));
        descriptorString.append(returnType).append(" ").append(name.lexeme());

        List<Stmt> body;
        if (match(ARROW)) {
            body = List.of(expressionStatement());
        } else {
            consume(LEFT_BRACE, "Expect '{' before function body.");
            body = block();
        }

        levelMarker = levelMarker.enclosing();
        return new Stmt.Function(
                name,
                parameterNames,
                new TypeDescriptor(descriptorString.toString()),
                body);
    }

    private TypeDescriptor collectType() {
        final var descriptorString = new StringBuilder();

        // if type starts with a left parenthesis, it's a lambda
        if (match(LEFT_PAREN)) {
            // add $
            descriptorString.append("$ ");
            final var lambdaArgs = new ArrayList<TypeDescriptor>();
            if (!check(RIGHT_PAREN)) {
                do {
                    // collect all parameters while we match a comma
                    lambdaArgs.add(collectType());
                } while (match(COMMA));
            }
            consume(RIGHT_PAREN, "Expect ')' after lambda parameter types.");
            consume(ARROW, "Expect '->' after ')'.");
            final var lambdaReturnType = collectType();
            descriptorString.append(lambdaArgs.size()).append(" ");
            lambdaArgs.forEach(it ->
                    descriptorString.append(it.descriptor()).append(" "));
            descriptorString.append(lambdaReturnType.descriptor());

            return new TypeDescriptor(descriptorString.toString());
        }

        final var isMutable  = match(AMPERSAND);
        final var typeName   = consume(IDENTIFIER, "Expect bind name.");

        // generic type open bracket e.g &List< ... >
        var isGeneric = false;
        List<String> inner = null;
        while (match(LESS)) {
            isGeneric = true;
            inner = collectTypeArguments();
            consume(GREATER, "Expect '>' after type.");
        }

        // match [] or ?
        while (match(HUH, LEFT_BRACKET)) {
            if (previous().type() == LEFT_BRACKET) {
                consume(RIGHT_BRACKET, "Expect ']' after '['");
                descriptorString.append("a");
            } else {
                descriptorString.append("n");
            }
        }

        if (isGeneric) descriptorString.append("@ ").append(inner.size()).append(" ");
        if (isMutable) descriptorString.append("m");

        descriptorString.append(":").append(typeName.lexeme()).append(" ");

        if (isGeneric) inner.forEach(it -> descriptorString.append(it).append(" "));

        return new TypeDescriptor(descriptorString.toString());
    }

    private List<String> collectTypeArguments() {
        final var typeArgs = new ArrayList<String>();
        do {
            typeArgs.add(collectType().descriptor());
        } while (match(COMMA));
        return typeArgs;
    }

    private Stmt statement() {
        if (match(BREAK)) return new Stmt.Break(break_());
        if (match(RETURN)) return new Stmt.Return(return_());
        if (match(FOR)) return forStatement();
        if (match(IF)) return ifStatement();
        if (match(PRINT)) return printStatement();
        if (match(LOOP, WHILE, UNTIL)) return unboundLoopStatement();
        if (match(LEFT_BRACE)) return new Stmt.Block(block());

        return expressionStatement();
    }

    private Token break_() {
        if (loopMarker == null)
            error(previous(), "Can only break inside of a loop.");

        consume(SEMICOLON, "Expect ';' after break.");
        return previous();
    }

    private Expr return_() {
        if (levelMarker == null)
            error(previous(), "Can only return inside of a function.");

        final var expr = expression();
        consume(SEMICOLON, "Expect ';' after expression.");
        return expr;
    }

    private Stmt forStatement() {
        // wrap into loop level
        this.loopMarker = new LoopMarker(loopMarker);
        consume(LEFT_PAREN, "Expect '(' after 'for'.");

        consume(LET, "Expect iteration bind after '('");
        final var iterationBind = consume(IDENTIFIER, "Expect bind name after 'let'.");
        final var in = consume(IN, "Expect 'in' after iteration bind.");
        final var expression  = expression();
        consume(RIGHT_PAREN, "Expect ')' after iterable expression.");

        final var body = statement();

        // unwrap into enclosing
        this.loopMarker = loopMarker.enclosing();
        return new Stmt.For(iterationBind, in, expression, body);
    }

    private Stmt ifStatement() {
        final var paren = consume(LEFT_PAREN, "Expect '(' after 'if'.");
        final var condition = expression();
        consume(RIGHT_PAREN, "Expect ')' after if condition.");

        final var thenBranch = statement();
        Stmt elseBranch = null;
        if (match(ELSE)) {
            elseBranch = statement();
        }

        return new Stmt.If(paren, condition, thenBranch, elseBranch);
    }

    private Stmt printStatement() {
        consume(LEFT_PAREN, "Expect '(' before expression.");
        final var value = expression();
        consume(RIGHT_PAREN, "Expect ')' after expression.");
        consume(SEMICOLON, "Expect ';' after expression.");
        return new Stmt.Print(value);
    }

    private Stmt unboundLoopStatement() {
        // wrap into loop level
        this.loopMarker = new LoopMarker(loopMarker);
        final var keyword = previous();
        Expr condition = switch (keyword.type()) {
            // LOOP condition is always true
            case LOOP -> new Expr.Literal(true, TypeDescriptor.ofBoolean());
            case WHILE -> {
                consume(LEFT_PAREN, "Expect '(' after while.");
                final var res = expression();
                consume(RIGHT_PAREN, "Expect ')' after condition.");
                yield res;
            }
            case UNTIL -> {
                consume(LEFT_PAREN, "Expect '(' after until.");
                // UNTIL generates a synthetic negation for while.
                // It uses a fake NOT operator with "until" as lexeme
                final var res = new Expr.Unary(
                        new Token(NOT, previous().lexeme(), null, previous().line()),
                        expression(),
                        TypeDescriptor.inferred());
                consume(RIGHT_PAREN, "Expect ')' after condition.");
                yield res;
            }
            default -> throw new IllegalStateException("unreachable");
        };
        final var body = statement();
        // unwrap into enclosing
        this.loopMarker = loopMarker.enclosing();
        return new Stmt.While(keyword, condition, body);
    }

    private Stmt expressionStatement() {
        final var expr = expression();
        consume(SEMICOLON, "Expect ';' after expression.");
        return new Stmt.Expression(expr);
    }

    private List<Stmt> block() {
        final var statements = new ArrayList<Stmt>();

        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            statements.add(declaration());
        }

        consume(RIGHT_BRACE, "Expect '}' after block.");
        return statements;
    }

    private Expr expression() {
        return assignment();
    }

    private Expr assignment() {
        var expr = or();

        if (match(PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, SLASH_EQUAL, EQUAL)) {
            final var operator = previous();
            final var value = assignment();

            // left assign_op right === left = left op right
            if (expr instanceof Expr.Variable(Token name, _)) {
                return switch (operator.type()) {
                    case EQUAL -> new Expr.Assignment(name, value, null);
                    case PLUS_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic plus token from plus_equal
                                    new Token(PLUS, "+", null, operator.line()),
                                    value,
                                    TypeDescriptor.inferred()),
                            TypeDescriptor.inferred());
                    case MINUS_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic minus token from minus_equal
                                    new Token(MINUS, "-", null, operator.line()),
                                    value,
                                    TypeDescriptor.inferred()),
                            TypeDescriptor.inferred());
                    case STAR_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic star token from star_equal
                                    new Token(STAR, "*", null, operator.line()),
                                    value,
                                    TypeDescriptor.inferred()),
                            TypeDescriptor.inferred());
                    case SLASH_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic slash token from slash_equal
                                    new Token(SLASH, "/", null, operator.line()),
                                    value,
                                    TypeDescriptor.inferred()),
                            TypeDescriptor.inferred());
                    default -> throw new IllegalStateException("unreachable");
                };
            }

            error(operator, "Invalid assignment target.");
        }

        return expr;
    }

    private Expr or() {
        var expr = and();

        while (match(OR)) {
            final var operator = previous();
            final var right = and();
            expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr and() {
        var expr = equality();

        while (match(AND)) {
            final var operator = previous();
            final var right = equality();
            expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr equality() {
        var expr = comparison();

        while (match(BANG_EQUAL, EQUAL_EQUAL)) {
            final var operator = previous();
            final var right = comparison();
            expr = new Expr.Binary(expr, operator, right, TypeDescriptor.ofBoolean());
        }

        return expr;
    }

    private Expr comparison() {
        var expr = term();

        while (match(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
            final var operator = previous();
            final var right = term();
            expr = new Expr.Binary(expr, operator, right, TypeDescriptor.ofBoolean());
        }

        return expr;
    }

    private Expr term() {
        var expr = factor();

        while (match(MINUS, PLUS)) {
            final var operator = previous();
            final var right = factor();
            expr = new Expr.Binary(expr, operator, right, TypeDescriptor.inferred());
        }

        return expr;
    }

    private Expr factor() {
        var expr = unary();

        while (match(SLASH, STAR)) {
            final var operator = previous();
            final var right = unary();
            expr = new Expr.Binary(expr, operator, right, TypeDescriptor.inferred());
        }

        return expr;
    }

    private Expr unary() {
        if (match(NOT, MINUS, TYPEOF)) {
            final var operator = previous();
            final var right = unary();
            return new Expr.Unary(operator, right, TypeDescriptor.inferred());
        }

        return call();
    }

    private Expr call() {
        var expr = primary();

        while (true) {
            final var token = previous();
            if (match(LEFT_PAREN)) {
                expr = finishCall(token);
            } else {
                break;
            }
        }

        return expr;
    }

    private Expr finishCall(final Token callee) {
        final var arguments = new ArrayList<Expr>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (arguments.size() >= 254) {
                    error(peek(), "Can't have more than 254 arguments.");
                }
                arguments.add(expression());
                System.out.println("added argument to call: " + arguments.getLast());
            } while (match(COMMA));
        }

        final var paren = consume(RIGHT_PAREN, "Expect ')' after arguments.");

        return new Expr.Call(callee, paren, arguments, TypeDescriptor.inferred());
    }

    private Expr primary() {
        if (match(FALSE)) return new Expr.Literal(false, TypeDescriptor.ofBoolean());
        if (match(TRUE)) return new Expr.Literal(true, TypeDescriptor.ofBoolean());
        if (match(NULL)) return new Expr.Literal(null, TypeDescriptor.ofNever().nullable());
        if (match(UNIT)) return new Expr.Literal(new UnitLiteral(), TypeDescriptor.ofUnit());

        if (match(INT)) {
            final var number = previous();
            // check if it's a range
            if (match(DOT_DOT)) {
                return new Expr.Literal(new IntRangeLiteral(
                        (Integer)number.literal(),
                        previous(),
                        (Integer)consume(INT, "Expect Integer after range operator").literal()),
                        TypeDescriptor.genericOf(new TypeDescriptor(":Range"), TypeDescriptor.ofInt()));
            }
            return new Expr.Literal(number.literal(), TypeDescriptor.ofInt());
        }

        if (match(DOUBLE, STRING)) {
            return new Expr.Literal(
                    previous().literal(),
                    previous().type() == DOUBLE
                            ? TypeDescriptor.ofDouble()
                            : TypeDescriptor.ofString());
        }

        if (match(IF)) {
            final var paren = consume(LEFT_PAREN, "Expect '(' after 'if'.");
            final var condition = expression();
            consume(RIGHT_PAREN, "Expect ')' after condition.");
            consume(THEN, "Expect 'then' after ')'.");
            final var thenExpr = expression();
            consume(ELSE, "'Expect 'else' after expression.");
            final var elseExpr = expression();
            return new Expr.If(paren, condition, thenExpr, elseExpr, TypeDescriptor.inferred());
        }

        if (match(IDENTIFIER)) {
            return new Expr.Variable(previous(), TypeDescriptor.inferred());
        }

        // ( -> must disambiguate grouping vs lambda. how?
        if (match(LEFT_PAREN)) {
            final var paren = previous();
            // if identifier, can be a lambda
            // (a -> must check for (a,
            if (match(IDENTIFIER)) {
                final var ident = previous();
                // (a, -> definitely a lambda
                if (match(COMMA)) {
                    final var list = new ArrayList<Token>();
                    list.add(ident);
                    do {
                        if (list.size() >= 254) {
                            error(peek(), "Can't have more than 254 arguments.");
                        }
                        list.add(consume(IDENTIFIER, "Expect parameter name."));
                    } while (match(COMMA));
                    consume(RIGHT_PAREN, "Expect ')' after lambda parameters");
                    return finishLambda(list);
                }
                // (a), must look for arrow
                else if (match(RIGHT_PAREN)) {
                    if (check(ARROW)) {
                        return finishLambda(List.of(ident));
                    }
                }
                // none of the others
                else {
                    consume(RIGHT_PAREN, "Expect ')' after expression.");
                    return new Expr.Grouping(
                            paren,
                            new Expr.Variable(
                                    ident,
                                    TypeDescriptor.inferred()),
                            TypeDescriptor.inferred());
                }
            // empty paren: definitely a no param lambda
            } else if (match(RIGHT_PAREN)) {
                return finishLambda(List.of());
            }
            consume(RIGHT_PAREN, "Expect ')' after expression.");
            return new Expr.Grouping(paren, expression(), TypeDescriptor.inferred());
        }

        throw error(peek(), "Expect expression.");
    }

    private Expr.Lambda finishLambda(final List<Token> params) {
        final var arrow = consume(ARROW, "Expect '->' after ')'.");
        levelMarker = new LevelMarker(levelMarker);
        List<Stmt> body;
        if (match(LEFT_BRACE)) {
            body = block();
        } else {
            body = List.of(new Stmt.Return(expression()));
        }
        levelMarker = levelMarker.enclosing();
        return new Expr.Lambda(arrow, params, body, TypeDescriptor.inferred());
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
