package com.maruseron.zeron.diagnostic;

import java.util.Objects;

public record DiagnosticHelp(String message, SourceSpan span, String replacement) {
    public DiagnosticHelp {
        Objects.requireNonNull(message);
        if (replacement != null && span == null) {
            throw new IllegalArgumentException("A replacement requires a source span.");
        }
    }

    public DiagnosticHelp(final String message) {
        this(message, null, null);
    }
}
