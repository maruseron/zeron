package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

import java.util.List;

public sealed interface Expr {

    record Assignment(Token name, Expr value) implements Expr {}

    record Binary(Expr left, Token operator, Expr right) implements Expr {}

    record Call(Expr callee, Token paren, List<Expr> arguments) implements Expr {}

    record Grouping(Token paren, Expr expression) implements Expr {}

    record If(Token paren, Expr condition, Expr thenExpr, Expr elseExpr) implements Expr {}

    record Lambda(List<Token> params, Stmt body) implements Expr {}

    record Literal(Object value) implements Expr {}

    record Logical(Expr left, Token operator, Expr right) implements Expr {}

    record Unary(Token operator, Expr right) implements Expr {}

    record Variable(Token name) implements Expr {}
}
