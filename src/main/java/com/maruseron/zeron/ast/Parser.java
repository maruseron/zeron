package com.maruseron.zeron.ast;

import com.maruseron.zeron.IntRangeLiteral;
import com.maruseron.zeron.UnitLiteral;
import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.domain.NominalDescriptor;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.maruseron.zeron.scan.TokenType.*;

public final class Parser {
    private static class ParseError extends RuntimeException {}

    private final List<Token> tokens;
    private int current = 0;
    private static final AtomicInteger TYPE_PARAMETER_SCOPES = new AtomicInteger();
    private Map<String, TypeParameterDescriptor> activeTypeParameters = Map.of();

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
            if (match(FN))  return fnDeclaration();
            if (levelMarker == null && match(CLASS)) return classDeclaration();
            if (levelMarker == null && match(CONTRACT)) return contractDeclaration();

            if (levelMarker != null) return statement();
            throw error(peek(), "Expected declaration at top level.");
        } catch (ParseError error) {
            synchronize();
            return null;
        }
    }

    private Stmt letDeclaration() {
        final var mutability = match(MUT)
            ? BindingMutability.REASSIGNABLE
            : BindingMutability.IMMUTABLE;
        final var name = consume(IDENTIFIER, "Expect binding name.");

        TypeDescriptor type = TypeDescriptor.ofInfer();
        if (match(COLON)) {
            type = collectType();
        }

        Expr initializer = null;
        if (match(EQUAL)) {
            initializer = expression();
        }

        consume(SEMICOLON, "Expect ';' after variable declaration.");
        return new Stmt.Var(name, type, initializer, mutability);
    }

    private Stmt.Function fnDeclaration() {
        final var name = consume(IDENTIFIER, "Expect function name.");
        final var typeParameters = typeParameterDeclaration(name);
        final var enclosingTypeParameters = activeTypeParameters;
        activeTypeParameters = typeParameters;
        try {
            return parseFunctionDeclaration(name, typeParameters);
        } finally {
            activeTypeParameters = enclosingTypeParameters;
        }
    }

    private Stmt.Function parseFunctionDeclaration(
            final Token name,
            final Map<String, TypeParameterDescriptor> typeParameters) {
        consume(LEFT_PAREN, "Expect '(' after function name.");
        final var enclosingLevelMarker = levelMarker;
        levelMarker = new LevelMarker(levelMarker);

        try {
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

            TypeDescriptor returnType = TypeDescriptor.ofUnit();
            final var hasExplicitReturnType = match(COLON);
            if (hasExplicitReturnType) {
                returnType = collectType();
            }
            if (!typeParameters.isEmpty() && !hasExplicitReturnType) {
                error(name, "Generic functions require an explicit return type.");
            }

            final List<Stmt> body;
            if (match(EQUAL)) {
                if (!hasExplicitReturnType) returnType = TypeDescriptor.ofInfer();
                body = List.of(new Stmt.Return(expression()));
                consume(SEMICOLON, "Expect ';' after expression.");
            } else {
                consume(LEFT_BRACE, "Expect '{' before function body.");
                body = block();
            }

            return new Stmt.Function(name, parameterNames,
                    TypeDescriptor.genericFunctionOf(name.lexeme(), returnType, parameterTypes,
                            List.copyOf(typeParameters.values())), body);
        } finally {
            levelMarker = enclosingLevelMarker;
        }
    }

    private Map<String, TypeParameterDescriptor> typeParameterDeclaration(final Token functionName) {
        if (!match(LESS)) return Map.of();
        final var scopeId = TYPE_PARAMETER_SCOPES.incrementAndGet();
        final var parameters = new LinkedHashMap<String, TypeParameterDescriptor>();
        do {
            final var parameter = consume(IDENTIFIER, "Expect type parameter name.");
            if (parameters.putIfAbsent(parameter.lexeme(),
                    new TypeParameterDescriptor(scopeId, parameter.lexeme())) != null) {
                error(parameter, "Duplicate type parameter.");
            }
        } while (match(COMMA));
        consume(GREATER, "Expect '>' after type parameters.");
        if (parameters.isEmpty()) error(functionName, "A generic function must declare a type parameter.");
        return parameters;
    }

    private Stmt.ClassDecl classDeclaration() {
        final var name = consume(IDENTIFIER, "Expect class name.");
        final var contractName = match(IS) ? consume(IDENTIFIER, "Expect contract name after 'is'.") : null;
        consume(LEFT_BRACE, "Expect '{' before class members.");

        final var fields = new ArrayList<Stmt.Field>();
        final var methods = new ArrayList<Stmt.Method>();
        Stmt.Constructor constructor = null;
        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            if (match(PUBLIC, PRIVATE)) {
                final var isPublic = previous().type() == PUBLIC;
                if (match(CONSTRUCTOR)) {
                    if (constructor != null) error(previous(), "A class can declare only one constructor in v1.");
                    final var constructorName = consume(IDENTIFIER, "Expect 'new' after 'constructor'.");
                    if (!constructorName.lexeme().equals("new")) error(constructorName, "The canonical constructor must be named 'new'.");
                    consume(SEMICOLON, "Expect ';' after constructor declaration.");
                    constructor = new Stmt.Constructor(constructorName, isPublic);
                } else {
                    methods.add(classMethod(isPublic));
                }
            } else if (match(CONSTRUCTOR)) {
                error(previous(), "Constructors must declare 'public' or 'private' visibility.");
            } else {
                final var fieldName = consume(IDENTIFIER, "Expect field name or explicitly visible method.");
                consume(COLON, "Expect ':' after field name.");
                final var fieldType = collectType();
                consume(SEMICOLON, "Expect ';' after field declaration.");
                fields.add(new Stmt.Field(fieldName, fieldType));
            }
        }
        consume(RIGHT_BRACE, "Expect '}' after class members.");
        if (constructor == null) error(name, "A class must declare 'public' or 'private constructor new'.");
        return new Stmt.ClassDecl(name, contractName, List.copyOf(fields), constructor, List.copyOf(methods));
    }

    private Stmt.Method classMethod(final boolean isPublic) {
        final var isMutating = match(MUT);
        final var name = consume(IDENTIFIER, "Expect method name.");
        final var signature = methodSignature(name, false);
        return new Stmt.Method(name, signature.parameters(), signature.typeDescriptor(),
                isPublic, isMutating, signature.body());
    }

    private Stmt.ContractDecl contractDeclaration() {
        final var name = consume(IDENTIFIER, "Expect contract name.");
        consume(LEFT_BRACE, "Expect '{' before contract members.");
        final var methods = new ArrayList<Stmt.ContractMethod>();
        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            final var isMutating = match(MUT);
            final var methodName = consume(IDENTIFIER, "Expect contract method name.");
            final var signature = methodSignature(methodName, true);
            methods.add(new Stmt.ContractMethod(methodName, signature.parameters(),
                    signature.typeDescriptor(), isMutating));
        }
        consume(RIGHT_BRACE, "Expect '}' after contract members.");
        return new Stmt.ContractDecl(name, List.copyOf(methods));
    }

    private record ParsedMethod(List<Token> parameters,
                                com.maruseron.zeron.domain.FunctionDescriptor typeDescriptor,
                                List<Stmt> body) {}

    private ParsedMethod methodSignature(final Token name, final boolean isContract) {
        consume(LEFT_PAREN, "Expect '(' after method name.");
        final var parameterNames = new ArrayList<Token>();
        final var parameterTypes = new ArrayList<TypeDescriptor>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (parameterNames.size() >= 254) error(peek(), "Can't have more than 254 parameters.");
                parameterNames.add(consume(IDENTIFIER, "Expect parameter name."));
                consume(COLON, "Expect ':' after parameter name.");
                parameterTypes.add(collectType());
            } while (match(COMMA));
        }
        consume(RIGHT_PAREN, "Expect ')' after parameters.");

        final var hasReturnType = match(COLON);
        final var returnType = hasReturnType ? collectType() : TypeDescriptor.ofUnit();
        if (!hasReturnType) error(name, "Class and contract methods require an explicit return type.");

        final var descriptor = TypeDescriptor.functionOf(name.lexeme(), returnType,
                parameterTypes.toArray(TypeDescriptor[]::new));
        if (isContract) {
            consume(SEMICOLON, "Expect ';' after contract method signature.");
            return new ParsedMethod(List.copyOf(parameterNames), descriptor, List.of());
        }

        levelMarker = new LevelMarker(levelMarker);
        List<Stmt> body;
        if (match(EQUAL)) {
            body = List.of(new Stmt.Return(expression()));
            consume(SEMICOLON, "Expect ';' after method expression.");
            levelMarker = levelMarker.enclosing();
            return new ParsedMethod(List.copyOf(parameterNames),
                    TypeDescriptor.functionOf(name.lexeme(), returnType,
                            parameterTypes.toArray(TypeDescriptor[]::new)), body);
        }

        consume(LEFT_BRACE, "Expect '{' before method body.");
        body = block();
        levelMarker = levelMarker.enclosing();
        return new ParsedMethod(List.copyOf(parameterNames), descriptor, body);
    }

    private TypeDescriptor collectType() {
        final var isMutable = match(AMPERSAND);
        TypeDescriptor type;

        if (match(LEFT_PAREN)) {
            final var parameters = new ArrayList<TypeDescriptor>();
            if (!check(RIGHT_PAREN)) {
                do {
                    parameters.add(collectType());
                } while (match(COMMA));
            }
            consume(RIGHT_PAREN, "Expect ')' after lambda parameter types.");
            consume(ARROW, "Expect '->' after ')'.");
            final var returnType = collectType();
            type = TypeDescriptor.functionOf("", returnType,
                    parameters.toArray(TypeDescriptor[]::new));
        } else {
            final var typeName = consume(IDENTIFIER, "Expect bind name.");

            var isGeneric = false;
            List<TypeDescriptor> inner = null;
            while (match(LESS)) {
                isGeneric = true;
                inner = collectTypeArguments();
                consume(GREATER, "Expect '>' after type.");
            }

            type = activeTypeParameters.get(typeName.lexeme());
            if (type == null) type = TypeDescriptor.of(typeName.lexeme());
            if (isGeneric) {
                if (type.name().equals("Array")) {
                    if (inner.size() != 1) error(typeName, "Array expects one element type.");
                    type = TypeDescriptor.arrayOf(inner.getFirst());
                } else if (type instanceof NominalDescriptor nominal) {
                    type = TypeDescriptor.genericOf(nominal, inner);
                } else {
                    error(typeName, "Only nominal types can have type arguments.");
                }
            } else if (type.name().equals("Array")) {
                error(typeName, "Array requires an element type.");
            }
        }

        if (isMutable) type = new ReferenceDescriptor(type);
        if (match(HUH)) type = type.toNullable();

        return type;
    }

    private List<TypeDescriptor> collectTypeArguments() {
        final var typeArgs = new ArrayList<TypeDescriptor>();
        do {
            typeArgs.add(collectType());
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
                        TypeDescriptor.ofInfer());
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

            if (expr instanceof Expr.Property property) {
                if (operator.type() != EQUAL) error(operator, "Field assignment currently supports '=' only.");
                return new Expr.PropertyAssignment(property, value, TypeDescriptor.ofInfer());
            }

            if (expr instanceof Expr.Index index) {
                if (operator.type() != EQUAL) error(operator, "Indexed assignment only supports '='.");
                return new Expr.IndexAssignment(index.array, index.index, value, TypeDescriptor.ofUnit());
            }

            // left assign_op right === left = left op right
            if (expr instanceof Expr.Variable variable) {
                final var name = variable.name;
                return switch (operator.type()) {
                    case EQUAL -> new Expr.Assignment(name, value, null);
                    case PLUS_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic plus token from plus_equal
                                    new Token(PLUS, "+", null, operator.line()),
                                    value,
                                    TypeDescriptor.ofInfer()),
                            TypeDescriptor.ofInfer());
                    case MINUS_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic minus token from minus_equal
                                    new Token(MINUS, "-", null, operator.line()),
                                    value,
                                    TypeDescriptor.ofInfer()),
                            TypeDescriptor.ofInfer());
                    case STAR_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic star token from star_equal
                                    new Token(STAR, "*", null, operator.line()),
                                    value,
                                    TypeDescriptor.ofInfer()),
                            TypeDescriptor.ofInfer());
                    case SLASH_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic slash token from slash_equal
                                    new Token(SLASH, "/", null, operator.line()),
                                    value,
                                    TypeDescriptor.ofInfer()),
                            TypeDescriptor.ofInfer());
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
            expr = new Expr.Binary(expr, operator, right, TypeDescriptor.ofInfer());
        }

        return expr;
    }

    private Expr factor() {
        var expr = unary();

        while (match(SLASH, STAR)) {
            final var operator = previous();
            final var right = unary();
            expr = new Expr.Binary(expr, operator, right, TypeDescriptor.ofInfer());
        }

        return expr;
    }

    private Expr unary() {
        if (match(NOT, MINUS, TYPEOF)) {
            final var operator = previous();
            final var right = unary();
            return new Expr.Unary(operator, right, TypeDescriptor.ofInfer());
        }

        return call();
    }

    private Expr call() {
        var expr = primary();

        while (true) {
            if (expr instanceof Expr.Variable variable && check(LESS)
                    && looksLikeTypeArgumentsCall()) {
                advance();
                final var typeArguments = collectTypeArguments();
                consume(GREATER, "Expect '>' after type arguments.");
                consume(LEFT_PAREN, "Expect '(' after type arguments.");
                expr = finishCall(variable.name, typeArguments);
            } else if (match(LEFT_PAREN)) {
                if (expr instanceof Expr.Variable variable) {
                    expr = finishCall(variable.name);
                } else if (expr instanceof Expr.Property property) {
                    expr = finishMemberCall(property);
                } else {
                    error(previous(), "Only functions and named methods can be called.");
                }
            } else if (match(LEFT_BRACKET)) {
                final var index = expression();
                consume(RIGHT_BRACKET, "Expect ']' after array index.");
                expr = new Expr.Index(expr, index, TypeDescriptor.ofInfer());
            } else if (match(DOT)) {
                final var property = consume(IDENTIFIER, "Expect member name after '.'.");
                expr = new Expr.Property(expr, property, TypeDescriptor.ofInfer());
            } else {
                break;
            }
        }

        return expr;
    }

    private Expr finishCall(final Token callee) {
        return finishCall(callee, List.of());
    }

    private Expr finishCall(final Token callee, final List<TypeDescriptor> typeArguments) {
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

        return new Expr.Call(callee, paren, arguments, typeArguments, TypeDescriptor.ofInfer());
    }

    private boolean looksLikeTypeArgumentsCall() {
        var depth = 0;
        for (var index = current; index < tokens.size(); index++) {
            final var type = tokens.get(index).type();
            if (type == LESS) depth++;
            else if (type == GREATER && --depth == 0) {
                return index + 1 < tokens.size() && tokens.get(index + 1).type() == LEFT_PAREN;
            }
        }
        return false;
    }

    private Expr finishMemberCall(final Expr.Property property) {
        final var arguments = new ArrayList<Expr>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (arguments.size() >= 254) error(peek(), "Can't have more than 254 arguments.");
                arguments.add(expression());
            } while (match(COMMA));
        }
        final var paren = consume(RIGHT_PAREN, "Expect ')' after arguments.");
        return new Expr.MemberCall(property.receiver, property.name, paren, arguments,
                TypeDescriptor.ofInfer());
    }

    private Expr primary() {
        if (match(LEFT_BRACKET)) {
            final var elements = new ArrayList<Expr>();
            if (check(RIGHT_BRACKET)) error(peek(), "Array literals must initialize at least one element.");
            do {
                elements.add(expression());
            } while (match(COMMA));
            consume(RIGHT_BRACKET, "Expect ']' after array elements.");
            return new Expr.ArrayLiteral(elements, TypeDescriptor.ofInfer());
        }

        if (match(FALSE)) return new Expr.Literal(false, TypeDescriptor.ofBoolean());
        if (match(TRUE))  return new Expr.Literal(true,  TypeDescriptor.ofBoolean());
        if (match(NULL))  return new Expr.Literal(null, TypeDescriptor.ofNull());
        if (match(UNIT))  return new Expr.Literal(new UnitLiteral(), TypeDescriptor.ofUnit());
        if (match(THIS))  return new Expr.Variable(previous(), TypeDescriptor.ofInfer());

        if (match(INT)) {
            final var number = previous();
            // check if it's a range
            if (match(DOT_DOT)) {
                return new Expr.Literal(new IntRangeLiteral(
                        (Integer)number.literal(),
                        previous(),
                        (Integer)consume(INT, "Expect Integer after range operator").literal()),
                        TypeDescriptor.genericOf(
                                TypeDescriptor.ofName("Range"),
                                TypeDescriptor.ofInt()));
            }
            return new Expr.Literal(number.literal(), TypeDescriptor.ofInt());
        }

        if (match(DOUBLE, STRING)) {
            return new Expr.Literal(
                    previous().literal(),
                    previous().type() == DOUBLE
                            ? TypeDescriptor.ofFloat()
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
            return new Expr.If(paren, condition, thenExpr, elseExpr, TypeDescriptor.ofInfer());
        }

        if (match(IDENTIFIER)) {
            // `a -> ...` lambda
            final var ident = previous();
            if (check(ARROW)) {
                return finishLambda(List.of(ident));
            }
            return new Expr.Variable(ident, TypeDescriptor.ofInfer());
        }

        // ( can be `() ->`, `(a, b) ->`, or `(a + b)`
        if (match(LEFT_PAREN)) {
            final var paren = previous();
            if (check(RIGHT_PAREN)) {
                advance();
                return finishLambda(List.of());
            }

            if (check(IDENTIFIER)) {
                final var params = new ArrayList<Token>();
                params.add(consume(IDENTIFIER, "Expect parameter name."));
                while (match(COMMA)) {
                    if (params.size() >= 254) {
                        error(peek(), "Can't have more than 254 parameters.");
                    }
                    params.add(consume(IDENTIFIER, "Expect parameter name."));
                }
                if (check(RIGHT_PAREN)) {
                    final var nextToken = current + 1 < tokens.size() ? tokens.get(current + 1) : null;
                    consume(RIGHT_PAREN, "Expect ')' after lambda parameters.");
                    if (nextToken != null && nextToken.type() == ARROW) {
                        return finishLambda(params);
                    }
                    if (params.size() == 1) {
                        return new Expr.Grouping(paren,
                                new Expr.Variable(params.getFirst(), TypeDescriptor.ofInfer()),
                                TypeDescriptor.ofInfer());
                    }
                    throw error(peek(), "Expect '->' after lambda parameters.");
                }
            }

            return new Expr.Grouping(paren, expression(), TypeDescriptor.ofInfer());
        }

        /*
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
                                    TypeDescriptor.ofInfer()),
                            TypeDescriptor.ofInfer());
                }
            // empty paren: definitely a no param lambda
            } else if (match(RIGHT_PAREN)) {
                return finishLambda(List.of());
            }
            consume(RIGHT_PAREN, "Expect ')' after expression.");
            return new Expr.Grouping(paren, expression(), TypeDescriptor.ofInfer());
        }
         */

        throw error(peek(), "Expect expression.");
    }

    private Expr.Lambda finishLambda(final List<Token> params) {
        final var arrow = consume(ARROW, "Expect '->' after parameters.");
        levelMarker = new LevelMarker(levelMarker);
        List<Stmt> body;
        if (match(LEFT_BRACE)) {
            body = block();
        } else {
            body = List.of(new Stmt.Return(expression()));
        }
        levelMarker = levelMarker.enclosing();
        final var inferredParameters = params.stream()
                .map(_ -> TypeDescriptor.ofInfer())
                .toArray(TypeDescriptor[]::new);
        return new Expr.Lambda(arrow, params, body,
                TypeDescriptor.functionOf("", TypeDescriptor.ofInfer(), inferredParameters));
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
