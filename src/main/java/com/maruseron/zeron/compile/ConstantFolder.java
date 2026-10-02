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
            case "I", "Z", "D", "Ljava/lang/String;" -> true;
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
                    case Double floating -> -floating;
                    default -> null;
                };
            }
            case Expr.Binary bin -> {
                final var left = fold(bin.left);
                final var right = fold(bin.right);
                if (left == null || right == null) yield null;
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
            case Double _ -> TypeDescriptor.ofFloat();
            case String _ -> TypeDescriptor.ofString();
            default -> throw new IllegalStateException();
        };
    }
}