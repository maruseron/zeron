package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

public record Let(Token name, Token type, Expr initializer, boolean isFinal) implements Stmt {}
