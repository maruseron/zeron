package com.maruseron.zeron.analize;

import com.maruseron.zeron.diagnostic.Diagnostic;

import java.util.ArrayList;
import java.util.List;

final class ResolutionDiagnostics {
    private final List<Diagnostic> errors = new ArrayList<>();

    void record(final ResolutionError error, final String sourcePath) {
        errors.add(error.toDiagnostic(sourcePath));
    }

    List<Diagnostic> snapshot() {
        return List.copyOf(errors);
    }
}
