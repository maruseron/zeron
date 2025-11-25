package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

public record Assignment(Token name, Expr value) implements Expr {
}
