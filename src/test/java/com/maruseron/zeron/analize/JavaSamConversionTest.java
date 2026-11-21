package com.maruseron.zeron.analize;

import com.maruseron.zeron.TestPaths;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ZeronVersions;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.StandardLibrary;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class JavaSamConversionTest {
    private static final Path JAVA_FIXTURE_ROOT = TestPaths.projectFile("target", "test-classes");

    @Test
    public void convertsJdkAndClasspathLambdasAtJavaCallSites() throws Exception {
        final var packageName = "sam" + UUID.randomUUID().toString().replace("-", "");
        final var className = packageName + ".JdkSamClient";
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-sam-output-");
        final var source = parse("JdkSamClient.zn", """
                package %s;
                import com.maruseron.zeron.fixtures.JavaSamFixture;
                fn apply(value: String): String? = unsafe JavaSamFixture.applyFunction((text) -> text!!.trim(), value);
                fn test(value: String): Boolean = unsafe JavaSamFixture.testPredicate((text) -> text!! == "yes", value);
                fn accept(value: String): Unit = unsafe JavaSamFixture.acceptConsumer((text) -> println(text), value);
                fn supply(): String? = unsafe JavaSamFixture.supply(() -> "supplied");
                fn compare(): Int = unsafe JavaSamFixture.compare(
                    (left, right) -> left!!.length() - right!!.length(), "aaa", "a");
                fn unary(value: String): String? = unsafe JavaSamFixture.applyUnary((text) -> text!! + "!", value);
                fn combine(value: String, number: Int): Int? =
                    unsafe JavaSamFixture.combine(
                        (text, amount) -> text!!.length() + amount!!, value, number);
                fn intUnary(value: Int): Int = unsafe JavaSamFixture.applyIntUnary((number) -> number * 2, value);
                fn transform(value: String): String? =
                    unsafe JavaSamFixture.transform((text) -> text!!.toUpperCase(), value);
                fn transformGeneric(value: String): String? =
                    unsafe JavaSamFixture.transformGeneric((text) -> text!! + "?", value);
                fn upper(value: String?): String? = value!!.toUpperCase();
                fn viaReference(value: String): String? =
                    unsafe JavaSamFixture.applyFunction(upper, value);
                fn captured(value: String): String? {
                    let suffix = "!";
                    return unsafe JavaSamFixture.applyFunction((text) -> text!! + suffix, value);
                }
                fn run(): Unit = unsafe JavaSamFixture.run(() -> println("ran"));
                fn call(): String? = unsafe JavaSamFixture.call(() -> "called");
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(List.of(source), className,
                packageName, List.of(), true, List.of(JAVA_FIXTURE_ROOT), output);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    output.toUri().toURL(), JAVA_FIXTURE_ROOT.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                assertEquals("hi", program.getMethod("apply", String.class).invoke(null, "  hi  "));
                assertEquals(true, program.getMethod("test", String.class).invoke(null, "yes"));
                assertEquals(false, program.getMethod("test", String.class).invoke(null, "no"));
                assertEquals("supplied", program.getMethod("supply").invoke(null));
                assertEquals(2, program.getMethod("compare").invoke(null));
                assertEquals("x!", program.getMethod("unary", String.class).invoke(null, "x"));
                assertEquals(7, program.getMethod("combine", String.class, int.class).invoke(null, "abc", 4));
                assertEquals(10, program.getMethod("intUnary", int.class).invoke(null, 5));
                assertEquals("HI", program.getMethod("transform", String.class).invoke(null, "hi"));
                assertEquals("hi?", program.getMethod("transformGeneric", String.class).invoke(null, "hi"));
                assertEquals("HI", program.getMethod("viaReference", String.class).invoke(null, "hi"));
                assertEquals("hi!", program.getMethod("captured", String.class).invoke(null, "hi"));
                assertEquals("called", program.getMethod("call").invoke(null));
                program.getMethod("run").invoke(null);
                assertTrue(Files.isRegularFile(output.resolve(packageName).resolve("JdkSamClient.class")));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void convertsAtZeronParametersAndAcrossCompiledLibraries() throws Exception {
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-sam-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-sam-client-");
        final var packageName = "samlib" + UUID.randomUUID().toString().replace("-", "");
        final var library = parse("library/SamLibrary.zn", """
                package %s;
                import java.util.function.Function;
                import com.maruseron.zeron.fixtures.JavaSamFixture;
                public fn apply(value: String, function: Function<String?, String?>): String? =
                    unsafe JavaSamFixture.applyFunction(function, value);
                """.formatted(packageName));
        final var libraryCompiler = CompilationService.forCompilationUnits(List.of(library),
                packageName + ".SamLibrary", packageName, List.of(), true,
                List.of(JAVA_FIXTURE_ROOT), libraryOutput);
        try {
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFrom(
                    libraryOutput.resolve(ZeronVersions.API_INDEX_RELATIVE_PATH));
            final var client = parse("client/Main.zn", """
                    package client;
                    import %s.apply;
                    fn result(value: String): String? =
                        apply(value, (text) -> text!!.toUpperCase());
                    """.formatted(packageName));
            final var clientCompiler = CompilationService.forCompilationUnits(List.of(client),
                    "client.Main", "client", List.of(index), true,
                    List.of(JAVA_FIXTURE_ROOT), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL(),
                    JAVA_FIXTURE_ROOT.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals("HI", loader.loadClass("client.Main")
                        .getMethod("result", String.class).invoke(null, "hi"));
            }
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
        }
    }

    @Test
    public void rejectsEffectfulCallbacksAndAmbiguousSamOverloads() {
        final var effectful = parse("EffectfulSam.zn", """
                package interop;
                import com.maruseron.zeron.fixtures.JavaSamFixture;
                public effect Failure {}
                fn boom(): String performs Failure = perform Failure.new();
                fn invalid(value: String): String =
                    unsafe JavaSamFixture.applyFunction((text) -> boom(), value);
                """);
        final var effectfulCompiler = CompilationService.forCompilationUnits(List.of(effectful),
                "interop.EffectfulSam", "interop", List.of(), true, List.of(JAVA_FIXTURE_ROOT));
        final var effectFailure = org.junit.Assert.assertThrows(ResolutionError.class,
                effectfulCompiler::resolve);
        assertTrue(effectFailure.getMessage(),
                effectFailure.getMessage().contains("raised effects")
                        || effectFailure.getMessage().contains("performs row"));

        final var ambiguous = parse("AmbiguousSam.zn", """
                package interop;
                import com.maruseron.zeron.fixtures.JavaSamFixture;
                fn invalid(value: String): String =
                    unsafe JavaSamFixture.ambiguous((text) -> text, value);
                """);
        final var ambiguousCompiler = CompilationService.forCompilationUnits(List.of(ambiguous),
                "interop.AmbiguousSam", "interop", List.of(), true, List.of(JAVA_FIXTURE_ROOT));
        final var ambiguityFailure = org.junit.Assert.assertThrows(ResolutionError.class,
                ambiguousCompiler::resolve);
        assertTrue(ambiguityFailure.getMessage(),
                ambiguityFailure.getMessage().contains("ambiguous for a functional-interface argument"));

        final var zeronParameter = parse("EffectfulZeronSam.zn", """
                package interop;
                import java.util.function.Function;
                import com.maruseron.zeron.fixtures.JavaSamFixture;
                public effect Failure {}
                fn boom(): String performs Failure = perform Failure.new();
                fn invoke(function: Function<String?, String?>, value: String): String? =
                    unsafe JavaSamFixture.applyFunction(function, value);
                fn invalid(value: String): String? = invoke((text) -> boom(), value);
                """);
        final var zeronParameterCompiler = CompilationService.forCompilationUnits(
                List.of(zeronParameter), "interop.EffectfulZeronSam", "interop",
                List.of(), true, List.of(JAVA_FIXTURE_ROOT));
        final var zeronEffectFailure = org.junit.Assert.assertThrows(ResolutionError.class,
                zeronParameterCompiler::resolve);
        assertTrue(zeronEffectFailure.getMessage(),
                zeronEffectFailure.getMessage().contains("raised effects")
                        || zeronEffectFailure.getMessage().contains("performs row"));

        final var effectfulReference = parse("EffectfulSamReference.zn", """
                package interop;
                import com.maruseron.zeron.fixtures.JavaSamFixture;
                public effect Failure {}
                fn boom(text: String?): String? performs Failure = perform Failure.new();
                fn invalid(value: String): String =
                    unsafe JavaSamFixture.applyFunction(boom, value);
                """);
        final var referenceCompiler = CompilationService.forCompilationUnits(
                List.of(effectfulReference), "interop.EffectfulSamReference", "interop",
                List.of(), true, List.of(JAVA_FIXTURE_ROOT));
        final var referenceFailure = org.junit.Assert.assertThrows(ResolutionError.class,
                referenceCompiler::resolve);
        assertTrue(referenceFailure.getMessage(),
                referenceFailure.getMessage().contains("raised effects")
                        || referenceFailure.getMessage().contains("performs row"));
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens()).parseCompilationUnit(sourceName);
    }

    private static void deleteTree(final Path path) throws java.io.IOException {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
