package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.TokenType;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public final class SafeNavigationTest {
    @Test
    public void parsesSafePropertiesAndCalls() {
        final var tokens = Scanner.from("receiver?.value()").scanTokens();
        assertEquals(TokenType.HUH_DOT, tokens.get(1).type());

        final var statements = parse("""
                let optionalValue = receiver?.value;
                let call = receiver?.value();
                """);
        final var property = (Expr.Property) ((Stmt.Var) statements.getFirst()).initializer();
        final var call = (Expr.MemberCall) ((Stmt.Var) statements.get(1)).initializer();
        assertEquals(true, property.safeNavigation());
        assertEquals(true, call.safeNavigation());
    }

    @Test
    public void safeNavigationBranchesAndEvaluatesReceiverAndArgumentsOnce() throws Exception {
        final var className = "SafeNavigation" + UUID.randomUUID().toString().replace("-", "");
        final var boxName = "SafeBox" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                class %s {
                    value: Int;
                    public constructor new;
                    public read(): Int = this.value;
                    public combine(extra: Int): Int = this.value + extra;
                    public same(other: %s): Int = other.value;
                    public mut increment(extra: Int): Unit {
                        this.value = this.value + extra;
                    }
                    public readFrom(other: %s?): Int? = other?.value;
                }
                let mut receiverCalls = 0;
                let mut argumentCalls = 0;
                fn makeBox(value: Int?): %s? {
                    receiverCalls += 1;
                    if (value == null) return null;
                    return %s.new(value);
                }
                fn argument(): Int {
                    argumentCalls += 1;
                    return 5;
                }
                fn read(box: %s?): Int? = box?.read();
                fn readProperty(box: %s?): Int? = box?.readFrom(box);
                fn same(box: %s?): Int? = box?.same(box);
                fn combine(box: %s?): Int? = box?.combine(argument());
                fn increment(box: &%s?): Unit? = box?.increment(1);
                fn readOnce(value: Int?): Int? = makeBox(value)?.read();
                fn counts(): Int = receiverCalls * 10 + argumentCalls;
                """.formatted(boxName, boxName, boxName, boxName, boxName,
                        boxName, boxName, boxName, boxName, boxName);
        final var output = Path.of("dist", className + ".class");
        final var boxOutput = Path.of("dist", boxName + ".class");

        try {
            final var compiler = new Compiler(parse(source), className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                final var boxType = loader.loadClass(boxName);
                final var constructor = boxType.getDeclaredConstructor(int.class);
                constructor.setAccessible(true);
                final var box = constructor.newInstance(7);
                assertEquals(7, generated.getMethod("read", boxType).invoke(null, box));
                assertNull(generated.getMethod("read", boxType).invoke(null, new Object[]{null}));
                assertEquals(7, generated.getMethod("readProperty", boxType).invoke(null, box));
                assertNull(generated.getMethod("readProperty", boxType).invoke(null, new Object[]{null}));
                assertEquals(7, generated.getMethod("same", boxType).invoke(null, box));
                assertNull(generated.getMethod("combine", boxType).invoke(null, new Object[]{null}));
                assertNull(generated.getMethod("increment", boxType).invoke(null, new Object[]{null}));
                assertNotNull(generated.getMethod("increment", boxType).invoke(null, box));
                assertEquals(8, generated.getMethod("read", boxType).invoke(null, box));
                assertEquals(0, generated.getMethod("counts").invoke(null));
                assertEquals(13, generated.getMethod("combine", boxType).invoke(null, box));
                assertEquals(1, generated.getMethod("counts").invoke(null));
                assertNull(generated.getMethod("readOnce", Integer.class).invoke(null, new Object[]{null}));
                assertEquals(11, generated.getMethod("counts").invoke(null));
                assertEquals(4, generated.getMethod("readOnce", Integer.class).invoke(null, 4));
                assertEquals(21, generated.getMethod("counts").invoke(null));
            }
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(boxOutput);
        }
    }

    @Test
    public void mutableNullableReferenceRetainsMutatingCapability() {
        new Resolver().resolve(parse("""
                class SafeCounter {
                    value: Int;
                    public constructor new;
                    public mut increment(): Unit { this.value = this.value + 1; }
                }
                fn increment(counter: &SafeCounter?): Unit {
                    counter?.increment();
                }
                """));

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse("""
                class ReadOnlySafeCounter {
                    value: Int;
                    public constructor new;
                    public mut increment(): Unit { this.value = this.value + 1; }
                }
                fn increment(counter: ReadOnlySafeCounter?): Unit {
                    counter?.increment();
                }
                """)));
    }

    @Test
    public void safeNavigationFlowRefinementDoesNotEscapeTheCall() {
        new Resolver().resolve(parse("""
                class SafeFlowBox {
                    value: Int;
                    public constructor new;
                    public read(): Int = this.value;
                    public same(other: SafeFlowBox): Int = other.value;
                }
                fn valid(box: SafeFlowBox?): Int? = box?.same(box);
                """));

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse("""
                class SafeFlowBox2 {
                    value: Int;
                    public constructor new;
                    public read(): Int = this.value;
                }
                fn invalid(box: SafeFlowBox2?): Int {
                    box?.read();
                    return box.read();
                }
                """)));
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}
