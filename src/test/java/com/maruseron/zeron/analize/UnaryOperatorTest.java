package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class UnaryOperatorTest {
    @Test
    public void compilesIntegerAndFloatUnarySigns() throws Exception {
        final var className = "UnaryOperators" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn negateInt(value: Int): Int = -value;
                fn negateFloat(value: Float): Float = -value;
                fn positiveInt(value: Int): Int = +value;
                fn positiveFloat(value: Float): Float = +value;
                fn groupedNegation(): Int = -(2 + 3);
                fn negativeIntLiteral(): Int = -3;
                fn negativeFloatLiteral(): Float = -2.5;
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(-7, generated.getMethod("negateInt", int.class).invoke(null, 7));
                assertEquals(-2.5, generated.getMethod("negateFloat", double.class).invoke(null, 2.5));
                assertEquals(7, generated.getMethod("positiveInt", int.class).invoke(null, 7));
                assertEquals(2.5, generated.getMethod("positiveFloat", double.class).invoke(null, 2.5));
                assertEquals(-5, generated.getMethod("groupedNegation").invoke(null));
                assertEquals(-3, generated.getMethod("negativeIntLiteral").invoke(null));
                assertEquals(-2.5, generated.getMethod("negativeFloatLiteral").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsUnarySignsOnNonnumericValues() {
        for (final var source : List.of(
                "fn invalid(): Int = -true;",
                "fn invalid(): String = +\"text\";")) {
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
        }
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}