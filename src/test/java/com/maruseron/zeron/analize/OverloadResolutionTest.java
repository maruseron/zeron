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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class OverloadResolutionTest {
    @Test
    public void selectsMostSpecificAndNonGenericTopLevelOverloads() throws Exception {
        final var programName = "Overloads" + UUID.randomUUID().toString().replace("-", "");
        final var programFile = Path.of("dist", programName + ".class");
        final var statements = parse("""
                fn choose<T>(value: T): String = "generic";
                fn choose(value: Int): String = "int";
                fn fromInt(): String = choose(3);
                fn fromString(): String = choose("text");
                fn viaReference(): String {
                    let chooser: (Int) -> String = choose;
                    return chooser(4);
                }
                fn select(value: Int, label: String = "default"): String = "default";
                fn select(value: Int, labels: String...): String = "variadic";
                fn fixed(value: Int, label: String): String = "fixed";
                fn fixed(value: Int, labels: String...): String = "variadic";
                fn defaultsChooseVariadic(): String = select(1);
                fn fixedArityWins(): String = fixed(1, "text");
                """);
        final var compiler = new CompilationService(statements, programName);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(programName);
                assertEquals("int", generated.getMethod("fromInt").invoke(null));
                assertEquals("generic", generated.getMethod("fromString").invoke(null));
                assertEquals("int", generated.getMethod("viaReference").invoke(null));
                assertEquals("variadic", generated.getMethod("defaultsChooseVariadic").invoke(null));
                assertEquals("fixed", generated.getMethod("fixedArityWins").invoke(null));
            }
        } finally {
            Files.deleteIfExists(programFile);
        }
    }

    @Test
    public void selectsMethodOverloadsAndReportsAmbiguousCalls() throws Exception {
        final var programName = "MethodOverloads" + UUID.randomUUID().toString().replace("-", "");
        final var pickerName = "Picker" + UUID.randomUUID().toString().replace("-", "");
        final var programFile = Path.of("dist", programName + ".class");
        final var statements = parse("""
                class %s {
                    public constructor new;
                    public choose(value: Any): String = "any";
                    public choose(value: Int): String = "int";
                }
                fn selected(): String = %s.new().choose(8);
                """.formatted(pickerName, pickerName));
        final var compiler = new CompilationService(statements, programName);
        compiler.resolve();
        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals("int", loader.loadClass(programName).getMethod("selected").invoke(null));
            }
        } finally {
            Files.deleteIfExists(programFile);
            Files.deleteIfExists(Path.of("dist", pickerName + ".class"));
        }
        final var ambiguous = assertThrows(ResolutionError.class, () ->
                new ResolutionService().resolve(parse("""
                        fn choose(value: Any, other: String): String = "left";
                        fn choose(value: String, other: Any): String = "right";
                        fn call(): String = choose("x", "y");
                        """)));
        assertTrue(ambiguous.getMessage(), ambiguous.getMessage().contains("Ambiguous call"));
        final var erasedCollision = new ResolutionService().resolveWithDiagnostics(parse("""
                fn same(value: Any): String = "any";
                fn same<T>(value: T): String = "generic";
                """));
        assertTrue(erasedCollision.errors().toString(),
                erasedCollision.errors().stream().anyMatch(error -> error.message().contains("JVM-colliding")));
    }

    private List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}
