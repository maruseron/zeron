package com.maruseron.zeron.compile;

import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.ast.Expr;

import java.lang.constant.ConstantDesc;
import java.lang.constant.Constable;

final class ConstantFolder {
    private ConstantFolder() {}

    static boolean isConstantFieldValue(final TypeDescriptor type, final ConstantDesc value) {
        if (value == null) return false;
        return switch (TypeDescriptor.toJavaClassDesc(type).descriptorString()) {
            case "I", "J", "Z", "D", "Ljava/lang/String;" -> true;
            default -> false;
        };
    }

    static ConstantDesc fold(final Expr expr) {
        return switch (expr) {
            case Expr.Literal lit -> {
                if (lit.value instanceof Boolean b) yield b ? 1 : 0;
                if (lit.value instanceof Constable constable) yield constable.describeConstable().orElse(null);
                else yield null;
            }
            case Expr.Grouping grouping -> fold(grouping.expression);
            case Expr.Unary unary -> foldUnary(unary);
            case Expr.Binary bin -> foldBinary(bin);
            case Expr.Logical logical -> foldLogical(logical);
            case null, default -> null;
        };
    }

    private static ConstantDesc foldUnary(final Expr.Unary unary) {
        final var operand = fold(unary.right);
        if (operand == null) return null;
        final var operator = unary.operator.type();
        if (operator == com.maruseron.zeron.scan.TokenType.PLUS) return operand;
        if (operator == com.maruseron.zeron.scan.TokenType.BANG
                || operator == com.maruseron.zeron.scan.TokenType.NOT) {
            return operand instanceof Integer value && isBooleanValue(value) ? (value == 0 ? 1 : 0) : null;
        }
        if (operator == com.maruseron.zeron.scan.TokenType.TILDE) {
            return switch (operand) {
                case Integer integer -> ~integer;
                case Long integer -> ~integer;
                default -> null;
            };
        }
        if (operator != com.maruseron.zeron.scan.TokenType.MINUS) return null;
        return switch (operand) {
            case Integer integer -> -integer;
            case Long integer -> -integer;
            case Double floating -> -floating;
            default -> null;
        };
    }

    private static ConstantDesc foldLogical(final Expr.Logical logical) {
        final var left = fold(logical.left);
        final var right = fold(logical.right);
        if (left == null || right == null) return null;
        if (!(left instanceof Integer leftInteger) || !(right instanceof Integer rightInteger)) return null;
        if (!isBooleanValue(leftInteger) || !isBooleanValue(rightInteger)) return null;
        final var leftValue = leftInteger == 1;
        final var rightValue = rightInteger == 1;
        return switch (logical.operator.type()) {
            case AND -> leftValue && rightValue ? 1 : 0;
            case OR -> leftValue || rightValue ? 1 : 0;
            default -> null;
        };
    }

    private static ConstantDesc foldBinary(final Expr.Binary bin) {
        final var left = fold(bin.left);
        final var right = fold(bin.right);
        if (left == null || right == null) return null;
        final var operator = bin.operator.type();
        if (operator == com.maruseron.zeron.scan.TokenType.EQUAL_EQUAL
                || operator == com.maruseron.zeron.scan.TokenType.BANG_EQUAL) {
            final var equal = switch (left) {
                case Integer leftInteger -> right instanceof Integer rightInteger
                        && leftInteger.intValue() == rightInteger.intValue();
                case Long leftLong -> right instanceof Long rightLong
                        && leftLong.longValue() == rightLong.longValue();
                case Double leftFloat -> right instanceof Double rightFloat
                        && Double.compare(leftFloat, rightFloat) == 0;
                case String leftString -> right instanceof String rightString
                        && leftString.equals(rightString);
                default -> false;
            };
            final var result = operator == com.maruseron.zeron.scan.TokenType.EQUAL_EQUAL ? equal : !equal;
            return result ? 1 : 0;
        }
        if (left.getClass() != right.getClass()) return null;
        return switch (left) {
            case Integer li -> foldInteger(operator, li, (Integer) right);
            case Long ll -> foldLong(operator, ll, (Long) right);
            case Double ld -> foldDouble(operator, ld, (Double) right);
            case String ls -> operator == com.maruseron.zeron.scan.TokenType.PLUS ? ls + right : null;
            default -> null;
        };
    }

    private static ConstantDesc foldInteger(final com.maruseron.zeron.scan.TokenType operator,
                                            final int left, final int right) {
        return switch (operator) {
            case PLUS -> left + right;
            case MINUS -> left - right;
            case STAR -> left * right;
            case SLASH -> right == 0 ? null : left / right;
            case PERCENT -> right == 0 ? null : left % right;
            case AMPERSAND -> left & right;
            case PIPE -> left | right;
            case CARET -> left ^ right;
            case SHIFT_LEFT -> left << right;
            case SHIFT_RIGHT -> left >> right;
            case UNSIGNED_SHIFT_RIGHT -> left >>> right;
            case LESS -> left < right ? 1 : 0;
            case LESS_EQUAL -> left <= right ? 1 : 0;
            case GREATER -> left > right ? 1 : 0;
            case GREATER_EQUAL -> left >= right ? 1 : 0;
            default -> null;
        };
    }

    private static ConstantDesc foldLong(final com.maruseron.zeron.scan.TokenType operator,
                                         final long left, final long right) {
        return switch (operator) {
            case PLUS -> left + right;
            case MINUS -> left - right;
            case STAR -> left * right;
            case SLASH -> right == 0 ? null : left / right;
            case PERCENT -> right == 0 ? null : left % right;
            case AMPERSAND -> left & right;
            case PIPE -> left | right;
            case CARET -> left ^ right;
            case SHIFT_LEFT -> left << right;
            case SHIFT_RIGHT -> left >> right;
            case UNSIGNED_SHIFT_RIGHT -> left >>> right;
            case LESS -> left < right ? 1 : 0;
            case LESS_EQUAL -> left <= right ? 1 : 0;
            case GREATER -> left > right ? 1 : 0;
            case GREATER_EQUAL -> left >= right ? 1 : 0;
            default -> null;
        };
    }

    private static ConstantDesc foldDouble(final com.maruseron.zeron.scan.TokenType operator,
                                           final double left, final double right) {
        return switch (operator) {
            case PLUS -> left + right;
            case MINUS -> left - right;
            case STAR -> left * right;
            case SLASH -> left / right;
            case PERCENT -> left % right;
            case LESS -> left < right ? 1 : 0;
            case LESS_EQUAL -> left <= right ? 1 : 0;
            case GREATER -> left > right ? 1 : 0;
            case GREATER_EQUAL -> left >= right ? 1 : 0;
            default -> null;
        };
    }

    private static boolean isBooleanValue(final int value) {
        return value == 0 || value == 1;
    }

    static TypeDescriptor typeForConstant(final ConstantDesc value) {
        return switch (value) {
            case Integer _ -> TypeDescriptor.ofInt();
            case Long _ -> TypeDescriptor.ofLong();
            case Double _ -> TypeDescriptor.ofFloat();
            case String _ -> TypeDescriptor.ofString();
            default -> throw new IllegalStateException();
        };
    }
}
