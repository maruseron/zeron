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
    public void matchesLiteralsAndDestructuresProducts() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var programName = "ProductMatch" + suffix;
        final var productName = "PatternPair" + suffix;
        final var source = """
                public type %s<A, B> = (left: A, right: B);
                fn zeroPair(): %s<Int, String> = %s<Int, String>.new(0, "zero");
                fn answerPair(): %s<Int, String> = %s<Int, String>.new(42, "answer");
                fn pairMatch(pair: %s<Int, String>): Int = match (pair) {
                    case %s(0, "zero") -> 0;
                    case %s(left, _) -> left;
                };
                fn integerMatch(value: Int): String = match (value) {
                    case 1 -> "one";
                    case _ -> "other";
                };
                fn stringMatch(value: String): Int = match (value) {
                    case "map" -> 1;
                    case _ -> 0;
                };
                fn booleanMatch(value: Boolean): Int = match (value) {
                    case true -> 1;
                    case false -> 0;
                };
                """.formatted(productName, productName, productName, productName, productName,
                productName, productName, productName);
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                final var product = loader.loadClass(productName);
                assertEquals(0, program.getMethod("pairMatch", product).invoke(null,
                        program.getMethod("zeroPair").invoke(null)));
                assertEquals(42, program.getMethod("pairMatch", product).invoke(null,
                        program.getMethod("answerPair").invoke(null)));
                assertEquals("one", program.getMethod("integerMatch", int.class).invoke(null, 1));
                assertEquals("other", program.getMethod("integerMatch", int.class).invoke(null, 3));
                assertEquals(1, program.getMethod("stringMatch", String.class).invoke(null, "map"));
                assertEquals(0, program.getMethod("stringMatch", String.class).invoke(null, "filter"));
                assertEquals(1, program.getMethod("booleanMatch", boolean.class).invoke(null, true));
                assertEquals(0, program.getMethod("booleanMatch", boolean.class).invoke(null, false));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", productName + ".class"));
        }
    }

    @Test
    public void requiresExhaustiveLiteralAndProductMatches() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                fn invalid(value: Int): Int = match (value) {
                    case 1 -> 1;
                };
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                fn invalid(value: Boolean): Int = match (value) {
                    case true -> 1;
                };
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type Pair = (left: Int, right: Int);
                fn invalid(value: Pair): Int = match (value) {
                    case Pair(0, _) -> 1;
                };
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                fn invalid(value: Boolean): Int = match (value) {
                    case true -> 1;
                    case true -> 2;
                    case false -> 0;
                };
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                fn invalid(value: Any): Int = match (value) {
                    case _ -> 0;
                };
                """)));
    }

    @Test
    public void matchesConcreteClassesOnlyThroughLiteralPatterns() throws Exception {
                final var suffix = UUID.randomUUID().toString().replace("-", "");
                final var programName = "ConcreteMatch" + suffix;
                final var className = "PatternBox" + suffix;
                final var source = """
                        class %s {
                            public property value: Int;
                            public constructor new;
                            public pattern unbox(result: Int) { result = this.value; }
                        }
                        fn classify(box: %s): Int = match (box) {
                            case %s.unbox(0) -> 0;
                            case %s.unbox(value) -> value;
                        };
                        fn zero(): %s = %s.new(0);
                        fn other(): %s = %s.new(42);
                        """.formatted(className, className, className, className,
                        className, className, className, className);
                final var compiler = new CompilationService(parse(source), programName);
                try {
                    compiler.resolve();
                    compiler.compile();
                    try (final var loader = new URLClassLoader(
                            new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                        final var program = loader.loadClass(programName);
                        final var box = loader.loadClass(className);
                        assertEquals(0, program.getMethod("classify", box).invoke(null,
                                program.getMethod("zero").invoke(null)));
                        assertEquals(42, program.getMethod("classify", box).invoke(null,
                                program.getMethod("other").invoke(null)));
                    }
                } finally {
                    Files.deleteIfExists(Path.of("dist", programName + ".class"));
                    Files.deleteIfExists(Path.of("dist", className + ".class"));
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
