package com.maruseron.zeron.interpret;

import com.maruseron.zeron.IntRangeLiteral;
import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Interpreter {
    final Environment globals = new Environment();
    private Environment environment = globals;

    public Interpreter() {
        globals.define("clock",
                TypeDescriptor.functionOf("clock", TypeDescriptor.ofInt()),
                new ZeronCallable() {
                    @Override public String toString() { return "<native fn clock>"; }
                    @Override public int arity() { return 0; }
                    @Override public Object call(Interpreter interpreter, List<Object> arguments) {
                        return (int)(System.currentTimeMillis() / 1000L);
                    }
                }, true, true);

    }

    public void interpret(final List<Stmt> statements) {
        try {
            for (final var statement : statements) {
                try {
                    execute(statement);
                } catch (BreakException _) {}
            }
        } catch (RuntimeError error) {
            Zeron.runtimeError(error);
        }
    }

    public void execute(final Stmt stmt) {
        switch (stmt) {
            case Stmt.Block(List<Stmt> statements) ->
                    executeBlock(statements, new Environment(environment));
            case Stmt.Break(Token keyword) -> throw new BreakException(keyword);
            case Stmt.Expression(Expr expression) ->
                    evaluate(expression);
            case Stmt.For(Token iterationBind, Token in, Expr iterable, Stmt body) -> {
                if (!(evaluate(iterable) instanceof IntRangeLiteral range))
                    throw new RuntimeError(in, "Only ranges can be iterated.");

                /*
                    for (let i in expr) body desugars to:

                    {
                        iterator iter = Iterator(expr)
                        while (iter.hasNext()) {
                            let i = iter.next()
                            execute(body)
                        }
                    }
                 */

                executeOverRange(range, iterationBind, body, new Environment(environment));
            }
            case Stmt.Function fn -> {
                System.out.println(fn);
                throw new UnsupportedOperationException("not implemented yet");
            }
            case Stmt.If(Token paren, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                if (ensureBoolean(paren, evaluate(condition))) {
                    execute(thenBranch);
                } else if (elseBranch != null) {
                    execute(elseBranch);
                }
            }
            case Stmt.Print(Expr expression) ->
                    System.out.println(evaluate(expression));
            case Stmt.Return(Expr value) ->
                throw new IllegalStateException("not implemented yet");
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
            case Stmt.While(Token keyword, Expr condition, Stmt body) -> {
                switch (keyword.type()) {
                    case LOOP -> { while (true) execute(body); }
                    case WHILE, UNTIL -> {
                        while (ensureBoolean(keyword, evaluate(condition))) {
                            execute(body);
                        }
                    }
                }
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

    void executeOverRange(final IntRangeLiteral range,
                          final Token iterationBind,
                          final Stmt body,
                          final Environment environment) {
        final var previous = this.environment;
        try {
            this.environment = environment;
            for (final var i : range) {
                // create synthetic variable with the iteration bind name
                // and set it to the current iteration value.
                // then run the body
                execute(new Stmt.Var(
                        iterationBind,
                        TypeDescriptor.ofInt(),
                        new Expr.Literal(i, TypeDescriptor.ofInt()),
                        true));
                execute(body);
            }
        } finally {
            this.environment = previous;
        }
    }

    public Object evaluate(final Expr expr) {
        return switch (expr) {
            case Expr.Assignment assignment -> {
                final var value = evaluate(assignment.value);
                environment.assign(assignment.name, value);
                yield value;
            }
            case Expr.Binary binary -> {
                final var left = evaluate(binary.left);
                final var right = evaluate(binary.right);

                yield switch (binary.operator.type()) {
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
                        throw new RuntimeError(binary.operator, "Invalid operands.");
                    }
                    case GREATER_EQUAL -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() >= rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() >= rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new RuntimeError(binary.operator, "Invalid operands.");
                    }
                    case LESS -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() < rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() < rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new RuntimeError(binary.operator, "Invalid operands.");
                    }
                    case LESS_EQUAL -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() <= rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() <= rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new RuntimeError(binary.operator, "Invalid operands.");
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
                        throw new RuntimeError(binary.operator, "Invalid operands.");
                    }
                    case MINUS -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() - rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() - rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new RuntimeError(binary.operator, "Invalid operands.");
                    }
                    case SLASH -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() / rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() / rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new RuntimeError(binary.operator, "Invalid operands.");
                    }
                    case STAR  -> {
                        // if both are a number, check leftmost and convert both to that type
                        if (left instanceof Number ln && right instanceof Number rn) {
                            if (left instanceof Integer) yield ln.intValue() * rn.intValue();
                            if (left instanceof Double)  yield ln.doubleValue() * rn.doubleValue();
                        }

                        // if we've reached here, either none of them are numbers or only one is,
                        // throw
                        throw new RuntimeError(binary.operator, "Invalid operands.");
                    }
                    default -> throw new RuntimeError(binary.operator, "Invalid binary operator.");
                };
            }
            case Expr.Call call -> {
                final var callee    = evaluate(null);
                final var arguments = new ArrayList<>();
                for (final var argumentExpr : call.arguments) {
                    arguments.add(evaluate(argumentExpr));
                }

                if (!(callee instanceof ZeronCallable callable)) {
                    throw new RuntimeError(call.paren, "Callee must be a function.");
                }

                if (arguments.size() != callable.arity()) {
                    throw new RuntimeError(call.paren, "Expected " + callable.arity() + " arguments, " +
                            "but got " + arguments.size() + " instead.");
                }

                yield callable.call(this, arguments);
            }
            case Expr.Grouping grouping ->
                    evaluate(grouping.expression);
            case Expr.If iff ->
                    ensureBoolean(iff.paren, evaluate(iff.condition))
                        ? evaluate(iff.thenExpr)
                        : evaluate(iff.elseExpr);
            case Expr.Lambda lambda ->
                throw new IllegalStateException("not implemented yet");
            case Expr.Literal literal ->
                    literal.value;
            case Expr.Logical logical -> {
                final var left = evaluate(logical.left);

                yield switch (logical.operator.type()) {
                //  or short circuits to true if left is true  and evaluates right if left is false
                // and short circuits to true if left is false and evaluates right if left is  true
                    case OR  ->  ensureBoolean(logical.operator, left)
                            ? true  : evaluate(logical.right);
                    case AND -> !ensureBoolean(logical.operator, left)
                            ? false : evaluate(logical.right);
                    default  -> throw new RuntimeError(logical.operator, "Invalid logical operator.");
                };
            }
            case Expr.Unary unary -> {
                final var value = evaluate(unary.right);

                yield switch (unary.operator.type()) {
                    case MINUS  -> switch (ensureNumber(unary.operator, value)) {
                        case Double d  -> -d;
                        case Integer i -> -i;
                        default -> throw new IllegalStateException("Unsupported number type.");
                    };
                    case NOT    -> !ensureBoolean(unary.operator, value);
                    case TYPEOF -> {
                        if (unary.right instanceof Expr.Variable variable) {
                            yield environment.get(variable.name).typeDescriptor().descriptor();
                        }
                        yield value.getClass().getSimpleName();
                    }
                    default     -> throw new IllegalStateException("Unsupported unary operator.");
                };
            }
            case Expr.Variable variable -> environment.get(variable.name).value();
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
