package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

public record Unary(Token operator, Expr right) implements Expr {}
