package com.maruseron.zeron.domain;

public enum TypeModifier {
    MUTABLE("&"),
    NULLABLE("?"),
    TYPE_PARAMETER("#");

    public final String symbol;

    TypeModifier(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }
}
