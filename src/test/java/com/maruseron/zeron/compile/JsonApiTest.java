package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class JsonApiTest {
    @Test
    public void exposesJsonParsingWritingAndErrorsThroughTheStandardLibrary() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-json-api-");
        final var source = """
                package json.client;
                import zeron.serialization.json.number;
                import zeron.serialization.json.parse;
                import zeron.serialization.json.stringify;

                fn kind(): String =
                    parse("[1,true,null]").fold(
                        (value) -> value.kind(),
                        (error) -> error.message
                    );

                fn roundTrip(): String =
                    parse("[-0.00e+12]").fold(
                        (value) -> stringify(value).fold(
                            (text) -> text,
                            (error) -> error.message
                        ),
                        (error) -> error.message
                    );

                fn rejectsInvalidNumber(): Boolean = number("01").isErr();

                fn syntaxOffset(): Int =
                    parse("[1,]").fold(
                        (_) -> -1,
                        (error) -> error.offset
                    );
                """;
        final var parsed = Parser.of(Scanner.from(source, "JsonApiTest.zn").scanTokens())
                .parseCompilationUnit("JsonApiTest.zn");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    java.util.List.of(parsed), "json.client.Main", "json.client",
                    java.util.List.of(), true, java.util.List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass("json.client.Main");
                assertEquals("array", program.getMethod("kind").invoke(null));
                assertEquals("[-0.00e+12]",
                        program.getMethod("roundTrip").invoke(null));
                assertTrue((Boolean) program.getMethod("rejectsInvalidNumber").invoke(null));
                assertEquals(3, program.getMethod("syntaxOffset").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static void deleteTree(final Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
