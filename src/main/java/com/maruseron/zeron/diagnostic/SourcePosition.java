package com.maruseron.zeron.diagnostic;

public record SourcePosition(int line, Integer column, Integer offset) {
    public SourcePosition {
        if (line < 1) throw new IllegalArgumentException("Source lines are one-based.");
        if (column != null && column < 1) {
            throw new IllegalArgumentException("Source columns are one-based.");
        }
        if (offset != null && offset < 0) {
            throw new IllegalArgumentException("Source offsets are zero-based.");
        }
    }

    public SourcePosition(final int line) {
        this(line, null, null);
    }
}
