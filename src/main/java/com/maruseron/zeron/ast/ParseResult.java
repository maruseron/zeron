package com.maruseron.zeron.ast;

import com.maruseron.zeron.diagnostic.Diagnostic;

import java.util.List;
import java.util.Objects;

public record ParseResult(CompilationUnit compilationUnit, List<Diagnostic> diagnostics) {
    public ParseResult {
        Objects.requireNonNull(compilationUnit);
        diagnostics = List.copyOf(diagnostics);
    }
}
