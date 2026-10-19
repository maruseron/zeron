package com.maruseron.zeron.scan;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void scansContextualKeywordsAsIdentifiers() {
        final var tokens = Scanner.from("permits requires when with matches map flatMap filter collect get set")
                .scanTokens();

        assertEquals(TokenType.IDENTIFIER, tokens.get(0).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(1).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(2).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(3).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(4).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(5).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(6).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(7).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(8).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(9).type());
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

    @Test
    public void retainsCommentTokensWithTheirSourceRanges() {
        final var source = "// first\r\nlet value /* *x / still a comment */ = 1;";
        final var result = Scanner.from(source, "src/comments.zn").scanWithDiagnostics();

        assertEquals(TokenType.LINE_COMMENT, result.tokens().getFirst().type());
        assertEquals("// first", result.tokens().getFirst().lexeme());
        assertPosition(result.tokens().getFirst().span().start(), 1, 1, 0);
        assertPosition(result.tokens().getFirst().span().end(), 1, 9, 8);
        final var blockComment = result.tokens().stream()
                .filter(token -> token.type() == TokenType.BLOCK_COMMENT)
                .findFirst().orElseThrow();
        assertEquals("/* *x / still a comment */", blockComment.lexeme());
        assertEquals(0, result.diagnostics().size());
        assertTrue(result.tokens().stream().anyMatch(token -> token.type() == TokenType.EOF));
    }

    @Test
    public void returnsPartialTokensAndDiagnosticsForIncompleteInput() {
        final var stringResult = Scanner.from("\"unfinished", "src/string.zn").scanWithDiagnostics();
        assertEquals(TokenType.STRING, stringResult.tokens().getFirst().type());
        assertEquals("unfinished", stringResult.tokens().getFirst().literal());
        assertEquals("ZR1002", stringResult.diagnostics().getFirst().code().toString());
        assertEquals(1, stringResult.diagnostics().size());

        final var commentResult = Scanner.from("let value = 1; /* unfinished", "src/comment.zn")
                .scanWithDiagnostics();
        assertEquals(TokenType.BLOCK_COMMENT, commentResult.tokens().get(commentResult.tokens().size() - 2).type());
        assertEquals("ZR1003", commentResult.diagnostics().getFirst().code().toString());
        assertEquals(1, commentResult.diagnostics().size());
    }

    @Test
    public void reportsIntegerOverflowInsteadOfThrowing() {
        final var result = Scanner.from("2147483648", "src/integer.zn").scanWithDiagnostics();

        assertEquals(TokenType.INT, result.tokens().getFirst().type());
        assertEquals(0, result.tokens().getFirst().literal());
        assertEquals("ZR1004", result.diagnostics().getFirst().code().toString());
        assertEquals(1, result.diagnostics().size());
    }

    @Test
    public void scansScalarCharacterLiteralsAndSupportedEscapes() {
        final var result = Scanner.from("'A' '\\n' '\\'' '\\\\' '\\u{1F600}' '😀'")
                .scanWithDiagnostics();

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(List.of(65, 10, 39, 92, 0x1F600, 0x1F600),
                result.tokens().stream().filter(token -> token.type() == TokenType.CHAR)
                        .map(token -> (Integer) token.literal()).toList());
    }

    @Test
    public void rejectsInvalidCharacterLiteralScalarForms() {
        final var result = Scanner.from("'' 'ab' '\\u{}' '\\u{D800}' '\\u{110000}' '\\q'")
                .scanWithDiagnostics();

        assertEquals(6, result.diagnostics().size());
        assertTrue(result.diagnostics().stream()
                .allMatch(diagnostic -> diagnostic.code().toString().equals("ZR1005")));
    }

    @Test
    public void scannerContinuesAfterInvalidCharactersAndPreservesFollowingTokens() {
        final var result = Scanner.from("let x = 1; @ let y = 2;").scanWithDiagnostics();

        assertFalse(result.diagnostics().isEmpty());
        assertTrue(result.tokens().stream().anyMatch(token -> token.type() == TokenType.IDENTIFIER
                && token.lexeme().equals("y")));
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
