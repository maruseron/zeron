package com.maruseron.zeron.diagnostic;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class DiagnosticTest {
    @Test
    public void retainsStructuredLocationLabelsNotesAndHelpAsImmutableLists() {
        final var labels = new ArrayList<>(List.of(
                new DiagnosticLabel(SourceSpan.line("src/main.zn", 2), "related declaration")));
        final var notes = new ArrayList<>(List.of(new DiagnosticNote("additional context")));
        final var helps = new ArrayList<>(List.of(new DiagnosticHelp("add an explicit type")));
        final var diagnostic = new Diagnostic(
                new DiagnosticCode("ZR2001"),
                Severity.ERROR,
                "cannot infer this type",
                SourceSpan.line("src/main.zn", 4),
                labels,
                notes,
                helps);
        labels.clear();
        notes.clear();
        helps.clear();

        assertEquals("ZR2001", diagnostic.code().toString());
        assertEquals("src/main.zn", diagnostic.primarySpan().sourcePath());
        assertEquals(4, diagnostic.primarySpan().start().line());
        assertEquals(1, diagnostic.labels().size());
        assertEquals(1, diagnostic.notes().size());
        assertEquals(1, diagnostic.helps().size());
        assertThrows(UnsupportedOperationException.class,
                () -> diagnostic.labels().add(new DiagnosticLabel(
                        SourceSpan.line("src/other.zn", 1), "another label")));
    }

    @Test
    public void validatesDiagnosticCodesAndSourceSpanOrdering() {
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticCode("ZR20"));
        assertThrows(IllegalArgumentException.class, () -> new SourceSpan("src/main.zn",
                new SourcePosition(3, 5, 20), new SourcePosition(3, 4, 21)));
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnosticHelp("replace this", null, "value"));
    }
}
