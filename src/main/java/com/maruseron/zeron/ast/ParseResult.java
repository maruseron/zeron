package com.maruseron.zeron.ast;

import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.scan.Token;

import java.util.List;
import java.util.Objects;

public record ParseResult(CompilationUnit compilationUnit, List<Diagnostic> diagnostics,
                          List<Token> comments) {
    public ParseResult {
        Objects.requireNonNull(compilationUnit);
        diagnostics = List.copyOf(diagnostics);
        comments = List.copyOf(comments);
    }

    public ParseResult(final CompilationUnit compilationUnit, final List<Diagnostic> diagnostics) {
        this(compilationUnit, diagnostics, List.of());
    }
}
