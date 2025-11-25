package com.maruseron.zeron.ast;

public sealed interface Stmt
    permits Expression, Print, Let {
}
