package com.maruseron.zeron.compile;

import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.Severity;

import java.util.List;

public record CompilationResult(List<Diagnostic> errors, List<Diagnostic> warnings) {
    public CompilationResult {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public CompilationResult(final List<Diagnostic> diagnostics) {
        this(diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity() == Severity.ERROR).toList(),
                diagnostics.stream()
                        .filter(diagnostic -> diagnostic.severity() == Severity.WARNING).toList());
    }

    public boolean successful() {
        return errors.isEmpty();
    }

    /// Retained for callers that only care about build-blocking diagnostics.
    public List<Diagnostic> diagnostics() {
        return errors;
    }
}
