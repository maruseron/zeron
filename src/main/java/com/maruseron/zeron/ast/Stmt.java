package com.maruseron.zeron.ast;

import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.List;

public sealed interface Stmt {

    record Expression(Expr expression) implements Stmt {}

    record If(Token paren, Expr condition, Stmt thenBranch, Stmt elseBranch) implements Stmt {}

    record Print(Expr expression) implements Stmt {}

    record Var(Token name, TypeDescriptor type, Expr initializer, boolean isFinal) implements Stmt {}

    record Block(List<Stmt> statements) implements Stmt {
    }
}
