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

public final class ControlFlowTest {
    @Test
    public void compilesWhileUntilLoopAndNestedBreak() throws Exception {
        final var className = "ControlFlowGenerated" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn whileOnce(): Int {
                    let mut count = 0;
                    while (count < 1) {
                        count += 1;
                    }
                    return count;
                }
                fn untilOnce(): Int {
                    let mut count = 0;
                    until (count == 3) {
                        count += 1;
                    }
                    return count;
                }
                fn nestedBreak(): Int {
                    let mut count = 0;
                    loop {
                        count += 1;
                        loop {
                            count += 10;
                            break;
                        }
                        break;
                    }
                    return count;
                }
                fn zeroPasses(): Int {
                    let mut count = 0;
                    while (false) {
                        count += 1;
                    }
                    return count;
                }
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(1, generated.getMethod("whileOnce").invoke(null));
                assertEquals(3, generated.getMethod("untilOnce").invoke(null));
                assertEquals(11, generated.getMethod("nestedBreak").invoke(null));
                assertEquals(0, generated.getMethod("zeroPasses").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsBreakAcrossLambdaBoundary() {
        final var statements = parse("""
                fn invalid() {
                    loop {
                        let action = () -> { break; };
                        break;
                    }
                }
                """);

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(statements));
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}