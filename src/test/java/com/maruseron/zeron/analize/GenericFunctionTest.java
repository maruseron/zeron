package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class GenericFunctionTest {
    @Test
    public void infersGenericArgumentsFromValuesAndCallbacks() {
        final var statements = parse("""
                fn identity<T>(value: T): T = value;
                fn first<T>(values: Array<T>): T = values[0];
                fn apply<T, R>(value: T, transform: (T) -> R): R = transform(value);
                let explicit = identity<Int>(7);
                let inferred = identity("text");
                let firstValue = first([8, 9]);
                let transformed = apply(4, number -> number + 1);
                """);
        final var resolver = new Resolver();

        resolver.resolve(statements);

        assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "explicit"));
        assertEquals(TypeDescriptor.ofString(), bindingType(resolver, statements, "inferred"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "firstValue"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "transformed"));
    }

    @Test
    public void rejectsConflictingAndUnresolvedTypeArguments() {
        for (final var source : List.of(
                "fn same<T>(left: T, right: T): T = left; let value = same(1, \"text\");",
                "fn add<T>(left: T, right: T): T = left + right;",
                "fn negate<T>(value: T): T = -value;",
                "fn equal<T>(left: T, right: T): Boolean = left == right;",
            "fn identity<T>(value: T): T = value; let value = identity<Missing>(1);",
            "fn use<T>(operation: (T) -> Unit): Unit {} "
                    + "fn test(): Unit { use(value -> { print(value); }); }")) {
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
        }
    }

    @Test
    public void erasesGenericValuesAndAdaptsCallbacksAtBothBoundaries() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "GenericFunctions" + suffix;
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn identity<T>(value: T): T = value;
                fn first<T>(values: Array<T>): T = values[0];
                fn apply<T, R>(value: T, transform: (T) -> R): R = transform(value);
                fn applyWide<T, R>(value: T, transform: (Float, T) -> R): R = transform(2.5, value);
                fn identityFunction<T>(): (T) -> T = value -> value;
                fn identityInt(): Int = identity(42);
                fn identityText(): String = identity<String>("zeron");
                fn firstArray(): Int = first([17, 23]);
                fn mappedText(): String = apply("zero", text -> text + "n");
                fn inlineCallback(): Int = apply(5, value -> value + 3);
                fn wideCallback(): Int = applyWide(3, (scale, value) -> value);
                fn storedCallback(): Int {
                    let increment = value -> value + 1;
                    return apply(5, increment);
                }
                fn returnedCallback(): Int {
                    let increment: (Int) -> Int = identityFunction<Int>();
                    return increment(9);
                }
                """);
        final var compiler = new Compiler(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                assertEquals(42, program.getMethod("identityInt").invoke(null));
                assertEquals("zeron", program.getMethod("identityText").invoke(null));
                assertEquals(17, program.getMethod("firstArray").invoke(null));
                assertEquals("zeron", program.getMethod("mappedText").invoke(null));
                assertEquals(8, program.getMethod("inlineCallback").invoke(null));
                assertEquals(3, program.getMethod("wideCallback").invoke(null));
                assertEquals(6, program.getMethod("storedCallback").invoke(null));
                assertEquals(9, program.getMethod("returnedCallback").invoke(null));
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