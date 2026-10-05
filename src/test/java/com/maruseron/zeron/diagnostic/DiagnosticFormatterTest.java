package com.maruseron.zeron.diagnostic;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

public final class DiagnosticFormatterTest {
    @Test
    public void formatsCodedDiagnosticWithSourceExcerptUnderlineLabelsNotesAndHelp() {
        final var span = span("src/example.zn", 2, 9, 8, 2, 16, 15);
        final var diagnostic = new Diagnostic(
                DiagnosticCatalog.NAME_NOT_FOUND.code(),
                Severity.ERROR,
                "Name 'missing' was not found.",
                span,
                List.of(new DiagnosticLabel(span, "unknown name")),
                List.of(new DiagnosticNote("Names are case-sensitive.")),
                List.of(new DiagnosticHelp("Declare the value before using it.")));

        final var formatted = DiagnosticFormatter.format(diagnostic,
                (path, line) -> line == 2 ? "let x = missing;" : null);

        assertTrue(formatted.startsWith("src/example.zn:2:9: error[ZR2001]: Name 'missing' was not found."));
        assertTrue(formatted.contains(" 2 | let x = missing;"));
        assertTrue(formatted.contains("    |         ^~~~~~"));
        assertTrue(formatted.contains("  = note: Names are case-sensitive."));
        assertTrue(formatted.contains("  = help: Declare the value before using it."));
        assertTrue(formatted.contains("(src/example.zn:2:9)"));
    }

    @Test
    public void alignsCaretAfterTabsAndFormatsPathlessDiagnostics() {
        final var span = span(null, 1, 2, 1, 1, 3, 2);
        final var diagnostic = new Diagnostic(DiagnosticCatalog.EXPECTED_SYNTAX.code(), Severity.ERROR,
                "Expected expression.", span, List.of(), List.of(), List.of());

        final var formatted = DiagnosticFormatter.format(diagnostic, (path, line) -> "\t;");

        assertTrue(formatted.startsWith("<input>:1:2: error[ZR1101]: Expected expression."));
        assertTrue(formatted.contains(" 1 |     ;"));
        assertTrue(formatted.contains("    |     ^"));
    }

    private static SourceSpan span(final String path,
                                   final int startLine,
                                   final int startColumn,
                                   final int startOffset,
                                   final int endLine,
                                   final int endColumn,
                                   final int endOffset) {
        return new SourceSpan(path,
                new SourcePosition(startLine, startColumn, startOffset),
                new SourcePosition(endLine, endColumn, endOffset));
    }
}
