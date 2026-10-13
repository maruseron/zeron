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
            final var client = parse("StringClient.zn", """
                    fn result(): Boolean =
                        "zeron".startsWith("ze")
                        and "zeron".substring(1, 4).isSome();
                    """);
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), programName, "", List.of(index), false, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertTrue((Boolean) loader.loadClass(programName).getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
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
