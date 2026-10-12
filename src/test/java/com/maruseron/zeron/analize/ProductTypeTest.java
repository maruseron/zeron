package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ProductTypeTest {
    @Test
    public void constructsReadsAndComparesProducts() throws Exception {
        final var programName = "ProductProgram" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                import zeron.lang.Eq;
                public type Pair<A, B> = (left: A, right: B);
                fn same<A: Eq<A>, B: Eq<B>>(first: &Pair<A, B>, second: &Pair<A, B>): Boolean =
                    first == second;
                fn left(): Int = Pair<Int, String>.new(42, "answer").left;
                fn equal(): Boolean =
                    same(Pair<Int, String>.new(42, "answer"), Pair<Int, String>.new(42, "answer"));
                fn unequal(): Boolean =
                    same(Pair<Int, String>.new(42, "answer"), Pair<Int, String>.new(7, "answer"));
                """;
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                final var pair = loader.loadClass("Pair");
                assertEquals(42, program.getMethod("left").invoke(null));
                assertTrue((Boolean) program.getMethod("equal").invoke(null));
                assertFalse((Boolean) program.getMethod("unequal").invoke(null));
                assertTrue(Modifier.isFinal(pair.getDeclaredField("$zeron$property$left").getModifiers()));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", "Pair.class"));
        }
    }

    @Test
    public void rejectsOneComponentAndDuplicateNames() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type One = (value: Int);
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type Duplicate = (value: Int, value: String);
                """)));
    }

    @Test
    public void productTypesAreNominalAndComponentsAreReadOnly() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type Pair = (left: Int, right: Int);
                type Coordinates = (left: Int, right: Int);
                fn invalid(): Coordinates = Pair.new(1, 2);
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type Pair = (left: Int, right: Int);
                fn invalid(pair: Pair): Unit {
                    pair.left = 3;
                }
                """)));
    }

    @Test
    public void requiresComponentEqualityEvidenceAndRejectsAnOverride() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                class Box {
                    public constructor new;
                }
                type Pair = (left: &Box, right: &Box);
                fn invalid(): Boolean =
                    Pair.new(Box.new(), Box.new()) == Pair.new(Box.new(), Box.new());
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                import zeron.lang.Eq;
                type Pair = (left: Int, right: Int);
                witness Eq<Pair> for Pair {
                    fn eq(other: Pair): Boolean = true;
                }
                """)));
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source, "ProductTypeTest.zn").scanTokens())
                .parse();
    }
}
