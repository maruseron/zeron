package com.maruseron.zeron.ast;

public sealed interface Expr
        permits Assignment, Binary, Grouping, Literal, Unary, Variable {}
