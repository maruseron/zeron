package com.maruseron.zeron.diagnostic;

import com.maruseron.zeron.scan.Token;

import java.util.List;
import java.util.Objects;

public record Diagnostic(
        DiagnosticCode code,
        Severity severity,
        String message,
        SourceSpan primarySpan,
        List<DiagnosticLabel> labels,
        List<DiagnosticNote> notes,
        List<DiagnosticHelp> helps) {

    public Diagnostic {
        Objects.requireNonNull(code);
        Objects.requireNonNull(severity);
        Objects.requireNonNull(message);
        Objects.requireNonNull(primarySpan);
        labels = List.copyOf(labels);
        notes = List.copyOf(notes);
        helps = List.copyOf(helps);
    }

    public Diagnostic withHelps(List<DiagnosticHelp> helps) {
        return new Diagnostic(code, severity, message, primarySpan, labels, notes, helps);
    }

    public static Diagnostic atToken(final DiagnosticCatalog.Entry entry,
                                     final Token token,
                                     final String sourcePath,
                                     final String message) {
        final var tokenSpan = token.span();
        final var span = tokenSpan.sourcePath() != null || sourcePath == null
                ? tokenSpan
                : new SourceSpan(sourcePath, tokenSpan.start(), tokenSpan.end());
        return new Diagnostic(entry.code(), entry.severity(), message, span, List.of(), List.of(), List.of());
    }
}
