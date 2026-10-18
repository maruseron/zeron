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
                if (lit.value instanceof Constable constable) yield constable.describeConstable().orElseThrow();
                else yield null;
            }
            case Expr.Unary unary -> {
                final var operand = fold(unary.right);
                if (operand == null) yield null;
                if (unary.operator.type() == com.maruseron.zeron.scan.TokenType.PLUS) yield operand;
                if (unary.operator.type() != com.maruseron.zeron.scan.TokenType.MINUS) yield null;
                yield switch (operand) {
                    case Integer integer -> -integer;
                    case Long integer -> -integer;
                    case Double floating -> -floating;
                    default -> null;
                };
            }
            case Expr.Binary bin -> {
                final var left = fold(bin.left);
                final var right = fold(bin.right);
                if (left == null || right == null) yield null;
                if (bin.operator.type() == com.maruseron.zeron.scan.TokenType.EQUAL_EQUAL
                        || bin.operator.type() == com.maruseron.zeron.scan.TokenType.BANG_EQUAL) {
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
                    final var result = bin.operator.type()
                            == com.maruseron.zeron.scan.TokenType.EQUAL_EQUAL ? equal : !equal;
                    yield result ? 1 : 0;
                }
                if (left.getClass() != right.getClass()) yield null;
                switch (left) {
                    case Integer li -> {
                        yield switch (bin.operator.type()) {
                            case PLUS -> li + (Integer) right;
                            case MINUS -> li - (Integer) right;
                            case STAR -> li * (Integer) right;
                            case SLASH -> li / (Integer) right;
                            default -> throw new IllegalStateException();
                        };
                    }
                    case Double ld -> {
                        yield switch (bin.operator.type()) {
                            case PLUS -> ld + (Double) right;
                            case MINUS -> ld - (Double) right;
                            case STAR -> ld * (Double) right;
                            case SLASH -> ld / (Double) right;
                            default -> throw new IllegalStateException();
                        };
                    }
                    case Long ll -> {
                        yield switch (bin.operator.type()) {
                            case PLUS -> ll + (Long) right;
                            case MINUS -> ll - (Long) right;
                            case STAR -> ll * (Long) right;
                            case SLASH -> ll / (Long) right;
                            case PERCENT -> ll % (Long) right;
                            case AMPERSAND -> ll & (Long) right;
                            case PIPE -> ll | (Long) right;
                            case CARET -> ll ^ (Long) right;
                            case SHIFT_LEFT -> ll << ((Long) right).intValue();
                            case SHIFT_RIGHT -> ll >> ((Long) right).intValue();
                            case UNSIGNED_SHIFT_RIGHT -> ll >>> ((Long) right).intValue();
                            default -> throw new IllegalStateException();
                        };
                    }
                    case String ls -> {
                        yield switch (bin.operator.type()) {
                            case PLUS -> ls + right;
                            default -> throw new IllegalStateException();
                        };
                    }
                    default -> {}
                }
                throw new IllegalStateException();
            }
            case null, default -> null;
        };
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