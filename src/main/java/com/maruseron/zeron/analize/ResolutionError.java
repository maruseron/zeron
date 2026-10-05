package com.maruseron.zeron.analize;

import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.scan.Token;

import java.util.Objects;

public final class ResolutionError extends RuntimeException {
    public final Token token;
    public final String sourcePath;
    private final DiagnosticCatalog.Entry catalogEntry;
    private final Diagnostic diagnostic;

    public ResolutionError(DiagnosticCatalog.Entry catalogEntry, Token token, String message) {
        this(catalogEntry, token, message, null);
    }

    public ResolutionError(DiagnosticCatalog.Entry catalogEntry,
                           Token token,
                           String message,
                           String sourcePath) {
        super(message);
        this.catalogEntry = Objects.requireNonNull(catalogEntry);
        this.token = Objects.requireNonNull(token);
        this.sourcePath = sourcePath;
        this.diagnostic = null;
    }

    private ResolutionError(final Diagnostic diagnostic) {
        super(diagnostic.message());
        this.token = null;
        this.sourcePath = diagnostic.primarySpan().sourcePath();
        this.catalogEntry = null;
        this.diagnostic = diagnostic;
    }

    public static ResolutionError fromDiagnostic(final Diagnostic diagnostic) {
        return new ResolutionError(diagnostic);
    }

    public Diagnostic toDiagnostic(final String fallbackSourcePath) {
        if (diagnostic != null) return diagnostic;
        return Diagnostic.atToken(catalogEntry, token,
                sourcePath == null ? fallbackSourcePath : sourcePath, getMessage());
    }
}