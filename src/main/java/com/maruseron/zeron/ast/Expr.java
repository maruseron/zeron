package com.maruseron.zeron.ast;

import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.List;

public sealed interface Expr {

    TypeDescriptor type();
    Expr withType(TypeDescriptor type);

    record Assignment(Token name, Expr value, TypeDescriptor type) implements Expr {
        public Assignment withType(TypeDescriptor type) {
            return new Assignment(name, value, type);
        }
    }

    record Binary(Expr left, Token operator, Expr right, TypeDescriptor type) implements Expr {
        public Binary withType(TypeDescriptor type) {
            return new Binary(left, operator, right, type);
        }
    }

    record Call(Token callee, Token paren, List<Expr> arguments, TypeDescriptor type) implements Expr {
        public Call withType(TypeDescriptor type) {
            return new Call(callee, paren, arguments, type);
        }
    }

    record Grouping(Token paren, Expr expression, TypeDescriptor type) implements Expr {
        public Grouping withType(TypeDescriptor type) {
            return new Grouping(paren, expression, type);
        }
    }

    record If(Token paren, Expr condition, Expr thenExpr, Expr elseExpr, TypeDescriptor type) implements Expr {
        public If withType(TypeDescriptor type) {
            return new If(paren, condition, thenExpr, elseExpr, type);
        }
    }

    record Lambda(Token arrow, List<Token> params, List<Stmt> body, TypeDescriptor type) implements Expr {
        public Lambda withType(TypeDescriptor type) {
            return new Lambda(arrow, params, body, type);
        }
    }

    record Literal(Object value, TypeDescriptor type) implements Expr {
        public Literal withType(TypeDescriptor type) {
            return new Literal(value, type);
        }
    }

    record Logical(Expr left, Token operator, Expr right) implements Expr {
        public TypeDescriptor type() {
            return new TypeDescriptor(":Boolean");
        }

        public Logical withType(TypeDescriptor type) {
            throw new UnsupportedOperationException(
                    "Logical expressions always resolve to boolean.");
        }
    }

    record Unary(Token operator, Expr right, TypeDescriptor type) implements Expr {
        public Unary withType(TypeDescriptor type) {
            return new Unary(operator, right, type);
        }
    }

    record Variable(Token name, TypeDescriptor type) implements Expr {
        public Variable withType(TypeDescriptor type) {
            return new Variable(name, type);
        }
    }
}
