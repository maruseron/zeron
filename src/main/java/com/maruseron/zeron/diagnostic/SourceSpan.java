package com.maruseron.zeron.diagnostic;

import java.util.Objects;

public record SourceSpan(String sourcePath, SourcePosition start, SourcePosition end) {
    public SourceSpan {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        if (end.line() < start.line()
                || (end.line() == start.line()
                && start.column() != null && end.column() != null
                && end.column() < start.column())
                || (start.offset() != null && end.offset() != null
                && end.offset() < start.offset())) {
            throw new IllegalArgumentException("Source span end must not precede its start.");
        }
    }

    public static SourceSpan line(final String sourcePath, final int line) {
        final var position = new SourcePosition(line);
        return new SourceSpan(sourcePath, position, position);
    }
}
