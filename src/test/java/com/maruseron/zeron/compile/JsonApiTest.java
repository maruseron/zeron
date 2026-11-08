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
                import zeron.serialization.json.getErrorMessage;
                import zeron.serialization.json.getErrorOffset;

                fn kind(): String =
                    parse("[1,true,null]").fold(
                        (value) -> value.kind(),
                        (error) -> getErrorMessage(error)
                    );

                fn roundTrip(): String =
                    parse("[-0.00e+12]").fold(
                        (value) -> stringify(value).fold(
                            (text) -> text,
                            (error) -> getErrorMessage(error)
                        ),
                        (error) -> getErrorMessage(error)
                    );

                fn rejectsInvalidNumber(): Boolean = number("01").isErr();

                fn syntaxOffset(): Int =
                    parse("[1,]").fold(
                        (_) -> -1,
                        (error) -> getErrorOffset(error).getOrElse(-1)
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

    @Test
    public void roundTripsDerivedValuesThroughTheTypedApi() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-json-typed-");
        final var source = """
                package json.typed;
                import zeron.lang.Option;
                import zeron.serialization.Decode;
                import zeron.serialization.DecodeError;
                import zeron.serialization.Encode;
                import zeron.serialization.json.parseAs;
                import zeron.serialization.json.stringifyAs;

                public type Point deriving Encode, Decode = (x: Int, y: Int);
                public type Tag deriving Encode, Decode = Empty | Number(value: Int);
                public data class Inner(value: Int) deriving Encode, Decode {}
                public type Outer deriving Encode, Decode = (inner: Inner, count: Int);

                fn pointText(): String =
                    stringifyAs(Point.new(1, 2)).fold((text) -> text, (error) -> error.message);

                fn pointSum(text: String): Int =
                    parseAs<Point>(text).fold((point) -> point.x + point.y, (error) -> -1);

                fn tagText(): String =
                    stringifyAs(Tag.Number(7)).fold((text) -> text, (error) -> error.message);

                fn tagValue(tag: Tag): Int = match (tag) {
                    case Tag.Number(value) -> value;
                    case Tag.Empty() -> 0;
                };

                fn tagSum(text: String): Int =
                    parseAs<Tag>(text).fold((tag) -> tagValue(tag), (error) -> -1);

                fn emptyTagText(): String =
                    stringifyAs(Tag.Empty()).fold((text) -> text, (error) -> error.message);

                fn nestedText(): String =
                    stringifyAs(Outer.new(Inner.new(5), 2))
                        .fold((text) -> text, (error) -> error.message);

                fn nestedProduct(text: String): Int =
                    parseAs<Outer>(text).fold(
                        (outer) -> outer.inner.value * outer.count, (error) -> -1);

                fn pointFailure(text: String): Boolean = parseAs<Point>(text).isErr();
                fn integerFailure(text: String): Boolean = parseAs<Int>(text).isErr();

                fn position(error: DecodeError): Int = match (error.position) {
                    case Option.Some(value) -> value;
                    case Option.None() -> -1;
                };

                fn syntaxPosition(text: String): Int =
                    parseAs<Point>(text).fold((_) -> -1, (error) -> position(error));

                fn failureMessage(text: String): String =
                    parseAs<Int>(text).fold((_) -> "ok", (error) -> error.message);
                """;
        final var parsed = Parser.of(Scanner.from(source, "JsonTypedApiTest.zn").scanTokens())
                .parseCompilationUnit("JsonTypedApiTest.zn");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    java.util.List.of(parsed), "json.typed.Main", "json.typed",
                    java.util.List.of(), true, java.util.List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass("json.typed.Main");
                assertEquals("{\"x\":1,\"y\":2}", program.getMethod("pointText").invoke(null));
                assertEquals(7, program.getMethod("pointSum", String.class)
                        .invoke(null, "{\"x\":3,\"y\":4}"));
                assertEquals("{\"Number\":7}", program.getMethod("tagText").invoke(null));
                assertEquals(9, program.getMethod("tagSum", String.class)
                        .invoke(null, "{\"Number\":9}"));
                assertEquals("{\"Empty\":null}", program.getMethod("emptyTagText").invoke(null));
                assertEquals("{\"inner\":{\"value\":5},\"count\":2}",
                        program.getMethod("nestedText").invoke(null));
                assertEquals(10, program.getMethod("nestedProduct", String.class)
                        .invoke(null, "{\"inner\":{\"value\":5},\"count\":2}"));
                assertTrue((Boolean) program.getMethod("pointFailure", String.class)
                        .invoke(null, "{\"x\":1}"));
                assertTrue((Boolean) program.getMethod("pointFailure", String.class)
                        .invoke(null, "[1,2]"));
                assertTrue((Boolean) program.getMethod("integerFailure", String.class)
                        .invoke(null, "1e999"));
                assertEquals(3, program.getMethod("syntaxPosition", String.class)
                        .invoke(null, "[1,]"));
                assertEquals("Expected a value after ','",
                        program.getMethod("failureMessage", String.class).invoke(null, "[1,]"));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void exposesConfiguredJsonFacadeAndModelEncoding() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-json-config-");
        final var source = """
                package json.config;
                import zeron.serialization.Decode;
                import zeron.serialization.Encode;
                import zeron.serialization.json.Json;
                import zeron.serialization.json.getErrorMessage;
                import zeron.serialization.json.parse;

                public type Point deriving Encode, Decode = (x: Int, y: Int);

                public data class User(id: Int, name: String) {}

                public model UserWire is Model<User> {
                    id -> "user_id";
                    name -> "full_name";
                }

                fn compactText(): String =
                    Json.encodeToString(Point.new(1, 2))
                        .fold((text) -> text, (error) -> error.message);

                fn indentedText(): String =
                    Json.pretty(2).encodeToString(Point.new(3, 4))
                        .fold((text) -> text, (error) -> error.message);

                fn indentedSum(text: String): Int =
                    Json.pretty(2).decodeFromString<Point>(text)
                        .fold((point) -> point.x + point.y, (error) -> -1);

                fn indentedValue(): String = parse("[1]").fold(
                    (value) -> Json.pretty(2).stringifyValue(value)
                        .fold((text) -> text, (error) -> getErrorMessage(error)),
                    (error) -> getErrorMessage(error));

                fn modelText(): String =
                    Json.pretty(2).encodeToStringWith(UserWire, User.new(1, "Ada")) {
                        field(User::name);
                    }.fold((text) -> text, (error) -> getErrorMessage(error));

                fn flag(config: Json): String =
                    if (config.isCompact()) then "compact" else "indented";

                fn flags(): String =
                    flag(Json.compact()) + "/" + flag(Json.pretty(2));
                """;
        final var parsed = Parser.of(Scanner.from(source, "JsonConfigApiTest.zn").scanTokens())
                .parseCompilationUnit("JsonConfigApiTest.zn");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    java.util.List.of(parsed), "json.config.Main", "json.config",
                    java.util.List.of(), true, java.util.List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass("json.config.Main");
                assertEquals("{\"x\":1,\"y\":2}",
                        program.getMethod("compactText").invoke(null));
                assertEquals("""
                        {
                          "x": 3,
                          "y": 4
                        }""", program.getMethod("indentedText").invoke(null));
                assertEquals(7, program.getMethod("indentedSum", String.class).invoke(null, """
                        {
                          "x": 3,
                          "y": 4
                        }"""));
                assertEquals("""
                        [
                          1
                        ]""", program.getMethod("indentedValue").invoke(null));
                assertEquals("""
                        {
                          "full_name": "Ada"
                        }""", program.getMethod("modelText").invoke(null));
                assertEquals("compact/indented", program.getMethod("flags").invoke(null));
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
