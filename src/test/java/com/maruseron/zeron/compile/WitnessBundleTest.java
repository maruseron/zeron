package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class WitnessBundleTest {
    private static final String CONTRACT = """
            package app;

            public contract Semigroup<A> {
                combine(other: A): A;
            }

            fn combineAll<A: Semigroup<A>>(left: A, right: A): A = left.combine(right);
            """;

    @Test
    public void namedBundleIsSelectionOnlyAndActivatesAtomically() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-bundle-");
        final var unit = parse(CONTRACT + """
                public witness bundle Multiplication for Int {
                    witness Semigroup<Int> {
                        fn combine(other: Int): Int = this * other;
                    }
                }

                fn selected(): Int {
                    using witness (Multiplication) {
                        return combineAll(3, 4);
                    }
                }

                fn selectedCall(): Int = combineAll(3, 4) with witness (Multiplication);
                """);
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), "app.Main", "app", List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass("app.Main");
                assertEquals(12, main.getMethod("selected").invoke(null));
                assertEquals(12, main.getMethod("selectedCall").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void defaultBundleMembersAreAmbientEvidence() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-default-bundle-");
        final var unit = parse(CONTRACT + """
                default witness bundle for Int {
                    witness Semigroup<Int> {
                        fn combine(other: Int): Int = this + other;
                    }
                }

                fn ambient(): Int = combineAll(3, 4);
                """);
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), "app.Main", "app", List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass("app.Main");
                assertEquals(7, main.getMethod("ambient").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void selectedBundleShadowsAmbientDefaultAsAClosure() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-bundle-closure-");
        final var unit = parse("""
                package app;

                public contract Semigroup<A> {
                    combine(other: A): A;
                }

                public contract Monoid<A> requires Semigroup<A> {
                    static empty(): A;
                }

                fn product<A: Monoid<A>>(left: A, right: A): A = left.combine(right);

                default witness bundle for Int {
                    witness Semigroup<Int> {
                        fn combine(other: Int): Int = this + other;
                    }
                    witness Monoid<Int> {
                        static fn empty(): Int = 0;
                    }
                }

                public witness bundle Multiplication for Int {
                    witness Semigroup<Int> {
                        fn combine(other: Int): Int = this * other;
                    }
                    witness Monoid<Int> {
                        static fn empty(): Int = 1;
                    }
                }

                fn additive(): Int = product(3, 4);

                fn multiplicative(): Int {
                    using witness (Multiplication) {
                        return product(3, 4);
                    }
                }
                """);
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), "app.Main", "app", List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass("app.Main");
                assertEquals(7, main.getMethod("additive").invoke(null));
                assertEquals(12, main.getMethod("multiplicative").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void selectedNamedWitnessShadowsAnAmbientDefault() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-selected-witness-");
        final var library = parse("lib.zn", """
                package lib;

                public contract Semigroup<A> {
                    combine(other: A): A;
                }

                public class Box {
                    public property value: Int;
                    public constructor new;
                }

                public fn combineAll<A: Semigroup<A>>(left: A, right: A): A = left.combine(right);
                public fn boxValue(box: Box): Int = box.value;

                default witness bundle for Box {
                    witness Semigroup<Box> {
                        fn combine(other: Box): Box = Box.new(this.value + other.value);
                    }
                }
                """);
        final var alternative = parse("alt.zn", """
                package alt;

                public witness Multiplicative: lib.Semigroup<lib.Box> for lib.Box {
                    fn combine(other: lib.Box): lib.Box =
                        lib.Box.new(this.value * other.value);
                }
                """);
        final var application = parse("Main.zn", """
                package app;

                import lib.Box;
                import lib.combineAll;
                import lib.boxValue;

                fn selected(): Int {
                    using witness (alt.Multiplicative) {
                        return boxValue(combineAll(Box.new(3), Box.new(4)));
                    }
                }
                """);
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(library, alternative, application), "app.Main", "app",
                    List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void namedBundleIsNotAmbientEvidence() {
        final var unit = parse(CONTRACT + """
                public witness bundle Multiplication for Int {
                    witness Semigroup<Int> {
                        fn combine(other: Int): Int = this * other;
                    }
                }

                fn outside(): Int = combineAll(3, 4);
                """);
        final var output = Path.of("target", "zeron-bundle-invalid");
        final var compiler = CompilationService.forCompilationUnits(
                List.of(unit), "app.Main", "app", List.of(), false, List.of(), output);
        assertThrows(ResolutionError.class, compiler::resolve);
    }

    private static CompilationUnit parse(final String source) {
        return parse("Main.zn", source);
    }

    private static CompilationUnit parse(final String path, final String source) {
        final var result = Parser.of(Scanner.from(source, path).scanTokens())
                .parseCompilationUnitWithDiagnostics(path);
        if (!result.diagnostics().isEmpty()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.compilationUnit();
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
