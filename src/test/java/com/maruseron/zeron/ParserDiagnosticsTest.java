package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class ParserDiagnosticsTest {
    @Test
    public void collectsDeclarationErrorsAndContinuesParsingWithoutGlobalReporting() {
        final var previousError = Zeron.hadError;
        Zeron.hadError = false;
        try {
            final var result = Parser.of(Scanner.from("""
                    let broken = ;
                    let alsoBroken = ;
                    let valid = 42;
                    """).scanTokens()).parseCompilationUnitWithDiagnostics("parser.zn");

            assertEquals(2, result.diagnostics().size());
            assertEquals(1, result.compilationUnit().declarations().size());
            assertEquals("parser.zn", result.diagnostics().getFirst().primarySpan().sourcePath());
            assertEquals(1, result.diagnostics().getFirst().primarySpan().start().line());
            assertEquals(14, result.diagnostics().getFirst().primarySpan().start().column().intValue());
            assertEquals("ZR1101", result.diagnostics().getFirst().code().toString());
            assertFalse(Zeron.hadError);
        } finally {
            Zeron.hadError = previousError;
        }
    }

    @Test
    public void assignsCatalogCodesAtParserErrorSites() {
        final var externalResult = Parser.of(Scanner.from(
                "external fn identity<T>(value: T): T;").scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("external.zn");
        assertEquals("ZR1104", externalResult.diagnostics().getFirst().code().toString());

        final var arrayResult = Parser.of(Scanner.from("let values: Array;").scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("array.zn");
        assertEquals("ZR1111", arrayResult.diagnostics().getFirst().code().toString());

        final var syntaxResult = Parser.of(Scanner.from("let broken = ;").scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("syntax.zn");
        assertEquals("ZR1101", syntaxResult.diagnostics().getFirst().code().toString());
    }

    @Test
    public void reportsSyntaxErrorsInsideFunctionBodiesWithoutCrashing() {
        final var result = Parser.of(Scanner.from("""
                fn main(): Unit {
                    match (value) {
                        case Some<Int> item -> item;
                    }
                }
                """).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("match.zn");

        assertFalse(result.diagnostics().isEmpty());
        assertEquals("ZR1101", result.diagnostics().getFirst().code().toString());
    }
}
