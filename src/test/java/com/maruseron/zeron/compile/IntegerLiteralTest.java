package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class IntegerLiteralTest {
    @Test
    public void parsesLargeAndRadixSuffixedLiteralsAndSupportsNumericOperations() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-integer-");
        final var client = parse("Main.zn", """
                package bigintconsumer;
                import zeron.numeric.Integer;
                import suffix zeron.numeric.Integer.n;

                public class IntegerCases {
                    public constructor new;
                    public literal(): Integer = 123456789012345678901234567890n;
                    public radix(): Integer = 0xFF_FFn;
                    public sum(): Integer = 9223372036854775808n + 5n;
                    public product(): Integer = 12345678901234567890n * 2n;
                    public negative(): Integer = -42n;
                }
                """);

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(client)),
                    "bigintconsumer.Main", "bigintconsumer",
                    List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass("bigintconsumer.IntegerCases")
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
