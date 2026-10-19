package com.maruseron.zeron.analize;

import com.maruseron.zeron.ZeronVersions;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class StringTest {
    @Test
    public void stringExtensionsDelegateToJavaWithUtf16AndSafeSubstringSemantics() throws Exception {
        final var output = Files.createTempDirectory("zeron-string-test");
        final var programName = "StringProgram" + UUID.randomUUID().toString().replace("-", "");
        final var unit = parse("strings.zn", """
                fn length(): Int = "A😀B".length();
                fn empty(): Boolean = "".isEmpty();
                fn nonEmpty(): Boolean = "text".isEmpty();
                fn firstIndex(): Int = "a😀b😀".indexOf("😀");
                fn lastIndex(): Int = "a😀b😀".lastIndexOf("😀");
                fn missingIndex(): Int = "zeron".indexOf("missing");
                fn containsText(): Boolean = "zeron strings".contains("strings");
                fn startsWithText(): Boolean = "zeron strings".startsWith("zeron");
                fn endsWithText(): Boolean = "zeron strings".endsWith("strings");
                fn substringValue(): String = match ("A😀B".substring(1, 3)) {
                    case Option.Some(value) -> value;
                    case Option.None() -> "";
                };
                fn rejectsInvalidBounds(): Boolean =
                    not "abc".substring(-1, 1).isSome()
                    and not "abc".substring(2, 1).isSome()
                    and not "abc".substring(0, 4).isSome();
                fn acceptsEmptySubstring(): Boolean =
                    "abc".substring(2, 2).isSome();
                """);
        final var compiler = CompilationService.forCompilationUnits(
                List.of(unit), programName, "", List.of(), true, List.of(), output);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals(4, program.getMethod("length").invoke(null));
                assertTrue((Boolean) program.getMethod("empty").invoke(null));
                assertFalse((Boolean) program.getMethod("nonEmpty").invoke(null));
                assertEquals(1, program.getMethod("firstIndex").invoke(null));
                assertEquals(4, program.getMethod("lastIndex").invoke(null));
                assertEquals(-1, program.getMethod("missingIndex").invoke(null));
                assertTrue((Boolean) program.getMethod("containsText").invoke(null));
                assertTrue((Boolean) program.getMethod("startsWithText").invoke(null));
                assertTrue((Boolean) program.getMethod("endsWithText").invoke(null));
                assertEquals("😀", program.getMethod("substringValue").invoke(null));
                assertTrue((Boolean) program.getMethod("rejectsInvalidBounds").invoke(null));
                assertTrue((Boolean) program.getMethod("acceptsEmptySubstring").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void stringExtensionsWorkFromCompiledStandardLibraryMetadata() throws Exception {
        final var libraryOutput = Files.createTempDirectory("zeron-string-stdlib");
        final var clientOutput = Files.createTempDirectory("zeron-string-client");
        final var programName = "StringClient" + UUID.randomUUID().toString().replace("-", "");
        try {
            final var libraryCompiler = CompilationService.forStandardLibrary(libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFrom(libraryOutput.resolve(
                    ZeronVersions.API_INDEX_RELATIVE_PATH));
            assertTrue(Files.exists(libraryOutput.resolve(
                    Path.of("zeron", "runtime", "StringUnicode.class"))));
            final var client = parse("StringClient.zn", """
                    fn result(): Boolean =
                        "zeron".startsWith("ze")
                        and "zeron".substring(1, 4).isSome()
                        and 'x'.toInt() == 120
                        and '😀'.toString() == "😀";
                    """);
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), programName, "", List.of(index), false, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                assertTrue((Boolean) loader.loadClass(programName).getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
        }
    }

    @Test
    public void characterAndScalarStringApisPreserveUtf16Compatibility() throws Exception {
        final var output = Files.createTempDirectory("zeron-char-test");
        final var programName = "CharProgram" + UUID.randomUUID().toString().replace("-", "");
        final var unit = parse("chars.zn", """
                        fn literalEscapes(): Boolean =
                            'A'.toInt() == 65 and '\\n'.toInt() == 10
                            and '\\u{1F600}'.toString() == "😀" and '😀' == '\\u{1F600}';
                        fn checkedConversion(): Boolean = match (128512.toChar()) {
                            case Option.Some(value) -> value.toString() == "😀";
                            case Option.None() -> false;
                        };
                        fn scalarTypeTest(value: Any): Boolean = value is Char;
                        fn safeScalarCast(value: Any?): Char? = value as? Char;
                        fn identity(value: Char): Char = value;
                        fn displayChar<T: Display<T>>(value: T): String = value.toString();
                        fn equalChar<T: Eq<T>>(left: T, right: T): Boolean = left.eq(right);
                        fn hashChar<T: Hashable<T>>(value: T): Int = value.hash();
                        fn genericCharOperations(): Boolean =
                            displayChar('😀') == "😀" and equalChar('x', 'x') and hashChar('x') == 120;
                        fn rejectsInvalidConversion(): Boolean =
                            not 55296.toChar().isSome() and not 1114112.toChar().isSome()
                            and not (-1).toChar().isSome();
                        fn scalarLength(): Int = "A😀é".codePointLength();
                        fn scalarAt(): Int = match ("A😀B".codePointAt(1)) {
                            case Option.Some(value) -> value.toInt();
                            case Option.None() -> -1;
                        };
                        fn scalarIndex(): Int = "A😀B😀".indexOfCodePoint('😀');
                        fn scalarSubstring(): String =
                            "A😀B".substringByCodePoints(1, 2).getOrElse("");
                        fn invalidScalarRange(): Boolean =
                            not "abc".substringByCodePoints(-1, 1).isSome()
                            and not "abc".substringByCodePoints(2, 1).isSome()
                            and not "abc".substringByCodePoints(0, 4).isSome();
                        fn scalarIteration(): Int = "A😀é".codePoints().count();
                        fn scalarSum(): Int = "A😀".codePoints().fold(0, (sum, value) -> sum + value.toInt());
                        fn exactlyOneScalar(): Boolean =
                            "😀".singleChar().isSome() and not "é".singleChar().isSome();
                        fn strictMalformedOffset(value: String): Int = match (value.codePointLengthStrict()) {
                            case Result.Err(UnicodeError.MalformedSurrogate(offset)) -> offset;
                            case Result.Ok(_) -> -1;
                        };
                        fn strictStreamOffset(value: String): Int = match (value.codePointsStrict()) {
                            case Result.Err(UnicodeError.MalformedSurrogate(offset)) -> offset;
                            case Result.Ok(_) -> -1;
                        };
                        fn strictStreamCount(value: String): Int = match (value.codePointsStrict()) {
                            case Result.Err(_) -> -1;
                            case Result.Ok(stream) -> stream.count();
                        };
                        fn replacementScalar(value: String): Int = match (value.codePointAt(0)) {
                            case Option.Some(character) -> character.toInt();
                            case Option.None() -> -1;
                        };
                        fn sanitized(value: String): String =
                            value.substringByCodePoints(0, value.codePointLength()).getOrElse("");
                """);
        try {
            final var compiler = CompilationService.forCompilationUnits(
                            List.of(unit), programName, "", List.of(), true, List.of(), output);
                    compiler.resolve();
                    compiler.compile();
                    try (final var loader = new URLClassLoader(
                            new java.net.URL[]{output.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                        final var program = loader.loadClass(programName);
                        assertTrue((Boolean) program.getMethod("literalEscapes").invoke(null));
                        assertTrue((Boolean) program.getMethod("checkedConversion").invoke(null));
                        assertTrue((Boolean) program.getMethod("rejectsInvalidConversion").invoke(null));
                        assertTrue((Boolean) program.getMethod("scalarTypeTest", Object.class)
                                .invoke(null, 65));
                        assertFalse((Boolean) program.getMethod("scalarTypeTest", Object.class)
                                .invoke(null, 55296));
                        assertEquals(Integer.valueOf(65), program.getMethod("safeScalarCast", Object.class)
                                .invoke(null, 65));
                        assertEquals(null, program.getMethod("safeScalarCast", Object.class)
                                .invoke(null, 55296));
                        assertEquals(0x1F600, program.getMethod("identity", int.class).invoke(null, 0x1F600));
                        assertTrue((Boolean) program.getMethod("genericCharOperations").invoke(null));
                        assertEquals(4, program.getMethod("scalarLength").invoke(null));
                        assertEquals(0x1F600, program.getMethod("scalarAt").invoke(null));
                        assertEquals(1, program.getMethod("scalarIndex").invoke(null));
                        assertEquals("😀", program.getMethod("scalarSubstring").invoke(null));
                        assertTrue((Boolean) program.getMethod("invalidScalarRange").invoke(null));
                        assertEquals(4, program.getMethod("scalarIteration").invoke(null));
                        assertEquals(0x41 + 0x1F600, program.getMethod("scalarSum").invoke(null));
                        assertTrue((Boolean) program.getMethod("exactlyOneScalar").invoke(null));

                        final var malformed = new String(new char[]{'\uD800', 'x'});
                        assertEquals(0, program.getMethod("strictMalformedOffset", String.class)
                                .invoke(null, malformed));
                        assertEquals(0, program.getMethod("strictStreamOffset", String.class)
                                .invoke(null, malformed));
                        assertEquals(0xFFFD, program.getMethod("replacementScalar", String.class)
                                .invoke(null, malformed));
                        assertEquals("\uFFFDx", program.getMethod("sanitized", String.class)
                                .invoke(null, malformed));
                        assertEquals(-1, program.getMethod("strictMalformedOffset", String.class)
                                .invoke(null, "A😀"));
                        assertEquals(2, program.getMethod("strictStreamCount", String.class)
                                .invoke(null, "A😀"));
                        assertEquals(1, program.getMethod("strictMalformedOffset", String.class)
                                .invoke(null, new String(new char[]{'a', '\uDC00'})));
                    }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens()).parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (final var paths = Files.walk(root)) {
            for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
