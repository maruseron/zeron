package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class StringApiTest {
    @Test
    public void stringInventorySplitsLinesParsesAndJoins() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "stringClient" + suffix;
        final var className = packageName + ".StringTest" + suffix;
        final var source = parse("StringTest.zn", """
                package %s;
                import zeron.lang.*;
                import zeron.collections.Collector;
                import zeron.collections.Stream;

                fn describe(value: Option<Char>): String = match (value) {
                    case Option.Some(char) -> char.toString();
                    case Option.None() -> "none";
                };
                fn splitLiteral(): String = String.join("|", "a,b,".split(","));
                fn splitSingleSeparatorCount(): Int = "a b c".split(" ").count();
                fn splitEmptySeparator(): String = String.join("|", "abc".split(""));
                fn splitEmptyTextCount(): Int = "".split(",").count();
                fn splitEmptySeparatorOnEmptyText(): Int = "".split("").count();
                fn singleLine(): String = String.join("|", "only".lines());
                fn emptyTextLinesCount(): Int = "".lines().count();
                fn charAt(): String = describe("hello".charAt(1));
                fn atAlias(): String = describe("hello".at(4));
                fn charAtBeyondEnd(): String = describe("hello".charAt(5));
                fn charAtNegative(): String = describe("hello".charAt(-1));
                fn caseConversion(): String = "Zeron Guide".toUpperCase();
                fn trimming(): String {
                    let padded = "  padded  ";
                    return "[" + padded.trim() + "][" + padded.trimStart()
                        + "][" + padded.trimEnd() + "]";
                }
                fn blankCheck(): Boolean = "   ".isBlank() and not " x ".isBlank();
                fn replacement(): String = "a-b-c".replace("-", "+");
                fn parseIntOk(): Int = "42".toInt().getOrElse(-1);
                fn parseIntSigned(): Int = "-7".toInt().getOrElse(-1);
                fn parseIntInvalid(): Int = "4x".toInt().getOrElse(-1);
                fn parseIntOverflow(): Int = "99999999999".toInt().getOrElse(-1);
                fn parseFloatOk(): Float = "2.5e2".toFloat().getOrElse(-1.0);
                fn parseFloatInvalid(): Float = "1.2.3".toFloat().getOrElse(-1.0);
                fn regexSplit(): String = match ("a1b22c".splitRegex("[0-9]+")) {
                    case Result.Ok(parts) -> String.join("|", parts);
                    case Result.Err(_) -> "unexpected";
                };
                fn regexSplitInvalid(): Boolean = match ("abc".splitRegex("[")) {
                    case Result.Ok(_) -> false;
                    case Result.Err(_) -> true;
                };
                fn joinEmpty(): String = "[" + String.join(", ", emptyStrings()) + "]";
                fn emptyStrings(): Array<String> = [];
                fn joinSingle(): String = String.join(", ", ["solo"]);
                fn joinMultiple(): String = String.join(", ", ["a", "b", "c"]);
                fn collectThroughJoiner(): String =
                    "abc".split("").collect(StringJoiner.withSeparator(":"));
                fn collectFromLocalJoiner(): String {
                    let joiner = StringJoiner.withSeparator(":");
                    let first = "ab".split("").collect(joiner);
                    let second = "cd".split("").collect(joiner);
                    return first + "|" + second;
                }
                fn collectThroughFactory(): String {
                    let collector = Collector.of<String, Int, String>("",
                        (text, value) -> text + value.toString(),
                        (text) -> "[" + text + "]");
                    return Stream<Int>.fromArray([1, 2, 3]).collect(collector);
                }
                fn joinerOnStreamDirectly(): String =
                    StringJoiner.withSeparator("-").join(Stream<Int>.fromArray([1, 2, 3])
                        .map(value -> value.toString()));
                fn splitDoesNotShareIterators(): Int {
                    let parts = "a,b,c".split(",");
                    return parts.count() + parts.count();
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(List.of(source), className,
                packageName);

        try {
            deleteTree(Path.of("dist"));
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()},
                    getClass().getClassLoader())) {
                final var test = loader.loadClass(className);
                assertEquals("a|b|", test.getMethod("splitLiteral").invoke(null));
                assertEquals(3, test.getMethod("splitSingleSeparatorCount").invoke(null));
                assertEquals("a|b|c", test.getMethod("splitEmptySeparator").invoke(null));
                assertEquals(1, test.getMethod("splitEmptyTextCount").invoke(null));
                assertEquals(0, test.getMethod("splitEmptySeparatorOnEmptyText").invoke(null));
                assertEquals("only", test.getMethod("singleLine").invoke(null));
                assertEquals(0, test.getMethod("emptyTextLinesCount").invoke(null));
                assertEquals("e", test.getMethod("charAt").invoke(null));
                assertEquals("o", test.getMethod("atAlias").invoke(null));
                assertEquals("none", test.getMethod("charAtBeyondEnd").invoke(null));
                assertEquals("none", test.getMethod("charAtNegative").invoke(null));
                assertEquals("ZERON GUIDE", test.getMethod("caseConversion").invoke(null));
                assertEquals("[padded][padded  ][  padded]", test.getMethod("trimming").invoke(null));
                assertEquals(true, test.getMethod("blankCheck").invoke(null));
                assertEquals("a+b+c", test.getMethod("replacement").invoke(null));
                assertEquals(42, test.getMethod("parseIntOk").invoke(null));
                assertEquals(-7, test.getMethod("parseIntSigned").invoke(null));
                assertEquals(-1, test.getMethod("parseIntInvalid").invoke(null));
                assertEquals(-1, test.getMethod("parseIntOverflow").invoke(null));
                assertEquals(250.0, test.getMethod("parseFloatOk").invoke(null));
                assertEquals(-1.0, test.getMethod("parseFloatInvalid").invoke(null));
                assertEquals("a|b|c", test.getMethod("regexSplit").invoke(null));
                assertEquals(true, test.getMethod("regexSplitInvalid").invoke(null));
                assertEquals("[]", test.getMethod("joinEmpty").invoke(null));
                assertEquals("solo", test.getMethod("joinSingle").invoke(null));
                assertEquals("a, b, c", test.getMethod("joinMultiple").invoke(null));
                assertEquals("a:b:c", test.getMethod("collectThroughJoiner").invoke(null));
                assertEquals("a:b|c:d", test.getMethod("collectFromLocalJoiner").invoke(null));
                assertEquals("[123]", test.getMethod("collectThroughFactory").invoke(null));
                assertEquals("1-2-3", test.getMethod("joinerOnStreamDirectly").invoke(null));
                assertEquals(6, test.getMethod("splitDoesNotShareIterators").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static com.maruseron.zeron.ast.CompilationUnit parse(final String sourcePath,
                                                                final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(sourcePath);
    }
}
