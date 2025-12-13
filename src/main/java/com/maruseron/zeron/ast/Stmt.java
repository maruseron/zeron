package com.maruseron.zeron.ast;

import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.List;

public sealed interface Stmt {
    sealed interface Decl {}

    record Block(List<Stmt> statements) implements Stmt {}

    record Break(Token keyword) implements Stmt {}

    record Expression(Expr expression) implements Stmt {}

    record For(Token iterationBind, Token in, Expr iterable, Stmt body) implements Stmt {}

    record Function(Token name, List<Token> parameters,
                    com.maruseron.zeron.domain.Function typeDescriptor, List<Stmt> body) implements Stmt, Decl {}

    record If(Token paren, Expr condition, Stmt thenBranch, Stmt elseBranch) implements Stmt {}

    record Print(Expr expression) implements Stmt {}

    record Return(Expr value) implements Stmt {}

    record Var(Token name, TypeDescriptor type, Expr initializer, boolean isFinal) implements Stmt, Decl {}

    record While(Token keyword, Expr condition, Stmt body) implements Stmt {}
}
