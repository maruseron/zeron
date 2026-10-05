package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ResolutionDiagnosticsTest {
    @Test
    public void reportsIndependentErrorsAfterAnInvalidImportWithoutReportingItsCascade() {
        final var unit = Parser.of(Scanner.from("""
                import zeron.lang.missing as unavailable;
                fn dependsOnUnavailableImport() {
                    unavailable();
                }
                fn wrong() {
                    let res = null;
                }
                """).scanTokens()).parseCompilationUnit(null);

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(
                List.of(unit));

        assertEquals(2, result.errors().size());
        assertTrue(result.errors().get(0).message().contains("Unknown import target"));
        assertTrue(result.errors().get(1).message().contains("Cannot infer a type from a null value"));
        assertEquals("ZR2014", result.errors().get(1).code().toString());
    }

    @Test
    public void collectsIndependentErrorsAndRestoresScopesBeforeContinuing() {
        final var statements = Parser.of(Scanner.from("""
                fn broken(): Int {
                    {
                        let hidden = 1;
                        return "wrong";
                    }
                }
                fn alsoBroken(): Int {
                    let bad: Int = "wrong";
                    return 2;
                }
                fn valid(): Int {
                    let local = 3;
                    return local;
                }
                """).scanTokens()).parse();
        final var hidden = ((Stmt.Var) ((Stmt.Block) ((Stmt.Function) statements.getFirst())
                .body().getFirst()).statements().getFirst()).name();
        final var validFunction = (Stmt.Function) statements.get(2);

        final var result = new ResolutionService().resolveWithDiagnostics(statements);

        assertEquals(2, result.errors().size());
        assertFalse(result.globalSymbolTable().containsSymbol(hidden));
        assertTrue(result.globalSymbolTable().containsFunction(validFunction.name()));
        assertEquals(TypeDescriptor.ofInt(),
                ((FunctionDescriptor) result.globalSymbolTable().getFunction(validFunction.name()).type())
                        .returnType());
    }

    @Test
    public void associatesDiagnosticsWithTheirCompilationUnitSourcePaths() {
        final var first = Parser.of(Scanner.from(
                "import zeron.lang.missingFirst as unavailableFirst;").scanTokens())
                .parseCompilationUnit("src/first.zn");
        final var second = Parser.of(Scanner.from(
                "import zeron.lang.missingSecond as unavailableSecond;").scanTokens())
                .parseCompilationUnit("src/second.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(first, second));

        assertEquals(2, result.errors().size());
        assertEquals("src/first.zn", result.errors().get(0).primarySpan().sourcePath());
        assertEquals("src/second.zn", result.errors().get(1).primarySpan().sourcePath());
        assertEquals("ZR2003", result.errors().get(0).code().toString());
    }
}
