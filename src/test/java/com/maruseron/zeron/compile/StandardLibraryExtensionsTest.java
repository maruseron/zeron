package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class StandardLibraryExtensionsTest {
    @Test
    public void statisticsOrderedCollectionsAndBuildersWork() throws Exception {
        final var packageName = "stdlibStats" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-stdlib-stats-");
        final var unit = parse("""
                package %s;
                import zeron.collections.ListBuilder;
                import zeron.collections.MapBuilder;
                import zeron.collections.PriorityQueue;
                import zeron.collections.SetBuilder;
                import zeron.collections.SortedMap;
                import zeron.collections.SortedSet;
                import zeron.math.*;
                public fn run(): Int {
                    let mean = Statistics.mean([1.0, 2.0, 3.0]).getOrElse(-1.0);
                    let median = Statistics.median([4.0, 1.0, 3.0, 2.0]).getOrElse(-1.0);
                    let variance = Statistics.variance([1.0, 2.0, 3.0]).getOrElse(-1.0);

                    let queue = PriorityQueue<Int>.empty();
                    queue.add(3);
                    queue.add(1);
                    queue.add(2);
                    let queueOrder = queue.poll().getOrElse(0) * 100
                        + queue.poll().getOrElse(0) * 10
                        + queue.poll().getOrElse(0);

                    let sorted = SortedMap<Int, String>.empty();
                    sorted.put(3, "c");
                    sorted.put(1, "a");
                    sorted.put(2, "b");
                    let sortedOrder = sorted.firstKey().getOrElse(0) * 100
                        + sorted.lastKey().getOrElse(0) * 10 + sorted.size;

                    let set = SortedSet<Int>.empty();
                    set.add(3);
                    set.add(1);
                    set.add(3);
                    let setOrder = set.first().getOrElse(0) * 100
                        + set.last().getOrElse(0) * 10 + set.size;

                    let listBuilder = ListBuilder<Int>.empty();
                    listBuilder.add(1);
                    listBuilder.add(2);
                    listBuilder.add(3);
                    let list = listBuilder.build();
                    let setBuilder = SetBuilder<Int>.empty();
                    setBuilder.add(7);
                    setBuilder.add(7);
                    let builtSet = setBuilder.build();
                    let mapBuilder = MapBuilder<Int, String>.empty();
                    mapBuilder.put(4, "x");
                    let builtMap = mapBuilder.build();

                    return mean.toInt().getOrElse(0) * 1000000
                        + median.toInt().getOrElse(0) * 100000
                        + variance.toInt().getOrElse(0) * 10000
                        + queueOrder * 10
                        + sortedOrder
                        + setOrder
                        + list.size
                        + builtSet.size
                        + builtMap.size;
                }
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                assertEquals(2201500, loader.loadClass(packageName + ".Main")
                        .getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void randomBuffersFormatterAndRegexWork() throws Exception {
        final var packageName = "stdlibText" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-stdlib-text-");
        final var unit = parse("""
                package %s;
                import zeron.buffer.*;
                import zeron.collections.ArrayList;
                import zeron.random.Random;
                import zeron.text.*;
                import zeron.text.RegexError;
                public fn buffersRoundTrip(): Boolean {
                    let buffer = ByteBuffer.empty();
                    buffer.order = ByteOrder.LittleEndian();
                    buffer.putInt(0x01020304);
                    buffer.putVarInt(300);
                    let bytes = buffer.toArray();
                    let reader = ByteReader.over(bytes);
                    reader.order = ByteOrder.LittleEndian();
                    let decoded = match (reader.getInt()) {
                        case Result.Ok(value) -> value;
                        case Result.Err(_) -> 0;
                    };
                    let varInt = match (reader.getVarInt()) {
                        case Result.Ok(value) -> value;
                        case Result.Err(_) -> 0;
                    };
                    return decoded == 0x01020304 and varInt == 300
                        and Hex.encode(bytes) == "04030201ac02";
                }
                public fn encodingsRoundTrip(): Boolean {
                    let bytes = match (Hex.decode("ff00")) {
                        case Result.Ok(value) -> value;
                        case Result.Err(_) -> ByteBuffer.empty().toArray();
                    };
                    let base64 = Base64.encode(bytes);
                    let decoded = match (Base64.decode(base64)) {
                        case Result.Ok(value) -> value;
                        case Result.Err(_) -> ByteBuffer.empty().toArray();
                    };
                    return base64 == "/wA=" and decoded.length == 2
                        and decoded[0].toInt() == 255
                        and VarInt.zigZagDecodeInt(VarInt.zigZagEncodeInt(-7)) == -7
                        and VarInt.zigZagDecodeLong(VarInt.zigZagEncodeLong(-9L)) == -9L;
                }
                public fn randomStaysBounded(): Boolean {
                    let random = Random.seeded(42L);
                    let below = random.nextIntBelow(10).getOrElse(-1);
                    let between = random.nextIntBetween(3, 4).getOrElse(-1);
                    let values = ArrayList<Int>.fromArray([1, 2, 3, 4, 5]);
                    random.shuffle<Int>(values);
                    let mut sum = 0;
                    for (let value in values) sum = sum + value;
                    return below >= 0 and below < 10 and between == 3
                        and values.size == 5 and sum == 15;
                }
                public fn formattingWorks(): Boolean {
                    let formatted = match (Formatter.format("{} {:>4} {{ok}}", ["a", "b"])) {
                        case Result.Ok(value) -> value;
                        case Result.Err(_) -> "error";
                    };
                    let explicitFormatted = match (Formatter.format("{0} {1} {0}", ["x", "y"])) {
                        case Result.Ok(value) -> value;
                        case Result.Err(_) -> "error";
                    };
                    return formatted == "a    b {ok}"
                        and Formatter.fixed(1.25, 1) == "1.3"
                        and Formatter.zeroPadded(42L, 4) == "0042"
                        and explicitFormatted == "x y x";
                }
                public fn regexFacts(): String =
                    match (Regex.compile("(?<word>[a-z]+)-([0-9]+)")) {
                        case Result.Ok(regex) -> match (regex.find("abc-42")) {
                            case Option.Some(found) ->
                                found.text + "|"
                                + found.group(1).getOrElse("missing") + "|"
                                + found.namedGroup("word").getOrElse("missing") + "|"
                                + regex.matches("abc-42").toString() + "|"
                                + regex.findAll("a-1 b-2").length.toString() + "|"
                                + regex.replaceAll("a-1 b-2", "x");
                            case Option.None() -> "no-match";
                        };
                        case Result.Err(error) -> match (error) {
                            case RegexError.InvalidPattern(message, offset) ->
                                "error|" + message + "|" + offset.toString();
                        };
                    };
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(true, main.getMethod("buffersRoundTrip").invoke(null));
                assertEquals(true, main.getMethod("encodingsRoundTrip").invoke(null));
                assertEquals(true, main.getMethod("randomStaysBounded").invoke(null));
                assertEquals(true, main.getMethod("formattingWorks").invoke(null));
                assertEquals("abc-42|abc|abc|true|2|x x",
                        main.getMethod("regexFacts").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void tomlAndXmlParsersExposeTablesElementsAndErrors() throws Exception {
        final var packageName = "stdlibParsers" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-stdlib-parsers-");
        final var unit = parse("""
                package %s;
                import zeron.parsers.toml.*;
                import zeron.parsers.xml.*;
                fn tomlPort(table: TomlTable): Int =
                    match (table.get("db")) {
                        case Option.Some(value) -> match (value) {
                            case TomlValue.TableValue(db) -> match (db.get("port")) {
                                case Option.Some(item) -> match (item) {
                                    case TomlValue.IntegerValue(number) -> number.toInt().getOrElse(0);
                                    case _ -> -2;
                                };
                                case Option.None() -> -3;
                            };
                            case _ -> -4;
                        };
                        case Option.None() -> -5;
                    };

                fn xmlScore(root: XmlElement): Int {
                    let child = root.child(0).getOrElse(root);
                    return root.attribute("version").getOrElse("0").toInt().getOrElse(0) * 100
                        + root.childCount() * 10
                        + child.text().length();
                }

                public fn run(): Int {
                    let port = match (Toml.parse(
                        "title = \\"demo\\"\\n[db]\\nport = 5432\\n"
                    )) {
                        case Result.Ok(table) -> tomlPort(table);
                        case Result.Err(_) -> -1;
                    };
                    let tomlError = match (Toml.parse("broken = [")) {
                        case Result.Ok(_) -> -1;
                        case Result.Err(error) -> match (error) {
                            case TomlError.TomlParseError(_, line, _) -> line;
                        };
                    };
                    let xmlValue = match (Xml.parse(
                        "<root version=\\"1\\"><child>hello</child><other/></root>"
                    )) {
                        case Result.Ok(document) -> xmlScore(document.root());
                        case Result.Err(_) -> -2;
                    };
                    let doctype = match (Xml.parse("<!DOCTYPE root><root/>")) {
                        case Result.Ok(_) -> -1;
                        case Result.Err(_) -> 1;
                    };
                    return port + tomlError + xmlValue + doctype;
                }
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                assertEquals(5559, loader.loadClass(packageName + ".Main")
                        .getMethod("run").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void testAssertionsReportTheirMessages() throws Exception {
        final var packageName = "stdlibTestApi" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-stdlib-test-api-");
        final var unit = parse("""
                package %s;
                import zeron.test.*;
                fn passing(): Unit performs TestFailure {
                    assertContains("alphabet", "pha");
                    assertNotContains("alphabet", "zzz");
                    assertStartsWith("alphabet", "alpha");
                    assertEndsWith("alphabet", "bet");
                    assertGreaterThan(3, 2);
                    assertLessThan(2, 3);
                    assertApproximatelyEquals(1.0, 1.01, 0.02);
                }
                fn failing(): Unit performs TestFailure {
                    assertGreaterThan(1, 2, "one is not greater than two");
                }
                public fn passingChecks(): Unit performs TestFailure {
                    passing();
                }
                public fn failingCheck(): Unit performs TestFailure {
                    failing();
                }
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                final var main = loader.loadClass(packageName + ".Main");
                main.getMethod("passingChecks").invoke(null);
                final var thrown = assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> main.getMethod("failingCheck").invoke(null));
                assertEquals("zeron.runtime.RaisedEffect",
                        thrown.getCause().getClass().getName());
                final var payload = ((zeron.runtime.RaisedEffect) thrown.getCause()).payload();
                assertEquals("zeron.test.TestFailure", payload.getClass().getName());
            }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationService compiler(final CompilationUnit unit, final String packageName,
                                               final Path output) {
        return CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(unit)),
                packageName + ".Main", packageName, List.of(), true, List.of(), output);
    }

    private static URLClassLoader loader(final Path output) throws Exception {
        return new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                StandardLibraryExtensionsTest.class.getClassLoader());
    }

    private static CompilationUnit parse(final String source) {
        return Parser.of(Scanner.from(source, "Main.zn").scanTokens())
                .parseCompilationUnit("Main.zn");
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
