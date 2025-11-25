package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

public record Binary(Expr left, Token operator, Expr right) implements Expr {}