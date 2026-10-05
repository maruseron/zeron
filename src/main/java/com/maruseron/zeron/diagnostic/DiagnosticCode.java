package com.maruseron.zeron.diagnostic;

import java.util.Objects;

public record DiagnosticCode(String value) {
    public DiagnosticCode {
        Objects.requireNonNull(value);
        if (!value.matches("ZR[0-9]{4}")) {
            throw new IllegalArgumentException("Diagnostic codes must use the form ZR0000.");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
