package com.maruseron.zeron.scan;

public record SuffixableNumber(Kind kind, int radix) {
    public enum Kind { INTEGER, DECIMAL }
}
