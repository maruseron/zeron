package com.maruseron.zeron.diagnostic;

import java.util.Objects;

public record DiagnosticLabel(SourceSpan span, String message) {
    public DiagnosticLabel {
        Objects.requireNonNull(span);
        Objects.requireNonNull(message);
    }
}
