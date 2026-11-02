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

public final class ConditionalExpectedTypeTest {
    @Test
    public void propagatesExpectedFunctionTypesIntoConditionalBranches() throws Exception {
        final var className = "ConditionalExpectedType" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn apply(operation: (Int) -> Int, value: Int): Int = operation(value);

                fn returnConditional(flag: Boolean): (Int) -> Int {
                    return if (flag) then (value -> 5) else (value -> 3);
                }

                fn argumentConditional(flag: Boolean): Int =
                    apply(if (flag) then (value -> 60) else (value -> 40), 50);
                """);

        try {
            final var compiler = new CompilationService(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                final var positive = program.getMethod("returnConditional", boolean.class).invoke(null, true);
                final var negative = program.getMethod("returnConditional", boolean.class).invoke(null, false);
                assertEquals(5, positive.getClass().getInterfaces()[0]
                        .getMethod("invoke", int.class).invoke(positive, 4));
                assertEquals(3, negative.getClass().getInterfaces()[0]
                        .getMethod("invoke", int.class).invoke(negative, 4));
                assertEquals(60, program.getMethod("argumentConditional", boolean.class).invoke(null, true));
                assertEquals(40, program.getMethod("argumentConditional", boolean.class).invoke(null, false));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}
