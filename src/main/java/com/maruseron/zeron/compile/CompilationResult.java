package com.maruseron.zeron.compile;

import com.maruseron.zeron.diagnostic.Diagnostic;

import java.util.List;

public record CompilationResult(List<Diagnostic> diagnostics) {
    public CompilationResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean successful() {
        return diagnostics.isEmpty();
    }
}
