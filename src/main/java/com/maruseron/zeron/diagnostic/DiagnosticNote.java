package com.maruseron.zeron.diagnostic;

import java.util.Objects;

public record DiagnosticNote(String message) {
    public DiagnosticNote {
        Objects.requireNonNull(message);
    }
}
