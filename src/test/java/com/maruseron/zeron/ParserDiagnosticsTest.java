package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
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
                        case Option.Some(item) -> item;
                    }
                }
                """).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("match.zn");

        assertFalse(result.diagnostics().isEmpty());
        assertEquals("ZR1101", result.diagnostics().getFirst().code().toString());
    }

    @Test
    public void preservesUnqualifiedTypeNamesForResolution() {
        final var result = Parser.of(Scanner.from("""
                import zeron.collections.*;
                fn stringify<T: Display<T>>(value: T): String = "";
                """).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("display-bound.zn");

        assertEquals(result.diagnostics().toString(), 0, result.diagnostics().size());
        final var function = (Stmt.Function) result.compilationUnit().declarations().getFirst();
        assertEquals("Display",
                function.typeDescriptor().typeParameters().getFirst().bound().name());
    }

    @Test
    public void preservesCommentsInParseResultButExcludesThemFromDeclarations() {
        final var result = Parser.of(Scanner.from("""
                // leading comment
                let value = 1; /* trailing comment */
                """, "comments.zn").scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("comments.zn");

        assertEquals(result.diagnostics().toString(), 0, result.diagnostics().size());
        assertEquals(1, result.compilationUnit().declarations().size());
        assertEquals(2, result.comments().size());
        assertEquals("comments.zn", result.comments().getFirst().span().sourcePath());
    }

    @Test
    public void parsesPatternDeclarationsAndRecursiveOrPatterns() {
        final var result = Parser.of(Scanner.from("""
                class Box {
                    public pattern split(left: Int, right: Box) {
                        left = 1;
                        right = this;
                    }
                }
                fn extract(value: Box): Int = match (value) {
                    case Box.split(_, Box.split(item, _)) | Box.split(item, _) -> item;
                    case _ -> 0;
                };
                """).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("patterns.zn");

        assertEquals(result.diagnostics().toString(), 0, result.diagnostics().size());
        final var declaration = (Stmt.ClassDecl) result.compilationUnit().declarations().getFirst();
        assertEquals(1, declaration.patterns().size());
        assertEquals(2, declaration.patterns().getFirst().outputs().size());

        final var function = (Stmt.Function) result.compilationUnit().declarations().get(1);
        final var match = (Expr.Match) ((Stmt.Return) function.body().getFirst()).value();
        final var alternatives = match.arms.getFirst().pattern().alternatives();
        assertEquals(2, alternatives.size());
        assertEquals(2, alternatives.getFirst().arguments().size());
    }

    @Test
    public void parsesSoftKeywordsInContextAndAsIdentifiers() {
        final var result = Parser.of(Scanner.from("""
                sealed contract Choice permits Selected {}
                class Selected is Choice {}
                contract requirements {}
                contract Composed requires requirements {}
                class PatternBox {
                    public pattern permits(when: Int) when when > 0 {
                        permits = when;
                    }
                }
                fn permits(when: Int): Int = when;
                fn when(permits: Int): Int = permits;
                fn requires(value: Int): Int = value;
                """).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("soft-keywords.zn");

        assertEquals(result.diagnostics().toString(), 0, result.diagnostics().size());
    }

    @Test
    public void parsesForStageSoftKeywordsAndAllowsThemAsNames() {
        final var result = Parser.of(Scanner.from("""
                fn map(value: Int): Int = value;
                fn flatMap(value: Int): Int = value;
                fn filter(value: Int): Int = value;
                fn collect(value: Int): Int = value;
                fn stages(values: Array<Int>): Stream<Int> =
                    for values map item -> item
                    filter item -> item > 0
                    flatMap item -> [item]
                    collect ListSink<Int>.empty();
                """).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("for-stage-soft-keywords.zn");

        assertEquals(result.diagnostics().toString(), 0, result.diagnostics().size());
    }
}
