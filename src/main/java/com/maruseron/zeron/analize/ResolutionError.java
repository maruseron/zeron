package com.maruseron.zeron.analize;

import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.DiagnosticHelp;
import com.maruseron.zeron.diagnostic.SourceSpan;
import com.maruseron.zeron.scan.Token;

import java.util.List;
import java.util.Objects;

public final class ResolutionError extends RuntimeException {
    public final Token token;
    public final String sourcePath;
    private final Diagnostic diagnostic;

    public ResolutionError(DiagnosticCatalog.Entry catalogEntry, Token token, String message) {
        this(catalogEntry, token, message, null);
    }

    public ResolutionError(DiagnosticCatalog.Entry catalogEntry,
                           Token token,
                           String message,
                           String sourcePath) {
        this(Diagnostic.atToken(Objects.requireNonNull(catalogEntry),
                Objects.requireNonNull(token), sourcePath, message), token);
    }

    private ResolutionError(final Diagnostic diagnostic, final Token token) {
        super(diagnostic.message());
        this.diagnostic = Objects.requireNonNull(diagnostic);
        this.token = token;
        this.sourcePath = diagnostic.primarySpan().sourcePath();
    }

    private ResolutionError(final Diagnostic diagnostic) {
        this(diagnostic, null);
    }

    public static ResolutionError withHelp(final DiagnosticCatalog.Entry catalogEntry,
                                           final Token token,
                                           final String message,
                                           final DiagnosticHelp help) {
        final var diagnostic = Diagnostic.atToken(
            Objects.requireNonNull(catalogEntry),
            Objects.requireNonNull(token), null, message).withHelps(List.of(Objects.requireNonNull(help)));
        return new ResolutionError(diagnostic, token);
    }

    public static ResolutionError fromDiagnostic(final Diagnostic diagnostic) {
        return new ResolutionError(diagnostic);
    }

    public Diagnostic toDiagnostic(final String fallbackSourcePath) {
        final var span = diagnostic.primarySpan();
        final var diagnosticSourcePath = span.sourcePath() == null ? fallbackSourcePath : span.sourcePath();
        if (Objects.equals(span.sourcePath(), diagnosticSourcePath)) return diagnostic;

        return new Diagnostic(diagnostic.code(), diagnostic.severity(), diagnostic.message(),
                new SourceSpan(diagnosticSourcePath, span.start(), span.end()),
                diagnostic.labels(), diagnostic.notes(), diagnostic.helps());
    }
}