package com.maruseron.zeron.scan;

import com.maruseron.zeron.diagnostic.Diagnostic;

import java.util.List;
import java.util.Objects;

public record ScanResult(List<Token> tokens, List<Diagnostic> diagnostics) {
    public ScanResult {
        tokens = List.copyOf(tokens);
        diagnostics = List.copyOf(diagnostics);
        if (tokens.isEmpty() || tokens.getLast().type() != TokenType.EOF) {
            throw new IllegalArgumentException("A scan result must end with an EOF token.");
        }
    }
}
