package com.maruseron.zeron.scan;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ScannerLocationTest {
    @Test
    public void recordsPathOffsetsAndLineColumnsForTokensAndEof() {
        final var tokens = Scanner.from("let x = 1;\n  x", "src/main.zn").scanTokens();

        assertEquals("src/main.zn", tokens.getFirst().span().sourcePath());
        assertPosition(tokens.getFirst().span().start(), 1, 1, 0);
        assertPosition(tokens.getFirst().span().end(), 1, 4, 3);
        assertPosition(tokens.get(5).span().start(), 2, 3, 13);
        assertPosition(tokens.getLast().span().start(), 2, 4, 14);
        assertEquals(tokens.getLast().span().start(), tokens.getLast().span().end());
    }

    @Test
    public void treatsCrLfAsOneLineBreakAndPreservesUtf16Offsets() {
        final var tokens = Scanner.from("a\r\nb", "src/crlf.zn").scanTokens();

        assertPosition(tokens.get(1).span().start(), 2, 1, 3);
        assertPosition(tokens.getLast().span().start(), 2, 2, 4);
    }

    @Test
    public void distinguishesDotRangeAndEllipsisTokens() {
        final var tokens = Scanner.from(". .. ...").scanTokens();

        assertEquals(TokenType.DOT, tokens.get(0).type());
        assertEquals(TokenType.DOT_DOT, tokens.get(1).type());
        assertEquals(TokenType.ELLIPSIS, tokens.get(2).type());
        assertEquals(TokenType.EOF, tokens.getLast().type());
    }

    @Test
    public void collectsCodedScannerDiagnosticsWithoutReportingEagerly() {
        final var previousOutput = System.out;
        final var output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            final var result = Scanner.from("let café = 1;", "src/unicode.zn").scanWithDiagnostics();

            assertEquals(1, result.diagnostics().size());
            final var diagnostic = result.diagnostics().getFirst();
            assertEquals("ZR1001", diagnostic.code().toString());
            assertEquals("src/unicode.zn", diagnostic.primarySpan().sourcePath());
            assertPosition(diagnostic.primarySpan().start(), 1, 8, 7);
            assertPosition(diagnostic.primarySpan().end(), 1, 9, 8);
            assertTrue(output.toString(StandardCharsets.UTF_8).isEmpty());
        } finally {
            System.setOut(previousOutput);
        }
    }

    private static void assertPosition(final com.maruseron.zeron.diagnostic.SourcePosition position,
                                       final int line,
                                       final int column,
                                       final int offset) {
        assertEquals(line, position.line());
        assertEquals(Integer.valueOf(column), position.column());
        assertEquals(Integer.valueOf(offset), position.offset());
    }
}
