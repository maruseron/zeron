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

public final class NullCoalescingTest {
    @Test
    public void scansCoalescingAsOneOperatorDistinctFromSafeNavigation() {
        final var tokens = Scanner.from("value ?? fallback ?.member ? other").scanTokens();

        assertEquals(List.of(TokenType.IDENTIFIER, TokenType.HUH_HUH, TokenType.IDENTIFIER,
                        TokenType.HUH_DOT, TokenType.IDENTIFIER, TokenType.HUH, TokenType.IDENTIFIER,
                        TokenType.EOF),
                tokens.stream().map(token -> token.type()).toList());
    }

    @Test
    public void parsesCoalescingAsRightAssociativeBelowOr() {
        final var statements = parse("""
                let chain = first ?? second ?? third;
                let precedence = first ?? second or true;
                """);
        final var chain = (Expr.Coalesce) ((Stmt.Var) statements.getFirst()).initializer();
        final var precedence = (Expr.Coalesce) ((Stmt.Var) statements.get(1)).initializer();

        assertEquals(Expr.Coalesce.class, chain.right.getClass());
        assertEquals(Expr.Logical.class, precedence.right.getClass());
    }

    @Test
    public void resolvesNullableAndNullFallbackTypeJoins() {
        final var statements = parse("""
                let maybeInt: Int? = null;
                let maybeAny: Any? = null;
                let maybeString: String? = null;
                let nonNull: Int = maybeInt ?? 1;
                let nullable: Int? = maybeInt ?? null;
                let nullLeft = null ?? 2;
                let common = maybeInt ?? "fallback";
                let nullableCommon = maybeInt ?? maybeString;
                let anyValue: Any = maybeAny ?? 3;
                """);
        final var resolver = new ResolutionService();
        final var result = resolver.resolve(statements);

        assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "nonNull"));
        assertEquals(TypeDescriptor.ofInt().toNullable(), bindingType(result, statements, "nullable"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "nullLeft"));
        assertEquals(TypeDescriptor.ofAny(), bindingType(result, statements, "common"));
        assertEquals(TypeDescriptor.ofAny().toNullable(), bindingType(result, statements, "nullableCommon"));
        assertEquals(TypeDescriptor.ofAny(), bindingType(result, statements, "anyValue"));
    }

    @Test
    public void resolvesFallbackUnderItsNullFlowAndJoinsFactsAfterward() {
        new ResolutionService().resolve(parse("""
                fn fallbackUsesRefinement(value: Int?, other: Int?): Int {
                    if (other == null) return 0;
                    return value ?? other;
                }
                """));

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                fn doesNotRefineAfterCoalescing(value: Int?): Int {
                    let result = value ?? 0;
                    return value + 1;
                }
                """)));
    }

    @Test
    public void rejectsNonNullableLeftAndUninferableNullOnlyCoalescing() {
        for (final var source : List.of(
                "fn invalid(value: Int): Int = value ?? 0;",
                "fn invalid(value: Int?): Int = if (value != null) then value ?? 0 else 0;",
                "fn invalid() = null ?? null;",
                "fn invalid(value: Int?): Unit { let mut count = 0; let callback = () -> value ?? count; }")) {
            assertThrows(source, ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
        }
    }

    @Test
    public void compilesShortCircuitCoalescingAndEvaluatesEachSideCorrectly() throws Exception {
        final var className = "NullCoalescing" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var source = parse("""
                let mut leftCalls = 0;
                let mut fallbackCalls = 0;
                fn left(value: Int?): Int? {
                    leftCalls += 1;
                    return value;
                }
                fn fallback(): Int {
                    fallbackCalls += 1;
                    return 9;
                }
                fn coalesce(value: Int?): Int = left(value) ?? fallback();
                fn counts(): Int = leftCalls * 10 + fallbackCalls;
                fn coalesceNullOnly(): String = null ?? "fallback";
                fn nullableResult(value: Int?): Int? = value ?? null;
                fn coalesceNullablePrimitive(value: Int?): Int = value ?? 7;
                fn coalesceAny(value: Any?): Any = value ?? "fallback";
                fn coalesceCallback(value: ((Int) -> Int)?): (Int) -> Int {
                    let fallback: (Int) -> Int = input -> input + 1;
                    return value ?? fallback;
                }
                """);

        try {
            final var compiler = new CompilationService(source, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(4, generated.getMethod("coalesce", Integer.class).invoke(null, 4));
                assertEquals(10, generated.getMethod("counts").invoke(null));
                assertEquals(9, generated.getMethod("coalesce", Integer.class).invoke(null, new Object[]{null}));
                assertEquals(21, generated.getMethod("counts").invoke(null));
                assertEquals("fallback", generated.getMethod("coalesceNullOnly").invoke(null));
                assertEquals(5, generated.getMethod("nullableResult", Integer.class).invoke(null, 5));
                assertNull(generated.getMethod("nullableResult", Integer.class).invoke(null, new Object[]{null}));
                assertEquals(7, generated.getMethod("coalesceNullablePrimitive", Integer.class)
                        .invoke(null, new Object[]{null}));
                assertEquals(6, generated.getMethod("coalesceNullablePrimitive", Integer.class).invoke(null, 6));
                assertEquals("value", generated.getMethod("coalesceAny", Object.class).invoke(null, "value"));
                assertEquals("fallback", generated.getMethod("coalesceAny", Object.class)
                        .invoke(null, new Object[]{null}));
                final var callbackType = TypeDescriptor.functionOf("",
                        TypeDescriptor.ofInt(), TypeDescriptor.ofInt());
                final var callbackInterface = loader.loadClass(
                        com.maruseron.zeron.domain.FunctionShapeNames.interfaceName(callbackType));
                final var chooseCallback = generated.getMethod("coalesceCallback", callbackInterface);
                final var callback = java.lang.reflect.Proxy.newProxyInstance(loader,
                        new Class<?>[]{callbackInterface}, (_, _, arguments) -> (int) arguments[0] + 5);
                final var provided = chooseCallback.invoke(null, callback);
                assertEquals(9, callbackInterface.getMethod("invoke", int.class).invoke(provided, 4));
                final var defaulted = chooseCallback.invoke(null, new Object[]{null});
                assertEquals(5, callbackInterface.getMethod("invoke", int.class).invoke(defaulted, 4));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }

    private static TypeDescriptor bindingType(final ResolutionResult result,
                                              final List<Stmt> statements,
                                              final String name) {
        final var declaration = statements.stream()
                .filter(Stmt.Var.class::isInstance)
                .map(Stmt.Var.class::cast)
                .filter(variable -> variable.name().lexeme().equals(name))
                .findFirst()
                .orElseThrow();
        return result.globalSymbolTable().getSymbol(declaration.name()).type();
    }
}
