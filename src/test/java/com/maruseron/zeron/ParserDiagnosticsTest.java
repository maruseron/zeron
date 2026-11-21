package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.GenericDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void rejectsMutableAdmissibilityForEffectAndHigherKindedParameters() {
        final var effectResult = Parser.of(Scanner.from(
                "fn invalid<E admits effect + mutable>(value: E): E performs E = value;").scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("effect-mut.zn");
        assertEquals("ZR1105", effectResult.diagnostics().getFirst().code().toString());

        final var higherKindedResult = Parser.of(Scanner.from(
                "fn invalid<F<_> admits mutable>(value: Int): Int = value;").scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("hkt-mut.zn");
        assertEquals("ZR1105", higherKindedResult.diagnostics().getFirst().code().toString());
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
                import zeron.lang.Display;
                fn stringify<T: Display>(value: T): String = "";
                """).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("display-bound.zn");

        assertEquals(result.diagnostics().toString(), 0, result.diagnostics().size());
        final var function = (Stmt.Function) result.compilationUnit().declarations().getFirst();
        assertEquals("Display",
                function.typeDescriptor().typeParameters().getFirst().bound().name());
        assertEquals("T", function.typeDescriptor().parameters().getFirst().name());
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
    public void parsesComprehensionSoftKeywordsAndAllowsThemAsNames() {
        final var result = Parser.of(Scanner.from("""
                fn map(value: Int): Int = value;
                fn flatMap(value: Int): Int = value;
                fn filter(value: Int): Int = value;
                fn collect(value: Int): Int = value;
                fn yield(value: Int): Int = value;
                fn into(value: Int): Int = value;
                fn stages(values: Array<Int>): Stream<Int> =
                    for item in values
                        let positive = item
                        if positive > 0
                        for expanded in [positive]
                        yield expanded;
                fn collected(values: Array<Int>) =
                    for item in values
                        collect item into ListSink<Int>.empty();
                """).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("comprehension-soft-keywords.zn");

        assertEquals(result.diagnostics().toString(), 0, result.diagnostics().size());
    }

    @Test
    public void reportsRemovedRaiseAndRaisesSpellings() {
        final var raiseResult = Parser.of(Scanner.from(
                "fn f(): Unit { raise Failure.new(); }")
                .scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("raise.zn");
        assertFalse(raiseResult.diagnostics().isEmpty());
        assertTrue(raiseResult.diagnostics().getFirst().message()
                .contains("'raise' was removed; use 'perform'."));

        final var raisesResult = Parser.of(Scanner.from(
                "fn f(): Unit raises IoFailure {}")
                .scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("raises.zn");
        assertFalse(raisesResult.diagnostics().isEmpty());
        assertTrue(raisesResult.diagnostics().getFirst().message()
                .contains("'raises' was removed; use 'performs'."));

        final var memberResult = Parser.of(Scanner.from(
                "fn f(): Unit = IoFailure.raise(\"message\");")
                .scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("member.zn");
        assertEquals(memberResult.diagnostics().toString(), 0, memberResult.diagnostics().size());
    }

    @Test
    public void parsesAdmitsClauseAndLeadingReifiedCapability() {
        final var result = Parser.of(Scanner.from("""
                fn reifiedBox<reified T: Render admits nullable>(value: T): T = value;
                fn mutableBox<T: Render admits mutable>(value: T): T = value;
                fn effectful<E: Responds<Never> admits effect...>(
                    callback: () -> Unit performs E): Unit performs E = callback();
                fn existential(box: Box<reified _: Render admits nullable>): Unit = ();
                fn markerOnly(box: Box<: admits nullable>): Unit = ();
                """).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("admits.zn");

        assertEquals(result.diagnostics().toString(), 0, result.diagnostics().size());
        final var reified = (Stmt.Function) result.compilationUnit().declarations().get(0);
        final var reifiedParameter = reified.typeDescriptor().typeParameters().getFirst();
        assertTrue(reifiedParameter.isReified());
        assertTrue(reifiedParameter.admitsNullableArguments());
        assertEquals("Render", reifiedParameter.bound().name());

        final var mutable = (Stmt.Function) result.compilationUnit().declarations().get(1);
        assertTrue(mutable.typeDescriptor().typeParameters().getFirst().admitsMutableArguments());

        final var effectful = (Stmt.Function) result.compilationUnit().declarations().get(2);
        assertTrue(effectful.typeDescriptor().typeParameters().getFirst().isEffectRowKind());

        final var existential = (Stmt.Function) result.compilationUnit().declarations().get(3);
        final var existentialType = (GenericDescriptor) existential.typeDescriptor()
                .parameters().getFirst();
        final var anonymous = (TypeParameterDescriptor) existentialType.typeParameters().getFirst();
        assertTrue(anonymous.isReified());
        assertTrue(anonymous.admitsNullableArguments());

        final var markerOnly = (Stmt.Function) result.compilationUnit().declarations().get(4);
        final var markerOnlyType = (GenericDescriptor) markerOnly.typeDescriptor()
                .parameters().getFirst();
        assertTrue(((TypeParameterDescriptor) markerOnlyType.typeParameters().getFirst())
                .admitsNullableArguments());
    }

    @Test
    public void reportsLegacyTypeParameterAndExistentialMarkerSpellings() {
        final var parameterResult = Parser.of(Scanner.from(
                "fn old<T: nullable>(value: T): T = value;")
                .scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("legacy-parameter.zn");
        assertFalse(parameterResult.diagnostics().isEmpty());
        assertTrue(parameterResult.diagnostics().toString(),
                parameterResult.diagnostics().getFirst().message()
                        .contains("write 'T admits nullable'"));

        final var existentialResult = Parser.of(Scanner.from(
                "fn old(box: Box<: reified>): Unit = ();")
                .scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("legacy-existential.zn");
        assertFalse(existentialResult.diagnostics().isEmpty());
        assertTrue(existentialResult.diagnostics().getFirst().message()
                .contains("'reified' is a leading capability"));
    }

    @Test
    public void rejectsResponseIndexedEffectMarker() {
        final var result = Parser.of(Scanner.from(
                "fn old<E: Responds<Never> admits effect<Never>>(): Unit = ();")
                .scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("effect-response.zn");

        assertFalse(result.diagnostics().isEmpty());
        assertTrue(result.diagnostics().getFirst().message()
                .contains("use 'Responds<R>' in the bound list"));
    }

    @Test
    public void rejectsRemovedOperatorStageForExpressionSyntax() {
        final var result = Parser.of(Scanner.from("""
                let values = for [1, 2, 3]
                    map item -> item * 2;
                """).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("removed-for-stages.zn");

        assertFalse(result.diagnostics().isEmpty());
        assertEquals("ZR1101", result.diagnostics().getFirst().code().toString());
    }
}
