package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.TokenType;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public final class NullCoalescingAssignmentTest {
    @Test
    public void scansAndParsesNullCoalescingAssignment() {
        final var tokens = Scanner.from("value ??= fallback").scanTokens();
        assertEquals(List.of(TokenType.IDENTIFIER, TokenType.HUH_HUH_EQUAL, TokenType.IDENTIFIER,
                        TokenType.EOF),
                tokens.stream().map(token -> token.type()).toList());

        final var statements = parse("""
                fn parseAssignment(): Unit {
                    let mut value: Int? = null;
                    value ??= 5;
                }
                """);
        final var function = (Stmt.Function) statements.getFirst();
        final var assignment = (Expr.CoalesceAssignment)
                ((Stmt.Expression) function.body().get(1)).expression();
        assertEquals("value", assignment.name.lexeme());
    }

    @Test
    public void expressionYieldsNullableTargetValueAndFallbackIsLazy() throws Exception {
        final var className = "NullCoalescingAssignment"
                + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var source = """
                let mut fallbackCalls = 0;
                fn fallback(): Int? {
                    fallbackCalls += 1;
                    return 9;
                }
                fn initialize(initial: Int?): Int? {
                    let mut value: Int? = initial;
                    return value ??= fallback();
                }
                fn initializeWithNullFallback(initial: Int?): Int? {
                    let mut value: Int? = initial;
                    return value ??= null;
                }
                fn useAfterNonNullFallback(initial: Int?): Int {
                    let mut value: Int? = initial;
                    value ??= 4;
                    return value + 1;
                }
                fn calls(): Int = fallbackCalls;
                """;

        try {
            final var compiler = new CompilationService(parse(source), className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(3, generated.getMethod("initialize", Integer.class).invoke(null, 3));
                assertEquals(0, generated.getMethod("calls").invoke(null));
                assertEquals(9, generated.getMethod("initialize", Integer.class)
                        .invoke(null, new Object[]{null}));
                assertEquals(1, generated.getMethod("calls").invoke(null));
                assertNull(generated.getMethod("initializeWithNullFallback", Integer.class)
                        .invoke(null, new Object[]{null}));
                assertEquals(5, generated.getMethod("useAfterNonNullFallback", Integer.class)
                        .invoke(null, new Object[]{null}));
                assertEquals(4, generated.getMethod("useAfterNonNullFallback", Integer.class).invoke(null, 3));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void nullAssignmentAndNullableFallbackUpdateFlowFactsConservatively() {
        final var valid = parse("""
                fn refinedByFallback(initial: Int?): Int {
                    let mut value: Int? = initial;
                    value ??= 4;
                    return value + 1;
                }
                """);
        final var resolver = new ResolutionService();
        resolver.resolve(valid);
        final var function = (Stmt.Function) valid.getFirst();
        final var returned = (Stmt.Return) function.body().getLast();
        assertEquals(TypeDescriptor.ofInt(), returned.value().getType());

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                fn maybeNull(initial: Int?, fallback: Int?): Int {
                    let mut value: Int? = initial;
                    value ??= fallback;
                    return value + 1;
                }
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                fn remainsNull(): Int {
                    let mut value: Int? = null;
                    value ??= null;
                    return value + 1;
                }
                """)));
    }

    @Test
    public void rejectsUnsupportedTargetsAndBindingKinds() {
        for (final var source : List.of(
                "fn immutable(value: Int?): Int? { let cached: Int? = value; return cached ??= 1; }",
                "fn nonNullable(): Int { let mut value = 1; value ??= 2; return value; }",
                "let mut global: Int? = null; fn unsupported(): Int? = global ??= 1;")) {
            assertThrows(source, ResolutionError.class,
                    () -> new ResolutionService().resolve(parse(source)));
        }
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}
