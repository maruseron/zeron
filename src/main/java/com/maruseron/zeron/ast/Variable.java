package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

public record Variable(Token name) implements Expr {}
