package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public final class LambdaSyntaxTest {
    @Test
    public void supportsUnaryFunctionTypesTypedLambdasAndTrailingLambdas() throws Exception {
        final var className = "LambdaSyntax" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                fn apply(f: Int -> Int): Int = f(3);
                fn withTrailingLambda(): Int = apply { value -> value + 2 };
                fn withImplicitTrailingLambda(): Int = apply { it + 4 };
                fn withTypedTrailingLambda(): Int = apply { value: Int -> value + 3 };
                fn withTypedLambda(): Int = apply((value: Int) -> value + 4);
                fn applyTwo(f: (Int, Int) -> Int): Int = f(2, 5);
                fn withTwoParameters(): Int = applyTwo { left, right -> left + right };
                fn curried(f: Int -> Int -> Int): Int = 0;
                fn invokeUnit(action: () -> Unit): Unit = action();
                fn withTrailingBlock(): Unit = invokeUnit { 1; };
                """;
        try {
            final var unit = Parser.of(Scanner.from(source, className + ".zn").scanTokens())
                    .parseCompilationUnit(className + ".zn");
            final var compiler = CompilationService.forCompilationUnits(List.of(unit), className, "");
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(5, generated.getMethod("withTrailingLambda").invoke(null));
                assertEquals(7, generated.getMethod("withImplicitTrailingLambda").invoke(null));
                assertEquals(6, generated.getMethod("withTypedTrailingLambda").invoke(null));
                assertEquals(7, generated.getMethod("withTypedLambda").invoke(null));
                assertEquals(7, generated.getMethod("withTwoParameters").invoke(null));
                assertEquals("Unit", generated.getMethod("withTrailingBlock").invoke(null).toString());
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", className + ".class"));
        }
    }
}
