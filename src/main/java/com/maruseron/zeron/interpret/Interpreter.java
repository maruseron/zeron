package com.maruseron.zeron.interpret;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.List;
import java.util.Objects;

public final class Interpreter {
    private Environment environment = new Environment();

    public void interpret(final List<Stmt> statements) {
        try {
            for (final var statement : statements) {
                execute(statement);
            }
        } catch (RuntimeError error) {
            Zeron.runtimeError(error);
        }
    }

    public void execute(final Stmt stmt) {
        switch (stmt) {
            case Stmt.Block(List<Stmt> statements) ->
                    executeBlock(statements, new Environment(environment));
            case Stmt.Expression(Expr expression) ->
                    evaluate(expression);
            case Stmt.If(Token paren, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                if (ensureBoolean(paren, evaluate(condition))) {
                    execute(thenBranch);
                } else if (elseBranch != null) {
                    execute(elseBranch);
                }
            }
            case Stmt.Print(Expr expression) ->
                    System.out.println(evaluate(expression));
            case Stmt.Var(Token name, TypeDescriptor type, Expr initializer, boolean isFinal) -> {
                Object value = null;
                if (initializer != null) {
                    value = evaluate(initializer);
                }

                environment.define(
                        name.lexeme(),
                        type,
                        value,
                        initializer != null,
                        isFinal);
            }
        }
    }

    void executeBlock(final List<Stmt> statements, final Environment environment) {
        final var previous = this.environment;
        try {
            this.environment = environment;

            for (final var statement : statements) {
                execute(statement);
            }
        } finally {
            this.environment = previous;
        }
    }

    public Object evaluate(final Expr expr) {
        return switch (expr) {
            case Expr.Assignment(Token name, Expr expression) -> {
                final var value = evaluate(expression);
                environment.assign(name, value);
                yield value;
            }
            case Expr.Binary(Expr leftExpr, Token operator, Expr rightExpr) -> {
                final var left = evaluate(leftExpr);
                final var right = evaluate(rightExpr);

                yield switch (operator.type()) {
                    case BANG_EQUAL -> !Objects.equals(left, right);
                    case EQUAL_EQUAL -> Objects.equals(left, right);
                    case GREATER -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() > rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() > rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new IllegalStateException("Invalid operands.");
                    }
                    case GREATER_EQUAL -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() >= rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() >= rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new IllegalStateException("Invalid operands.");
                    }
                    case LESS -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() < rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() < rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new IllegalStateException("Invalid operands.");
                    }
                    case LESS_EQUAL -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() <= rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() <= rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new IllegalStateException("Invalid operands.");
                    }
                    case PLUS -> {
                        // if any is a string, concatenate
                        if (left instanceof String || right instanceof String) {
                            yield left.toString() + right.toString();
                        }

                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() + rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() + rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is
                        // and the other isn't a string, so we throw
                        throw new IllegalStateException("Invalid operands.");
                    }
                    case MINUS -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() - rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() - rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new IllegalStateException("Invalid operands.");
                    }
                    case SLASH -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() / rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() / rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new IllegalStateException("Invalid operands.");
                    }
                    case STAR  -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() * rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() * rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new IllegalStateException("Invalid operands.");
                    }
                    default -> throw new IllegalStateException("Invalid binary operator.");
                };
            }
            case Expr.Grouping(Token paren, Expr expression) ->
                    evaluate(expression);
            case Expr.If(Token paren, Expr condition, Expr thenExpr, Expr elseExpr) ->
                    ensureBoolean(paren, evaluate(condition))
                        ? evaluate(thenExpr)
                        : evaluate(elseExpr);
            case Expr.Literal(Object value) ->
                    value;
            case Expr.Logical(Expr leftExpr, Token operator, Expr rightExpr) -> {
                final var left = evaluate(leftExpr);

                yield switch (operator.type()) {
                //  or short circuits to true if left is true  and evaluates right if left is false
                // and short circuits to true if left is false and evaluates right if left is  true
                    case OR  ->  ensureBoolean(operator, left) ? true  : evaluate(rightExpr);
                    case AND -> !ensureBoolean(operator, left) ? false : evaluate(rightExpr);
                    default  -> throw new IllegalStateException("Invalid logical operator.");
                };
            }
            case Expr.Unary(Token operator, Expr right) -> {
                final var value = evaluate(right);

                yield switch (operator.type()) {
                    case MINUS  -> switch (ensureNumber(operator, value)) {
                        case Double d  -> -d;
                        case Integer i -> -i;
                        default -> throw new IllegalStateException("Unsupported number type.");
                    };
                    case NOT    -> !ensureBoolean(operator, value);
                    case TYPEOF -> {
                        if (right instanceof Expr.Variable(Token name)) {
                            yield environment.get(name).typeDescriptor().descriptor();
                        }
                        yield value.getClass().getSimpleName();
                    }
                    default     -> throw new IllegalStateException("Unsupported unary operator.");
                };
            }
            case Expr.Variable(Token name) -> environment.get(name).value();
        };
    }

    private String stringify(final Object object) {
        return switch (object) {
            case null -> "Null";
            case Double _ -> {
                var text = object.toString();
                if (text.endsWith(".0")) {
                    text = text.substring(0, text.length() - 2);
                }
                yield text;
            }
            case Boolean _ -> {
                var text = object.toString();
                yield Character.toUpperCase(text.charAt(0)) + text.substring(1);
            }
            default -> object.toString();
        };
    }

    private static Number ensureNumber(final Token operator, final Object o) {
        if (o instanceof Number n) return n;
        throw new RuntimeError(operator, "Operand must be a number.");
    }

    private static boolean ensureBoolean(final Token operator, final Object o) {
        if (o instanceof Boolean b) return b;
        throw new RuntimeError(operator, "Operand must be a boolean.");
    }
}
