package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ArrayViewTest {
    @Test
    public void projectsMutableArrayViewToReadOnly() {
        final var statements = parse("""
                fn first(values: Array<Int>): Int = values[0];
                let values: &Array<Int> = [1, 2, 3];
                let readonly: Array<Int> = values;
                let firstValue = first(values);
                let size = readonly.length;
                """);
        final var resolver = new Resolver();

        resolver.resolve(statements);

        assertEquals(new ReferenceDescriptor(TypeDescriptor.arrayOf(TypeDescriptor.ofInt())),
                bindingType(resolver, statements, "values"));
        assertEquals(TypeDescriptor.arrayOf(TypeDescriptor.ofInt()),
                bindingType(resolver, statements, "readonly"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "firstValue"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "size"));
    }

    @Test
    public void rejectsWritingThroughReadOnlyArrayAndUpgradingItsView() {
        for (final var source : List.of(
            "fn test() { let values: Array<Int> = [1]; values[0] = 2; }",
            "fn test() { let values: Array<Int> = [1]; let writable: &Array<Int> = values; }",
                "fn test() { let mut values: Array<Int> = [1]; values[0] = 2; }",
                "fn test() { let values: &Array<Int> = [1]; values[0] = \"wrong\"; }",
                "fn test() { let values: &Array<Int> = [1]; let wrong: Array<String> = values; }")) {
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
        }
    }

    @Test
    public void writesThroughMutableViewAndCompilerExecutesArrayOperations() throws Exception {
        final var className = "ArrayViewGenerated" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn arrayRead(index: Int): Int {
                    let values: &Array<Int> = [1, 2];
                    let readonly: Array<Int> = values;
                    values[1] = 8;
                    return readonly[index] + readonly.length;
                }
                fn nullableRead(): Int? {
                    let values: &Array<Int?> = [1, null];
                    return values[1];
                }
                fn floatRead(): Float {
                    let values: &Array<Float> = [1.5, 2.5];
                    return values[1];
                }
                fn booleanRead(): Boolean {
                    let values: &Array<Boolean> = [true, false];
                    return values[1];
                }
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()},
                    getClass().getClassLoader())) {
                final var method = loader.loadClass(className).getMethod("arrayRead", int.class);
                assertEquals(10, method.invoke(null, 1));
                final var failure = assertThrows(InvocationTargetException.class,
                        () -> method.invoke(null, 2));
                assertTrue(failure.getCause() instanceof IndexOutOfBoundsException);
                assertNull(loader.loadClass(className).getMethod("nullableRead").invoke(null));
                assertEquals(2.5, loader.loadClass(className).getMethod("floatRead").invoke(null));
                assertEquals(false, loader.loadClass(className).getMethod("booleanRead").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }

    private static TypeDescriptor bindingType(final Resolver resolver,
                                              final List<Stmt> statements,
                                              final String name) {
        final var declaration = statements.stream()
                .filter(Stmt.Var.class::isInstance)
                .map(Stmt.Var.class::cast)
                .filter(variable -> variable.name().lexeme().equals(name))
                .findFirst()
                .orElseThrow();
        return resolver.symbols.getSymbol(declaration.name()).type();
    }
}