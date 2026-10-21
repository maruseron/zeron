package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class BigIntegerLiteralTest {
    @Test
    public void parsesLargeAndRadixSuffixedLiteralsAndSupportsAlgebraicOperations() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-biginteger-");
        final var algebra = parse(Path.of("src", "main", "resources", "stdlib", "zeron", "algebra",
                "algebra.zn"));
        final var bigInteger = parse(Path.of("src", "main", "resources", "stdlib", "zeron", "math",
                "biginteger.zn"));
        final var client = parse("Main.zn", """
                package bigintconsumer;
                import zeron.math.BigInteger;
                import suffix zeron.math.BigInteger.n;

                public class BigIntegerCases {
                    public constructor new;
                    public literal(): BigInteger = 123456789012345678901234567890n;
                    public radix(): BigInteger = 0xFF_FFn;
                    public sum(): BigInteger = 9223372036854775808n + 5n;
                    public product(): BigInteger = 12345678901234567890n * 2n;
                    public negative(): BigInteger = -42n;
                }
                """);

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(algebra, bigInteger, client), "bigintconsumer.Main", "bigintconsumer",
                    List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass("bigintconsumer.BigIntegerCases")
                        .getConstructor().newInstance();
                assertEquals("123456789012345678901234567890",
                        program.getClass().getMethod("literal").invoke(program).toString());
                assertEquals("65535", program.getClass().getMethod("radix").invoke(program).toString());
                assertEquals("9223372036854775813", program.getClass().getMethod("sum").invoke(program).toString());
                assertEquals("24691357802469135780", program.getClass().getMethod("product").invoke(program).toString());
                assertEquals("-42", program.getClass().getMethod("negative").invoke(program).toString());
            }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final Path path) throws Exception {
        return parse(path.getFileName().toString(), Files.readString(path));
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens()).parseCompilationUnit(sourceName);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
