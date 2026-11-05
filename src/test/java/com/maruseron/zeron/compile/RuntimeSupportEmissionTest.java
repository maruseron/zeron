package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RuntimeSupportEmissionTest {
    @Test
    public void omitsUnusedRuntimeSupportClasses() throws IOException {
        final var source = parse("src/main.zn", """
                class Box {}
                fn main() {}
                """);
        final var output = Files.createTempDirectory("zeron-runtime-support-unused-");
        try {
            final var compiler = compiler(List.of(source), "Main", output);
            compiler.resolve();
            compiler.compile();

            assertTrue(Files.exists(output.resolve(Path.of("zeron", "lang", "Unit.class"))));
            for (final var support : List.of(
                    "zeron/runtime/RaisedEffect.class",
                    "zeron/runtime/EffectValue.class",
                    "zeron/runtime/EffectSupport.class",
                    "zeron/runtime/ResourceSupport.class",
                    "zeron/runtime/EqSupport.class",
                    "zeron/runtime/HashSupport.class",
                    "zeron/runtime/StringUnicode.class",
                    "zeron/runtime/BigIntegerSupport.class",
                    "zeron/runtime/JavaArraySupport.class",
                    "zeron/runtime/HttpSupport.class",
                    "zeron/runtime/ArrayIterable.class",
                    "com/maruseron/zeron/runtime/NumericSupport.class",
                    "com/maruseron/zeron/runtime/RationalValue.class")) {
                assertFalse(support, Files.exists(output.resolve(support)));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void emitsReferencedSupportClassesAndTheirDependencies() throws IOException {
        final var source = parse("src/numeric.zn", """
                package zeron.numeric;
                import com.maruseron.zeron.runtime.RationalValue;
                import java.math.BigInteger;

                external fn bigIntegerFromLiteralText(value: String): &BigInteger;
                external fn rationalFromLiteralText(value: String): &RationalValue;

                public fn parseBig(value: String): &BigInteger =
                    bigIntegerFromLiteralText(value);
                public fn parseRational(value: String): &RationalValue =
                    rationalFromLiteralText(value);
                """);
        final var output = Files.createTempDirectory("zeron-runtime-support-used-");
        try {
            final var compiler = compiler(List.of(source), "zeron.numeric.RuntimeSupportProbe", output);
            compiler.resolve();
            compiler.compile();

            assertTrue(Files.exists(output.resolve(Path.of("zeron", "runtime",
                    "BigIntegerSupport.class"))));
            assertTrue(Files.exists(output.resolve(Path.of("com", "maruseron", "zeron", "runtime",
                    "RationalValue.class"))));
            assertTrue(Files.exists(output.resolve(Path.of("com", "maruseron", "zeron", "runtime",
                    "NumericSupport.class"))));
            assertFalse(Files.exists(output.resolve(Path.of("zeron", "runtime",
                    "HttpSupport.class"))));
            assertFalse(Files.exists(output.resolve(Path.of("zeron", "runtime",
                    "StringUnicode.class"))));
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void staleSupportClassesDoNotKeepOtherSupportClassesAlive() throws IOException {
        final var source = parse("src/main.zn", "fn main() {}");
        final var output = Files.createTempDirectory("zeron-runtime-support-stale-");
        final var staleSupport = output.resolve(Path.of("zeron", "runtime", "BigIntegerSupport.class"));
        try {
            Files.createDirectories(staleSupport.getParent());
            Files.writeString(staleSupport, "zeron/runtime/HttpSupport", StandardCharsets.ISO_8859_1);

            final var compiler = compiler(List.of(source), "Main", output);
            compiler.resolve();
            compiler.compile();

            assertFalse(Files.exists(output.resolve(Path.of("zeron", "runtime",
                    "HttpSupport.class"))));
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationService compiler(final List<CompilationUnit> units,
                                               final String mainClassName,
                                               final Path output) {
        return CompilationService.forCompilationUnits(units, mainClassName, "",
                List.of(), false, List.of(), output);
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }
}
