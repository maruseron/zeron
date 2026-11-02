package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public final class StatementIfCodegenTest {
    @Test
    public void nestedReturningIfStatementsGenerateValidBytecode() throws Exception {
        final var className = "NestedIf" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var source = """
                fn nestedReturns(first: Boolean, second: Boolean): Int {
                    if (first) {
                        if (second) {
                            return 1;
                        } else {
                            return 2;
                        }
                    } else {
                        return 3;
                    }
                }

                fn branchCanComplete(first: Boolean, second: Boolean): Int {
                    if (first) {
                        if (second) return 4;
                    } else {
                        return 5;
                    }
                    return 6;
                }
                """;

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    java.util.List.of(parse(source)), className, "");
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(1, generated.getMethod("nestedReturns", boolean.class, boolean.class)
                        .invoke(null, true, true));
                assertEquals(2, generated.getMethod("nestedReturns", boolean.class, boolean.class)
                        .invoke(null, true, false));
                assertEquals(3, generated.getMethod("nestedReturns", boolean.class, boolean.class)
                        .invoke(null, false, true));
                assertEquals(4, generated.getMethod("branchCanComplete", boolean.class, boolean.class)
                        .invoke(null, true, true));
                assertEquals(6, generated.getMethod("branchCanComplete", boolean.class, boolean.class)
                        .invoke(null, true, false));
                assertEquals(5, generated.getMethod("branchCanComplete", boolean.class, boolean.class)
                        .invoke(null, false, true));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static CompilationUnit parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(null);
    }
}
