package com.maruseron.zeron;

import com.maruseron.zeron.analize.ResolutionService;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WarningDiagnosticsTest {
    @Before
    public void resetWarnings() {
        Zeron.beginReplSubmission();
    }

    @Test
    public void reportsUnusedCodeUnreachableStatementsAndWhileTrue() {
        final var library = parse("warningslib.zn", """
                package warningslib;
                public fn importedTarget(): Int = 1;
                """);
        final var consumer = parse("warnings.zn", """
                package warnings;
                import warningslib.importedTarget;

                fn unusedHelper(value: Int): Int {
                    let ignored = value;
                    return value;
                }

                fn flow(): Int {
                    return 1;
                    let dead = 2;
                }

                fn spin(): Int {
                    while (true) {
                        return 1;
                    }
                }

                fn main(): Int = 42;
                """);
        final var warnings = resolve(List.of(library, consumer));
        assertTrue(warnings.toString(), warnings.contains("ZR3012"));
        assertTrue(warnings.toString(), warnings.contains("ZR3011"));
        assertTrue(warnings.toString(), warnings.contains("ZR3013"));
        assertTrue(warnings.toString(), warnings.contains("ZR3014"));
        assertTrue(warnings.toString(), warnings.contains("ZR3016"));
        assertTrue(warnings.stream().noneMatch(code -> code.equals("ZR3010")));
    }

    @Test
    public void reportsShadowingAndUnnecessaryMutableProjection() {
        final var shadowing = resolve("""
                package warnings;
                fn shadow(value: Int): Int {
                    if (value > 0) {
                        let value = 42;
                        return value;
                    }
                    return value;
                }
                fn main(): Unit = ();
                """);
        assertTrue(shadowing.toString(), shadowing.contains("ZR3017"));

        final var projection = resolve("""
                package warnings;
                class Counter {
                    public property value: Int;
                    public constructor new;
                }
                fn peek(counter: &Counter): Int = counter.value;
                fn main(): Unit = ();
                """);
        assertTrue(projection.toString(), projection.contains("ZR3015"));
    }

    @Test
    public void reportsNullableReturnsAndTracksForwardedMutableArguments() {
        final var nullable = resolve("""
                package warnings;
                public fn maybeName(): String? {
                    if (true) return "name";
                    return null;
                }
                fn main(): Unit = ();
                """);
        assertTrue(nullable.toString(), nullable.contains("ZR3018"));

        final var projection = resolve("""
                package warnings;
                class Handle {
                    public constructor new;
                    public mut touch(): Unit = ();
                }
                fn mutate(subject: &Handle): Unit = subject.touch();
                fn forward(subject: &Handle): Unit = mutate(subject);
                fn main(): Unit = ();
                """);
        assertFalse(projection.toString(), projection.contains("ZR3015"));
    }

    @Test
    public void reportsNullableReturnsOnlyForPublicApi() {
        final var warnings = resolve("""
                package warnings;
                class PackagePrivate {
                    public constructor new;
                    public lookup(): String? = null;
                }
                public class Visible {
                    public constructor new;
                    public exposed(): String? = null;
                    private hidden(): String? = null;
                }
                fn main(): Unit = ();
                """);
        assertEquals(1L, warnings.stream().filter("ZR3018"::equals).count());
    }

    @Test
    public void functionReferencesInsideClassBodiesCountAsUses() {
        final var warnings = resolve("""
                package warnings;
                fn helper(value: Int): Int = value + 1;
                class Box {
                    public property value: Int;
                    public constructor new;
                    public constructor withTransform(transform: (Int) -> Int) =
                        Box.new(transform(1));
                    public constructor build() = Box.withTransform(helper);
                }
                fn main(): Unit = ();
                """);
        assertFalse(warnings.toString(), warnings.contains("ZR3013"));
    }

    @Test
    public void doesNotReportShadowingForImplicitReceivers() {
        final var warnings = resolve("""
                package warnings;

                contract Reader {
                    read(): Int;
                }
                class Box {
                    public property value: Int;
                    public constructor new;
                    public peek(): Int {
                        let reader = object: Reader {
                            public read(): Int = 1;
                        };
                        return reader.read();
                    }
                }
                fn main(): Unit = ();
                """);
        assertFalse(warnings.toString(), warnings.contains("ZR3017"));
    }

    @Test
    public void anonymousWitnessWarnsWithoutFailingParsing() {
        Zeron.beginReplSubmission();
        final var parsed = Parser.of(Scanner.from("""
                class Box {
                    public constructor new;
                }
                witness Display for Box {
                    fn toString(): String = "box";
                }
                """, "witness-warning.zn").scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("witness-warning.zn");
        assertTrue(parsed.diagnostics().toString(), parsed.diagnostics().isEmpty());
        assertFalse(Zeron.warnings().isEmpty());
        assertEquals("ZR3010", Zeron.warnings().getFirst().code().toString());
        assertTrue(Zeron.warnings().getFirst().helps().stream()
                .anyMatch(help -> help.message().contains("Name the witness")));
        assertEquals(DiagnosticCatalog.ANONYMOUS_WITNESS_DEPRECATED,
                DiagnosticCatalog.find(new com.maruseron.zeron.diagnostic.DiagnosticCode("ZR3010"))
                        .orElseThrow());
    }

    private static List<String> resolve(final String source) {
        return resolve(List.of(parse("warnings.zn", source)));
    }

    private static List<String> resolve(final List<CompilationUnit> units) {
        final var result = new ResolutionService("warnings").resolveUnitsWithDiagnostics(units);
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        return result.warnings().stream().map(diagnostic -> diagnostic.code().toString()).toList();
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnit(sourcePath);
    }
}
