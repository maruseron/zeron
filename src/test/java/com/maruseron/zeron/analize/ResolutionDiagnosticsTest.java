package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.diagnostic.DiagnosticHelp;
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
        assertEquals("ZR2023", result.errors().get(1).code().toString());
        assertEquals(List.of(new DiagnosticHelp("Add an explicit nullable type annotation.")),
                result.errors().get(1).helps());
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

    @Test
    public void locatesReturnTypeMismatchAtReturnedExpression() {
        final var source = "fn wrong(): Int = \"bad\";";
        final var unit = Parser.of(Scanner.from(source, "src/main.zn").scanTokens())
                .parseCompilationUnit("src/main.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(unit));

        assertEquals(1, result.errors().size());
        assertEquals("src/main.zn", result.errors().getFirst().primarySpan().sourcePath());
        assertEquals(1, result.errors().getFirst().primarySpan().start().line());
        assertEquals(source.indexOf("\"bad\"") + 1,
                (int) result.errors().getFirst().primarySpan().start().column());
    }

    @Test
    public void reportsUnresolvedStandaloneLambdaInsteadOfPassingItToCompilation() {
        final var source = "let f = x -> x + x;";
        final var unit = Parser.of(Scanner.from(source, "src/main.zn").scanTokens())
                .parseCompilationUnit("src/main.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(unit));

        assertEquals(1, result.errors().size());
        assertEquals("Cannot infer the lambda's function type.", result.errors().getFirst().message());
        assertEquals("src/main.zn", result.errors().getFirst().primarySpan().sourcePath());
        assertEquals("ZR2023", result.errors().getFirst().code().toString());
        assertEquals(List.of(new DiagnosticHelp(
                        "Add an explicit function type annotation to the binding.")),
                result.errors().getFirst().helps());
    }

    @Test
    public void reportsGenericInferenceSeparatelyFromGeneralTypeInference() {
        final var unit = Parser.of(Scanner.from("""
                fn deferred<T>(): T? = null;
                fn useDeferred(): Unit {
                    let value = deferred();
                }
                """).scanTokens()).parseCompilationUnit("generic-inference.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(unit));

        assertEquals(1, result.errors().size());
        assertEquals("ZR2022", result.errors().getFirst().code().toString());
        assertTrue(result.errors().getFirst().message().contains("Cannot infer type parameter"));
    }

    @Test
    public void formatsTypesInMissingWitnessDiagnostics() {
        final var unit = Parser.of(Scanner.from("""
                class Box<T> {}
                fn stringify(value: Box<Int>): String = "value=" + value;
                fn equal(left: Box<Int>, right: Box<Int>): Boolean = left == right;
                """).scanTokens()).parseCompilationUnit("witness-diagnostics.zn");
        final var compiler = CompilationService.forCompilationUnits(
                List.of(unit), "WitnessDiagnostics", "");

        final var messages = compiler.resolveWithDiagnostics().errors().stream()
                .map(error -> error.message())
                .toList();

        assertTrue(messages.contains("No Display witness is in scope for 'Box<Int>'."));
        assertTrue(messages.contains("No Eq witness is in scope for 'Box<Int>'."));
    }

    @Test
    public void distinguishesInvalidIterationSourceFromInvalidCollectionSink() {
        final var unit = Parser.of(Scanner.from("""
                fn badIterable(): Unit {
                    for (let item in 42) { }
                }
                fn badSink(): Unit {
                    let values = for [1] collect 0;
                }
                """).scanTokens()).parseCompilationUnit("iteration-protocols.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(unit));

        assertEquals(2, result.errors().size());
        assertEquals("ZR2020", result.errors().get(0).code().toString());
        assertEquals("ZR2021", result.errors().get(1).code().toString());
    }

    @Test
    public void explainsHowToFixAnUnknownFunctionCall() {
        final var unit = Parser.of(Scanner.from("fn run(): Unit = append(1);", "src/main.zn").scanTokens())
                .parseCompilationUnit("src/main.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(unit));

        assertEquals(1, result.errors().size());
        assertEquals("Unknown function 'append' in the current scope.",
                result.errors().getFirst().message());
        assertEquals(List.of(new DiagnosticHelp(
                        "Declare this function or import it from the package that provides it.")),
                result.errors().getFirst().helps());
    }
}
