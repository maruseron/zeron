package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/// Validates that a value of type `T: nullable` is possibly null inside a generic body.
/// See `documents/developer_guides/nullable-type-parameters-fix.md`.
public final class NullableTypeParameterBodyTest {
    private static final String CONTRACTS = """
            contract Printable { text(): String; }
            contract Ranked<T: mut> { rank(other: T): Int; }
            class Box<T: nullable> {
                value: T;
                public constructor new;
            }
            """;

    @Test
    public void rejectsUncheckedUsesOfNullableTypeParameterValues() {
        final var memberAccess = CONTRACTS
                + "fn describe<T: nullable + Printable>(value: T): String = value.text();";
        final var useBeforeCheck = CONTRACTS + """
                fn checkedTooLate<T: nullable + Printable>(value: T): String {
                    let text = value.text();
                    if (value == null) return "null";
                    return text;
                }
                """;
        final var leakedArgument = CONTRACTS + """
                fn takesAny(value: Any): String = "ok";
                fn leak<T: nullable>(value: T): String = takesAny(value);
                """;
        final var boundOperation = CONTRACTS + """
                fn order<T: nullable + mut + Ranked<T>>(left: T, right: T): Int =
                    left.rank(right);
                """;
        final var invalidatedRefinement = CONTRACTS + """
                fn reassigned<T: nullable + Printable>(value: T, replacement: T): String {
                    let mut current = value;
                    if (current == null) return "null";
                    current = replacement;
                    return current.text();
                }
                """;

        for (final var source : List.of(memberAccess, useBeforeCheck, leakedArgument,
                boundOperation, invalidatedRefinement)) {
            final var failure = assertThrows(source, ResolutionError.class,
                    () -> new ResolutionService().resolve(parse(source)));
            assertEquals(source, "ZR2025", failure.toDiagnostic("nullable.zn").code().toString());
            assertTrue(source, failure.getMessage().contains("may be null"));
        }
    }

    @Test
    public void acceptsRefinedSafeAndMovingUses() {
        final var accepted = List.of(
                """
                fn describeBranch<T: nullable + Printable>(value: T): String =
                    if (value != null) then value.text() else "null";
                """,
                """
                fn describeEarly<T: nullable + Printable>(value: T): String {
                    if (value == null) return "null";
                    return value.text();
                }
                """,
                "fn identityOf<T: nullable>(value: T): T = value;",
                "fn wrap<T: nullable>(value: T): Box<T> = Box<T>.new(value);",
                "fn safeText<T: nullable + Printable>(value: T): String? = value?.text();",
                "fn asserted<T: nullable + Printable>(value: T): String = value!!.text();",
                """
                fn takesAny(value: Any): String = "ok";
                fn takesAnyOrNull(value: Any?): String = "ok";
                fn pass<T: nullable>(value: T): String = takesAnyOrNull(value);
                """,
                """
                fn takesAny(value: Any): String = "ok";
                fn passChecked<T: nullable>(value: T): String =
                    if (value != null) then takesAny(value) else "null";
                """,
                """
                fn orderChecked<T: nullable + mut + Ranked<T>>(left: T, right: T): Int {
                    if (left == null or right == null) return 0;
                    let ordering = left.rank(right);
                    return 1;
                }
                """);

        for (final var declaration : accepted) {
            final var source = CONTRACTS + declaration;
            try {
                final var result = new ResolutionService().resolve(parse(source));
                assertTrue(source, result.errors().isEmpty());
            } catch (final ResolutionError failure) {
                throw new AssertionError(declaration + " -> " + failure.getMessage(), failure);
            }
        }
    }

    @Test
    public void keepsCallSiteSubstitutionUnchanged() {
        new ResolutionService().resolve(parse("""
                fn firstOf<T: nullable>(values: Array<T>): T = values[0];
                fn identityOf<T: nullable>(value: T): T = value;
                fn only<A: mut>(value: A): A = value;
                let name: String = firstOf(["Ada"]);
                let accepted = identityOf<String?>(null);
                """));

        for (final var source : List.of(
                "fn firstOf<T: nullable>(values: Array<T>): T = values[0]; "
                        + "let nullableNames: Array<String?> = [\"Ada\", null]; "
                        + "let bad: String = firstOf(nullableNames);",
                "fn only<A: mut>(value: A): A = value; let rejected = only(null);",
                "fn firstOf<T: nullable>(values: Array<T>): T = values[0]; "
                        + "let nulls: Array<String?> = [null]; "
                        + "let bad: String = firstOf(nulls);")) {
            assertThrows(source, ResolutionError.class,
                    () -> new ResolutionService().resolve(parse(source)));
        }
    }

    @Test
    public void movesNullableTypeParameterValuesWithoutRunTimeChecks() throws Exception {
        final var className = "NullableTypeParameterValue"
                + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var source = parse("""
                fn identityOf<T: nullable>(value: T): T = value;
                fn describeNull(): String? = identityOf<String?>(null);
                fn describeText(): String = identityOf("text");
                fn isNull(): Boolean = identityOf<String?>(null) == null;
                """);

        try {
            final var compiler = new CompilationService(source, className);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertNull(generated.getMethod("describeNull").invoke(null));
                assertEquals("text", generated.getMethod("describeText").invoke(null));
                assertEquals(true, generated.getMethod("isNull").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static List<Stmt> parse(final String source) {
        final var parsed = Parser.of(Scanner.from(source, "NullableTypeParameters.zn")
                        .scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics("NullableTypeParameters.zn");
        if (!parsed.diagnostics().isEmpty()) throw new AssertionError(parsed.diagnostics().toString());
        return parsed.compilationUnit().declarations();
    }
}
