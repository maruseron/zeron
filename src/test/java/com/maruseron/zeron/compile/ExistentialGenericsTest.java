package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
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
import static org.junit.Assert.assertTrue;

public final class ExistentialGenericsTest {
    @Test
    public void starProjectionReadsAnUnknownArgument() throws Exception {
        assertCompiledResult("star", """
                package %s;
                public class Box<T: nullable> {
                    public property value: T;
                    public constructor new;
                }
                fn read(box: Box<*>): Any? = box.value;
                fn use(): Any? {
                    let box: Box<Int> = Box.new(7);
                    return read(box);
                }
                """, "use", 7);
    }

    @Test
    public void starProjectionIsRejectedForArrays() {
        assertRejected("""
                fn bad(values: Array<*>): Unit = ();
                """);
    }

    @Test
    public void anonymousExistentialPacksAndUsesInheritedBoundEvidence() throws Exception {
        assertCompiledResult("anonymous", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn describe(box: Box<: Render>): String = box.value.render();
                fn use(): String = describe(Box.new(Text.new()));
                """, "use", "text");
    }

    @Test
    public void anonymousExistentialSignaturesRoundTripThroughLibraryIndex() throws Exception {
        final var packageName = "zep0104Index" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-zep0104-index-");
        final var unit = parse("Library.zn", """
                package %s;
                public contract Marker {}
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public fn describe(box: Box<: Marker>): Any? = box.value;
                public fn isIntBox(box: Box<: reified>): Boolean = box is Box<Int>;
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), packageName + ".Library", packageName, List.of(), true,
                    List.of(), output);
            compiler.resolve();
            compiler.compile();

            final var index = ZeronLibraryIndex.readFromDirectory(output);
            final var functions = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .toList();
            final var describe = functions.stream()
                    .filter(function -> function.qualifiedName().equals(packageName + ".describe"))
                    .findFirst().orElseThrow().signature();
            final var reified = functions.stream()
                    .filter(function -> function.qualifiedName().equals(packageName + ".isIntBox"))
                    .findFirst().orElseThrow().signature();
            assertEquals(1, describe.typeParameters().size());
            assertTrue(describe.typeParameters().getFirst().isAnonymousExistential());
            assertTrue(describe.explicitTypeParameters().isEmpty());
            assertEquals(1, reified.typeParameters().size());
            assertTrue(reified.typeParameters().getFirst().isAnonymousExistential());
            assertTrue(reified.typeParameters().getFirst().isReified());
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void contractMethodsCanUseAnonymousExistentials() throws Exception {
        assertCompiledResult("contractAnonymous", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public contract Renderer {
                    render(box: Box<: Render>): String;
                }
                public class RendererImpl is Renderer {
                    public constructor new;
                    public render(box: Box<: Render>): String = box.value.render();
                }
                fn use(): String = RendererImpl.new().render(Box.new(Text.new()));
                """, "use", "text");
    }

    @Test
    public void reifiedAnonymousExistentialSupportsExactAppliedTests() throws Exception {
        assertCompiledResult("anonymousReified", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn isIntBox(box: Box<: reified>): Boolean = box is Box<Int>;
                fn useInt(): Boolean = isIntBox(Box.new(7));
                fn useString(): Boolean = isIntBox(Box.new("value"));
                """, "useInt", true);
    }

    @Test
    public void reifiedTypeVariableSupportsExactAppliedTests() throws Exception {
        assertCompiledResult("reifiedExact", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn isIntBox<T: reified>(value: T): Boolean = value is Box<Int>;
                fn useInt(): Boolean = isIntBox(Box.new(7));
                fn useString(): Boolean = isIntBox("value");
                """, "useInt", true);
    }

    @Test
    public void exactAppliedTestRequiresAReifiedSource() {
        assertRejected("""
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn invalid<T>(value: T): Boolean = value is Box<Int>;
                """);
    }

    @Test
    public void exactAppliedTestsDistinguishArguments() throws Exception {
        assertCompiledResult("reifiedFalse", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn isIntBox<T: reified>(value: T): Boolean = value is Box<Int>;
                fn useString(): Boolean = isIntBox(Box.new("value"));
                """, "useString", false);
    }

    @Test
    public void exactArrayTestsUseTheElementDescriptor() throws Exception {
        assertCompiledResult("reifiedArray", """
                package %s;
                fn isIntRange<T: reified>(value: T): Boolean = value is Array<Int>;
                fn useRange(): Boolean {
                    let values: Array<Int> = [1, 2, 3];
                    return isIntRange(values);
                }
                """, "useRange", true);
    }

    @Test
    public void anonymousExistentialPositionsAreRejected() {
        final var prefix = """
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                """;
        final var sources = List.of(
                prefix + "fn bad(box: Box<Box<: Any>>): Unit = ();",
                prefix + "fn bad(): Unit { let value: Box<: Any> = Box.new(1); }",
                prefix + "fn bad(): Box<: Any> = Box.new(1);",
                prefix + "class Holder { public property value: Box<: Any>; }",
                prefix + "fn bad(this: Box<: Any>): Unit = ();",
                prefix + "fn bad(callback: (Box<: Any>) -> Unit): Unit = ();",
                prefix + "fn bad(values: Array<: Any>): Unit = ();",
                prefix + "fn bad(): Unit { let callback: (Box<: Any>) -> Unit = (box) -> (); }",
                prefix + "fn bad(box: Box<: Any>) = box;");
        for (int index = 0; index < sources.size(); index++) {
            assertInvalidSource(sources.get(index), "position " + index);
        }
    }

    private static void assertCompiledResult(final String testName,
                                             final String source,
                                             final String method,
                                             final Object expected) throws Exception {
        final var packageName = "zep0104" + testName + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-zep0104-");
        final var unit = parse("Main.zn", source.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, ExistentialGenericsTest.class.getClassLoader())) {
                assertEquals(expected, loader.loadClass(packageName + ".Main")
                        .getMethod(method).invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static void assertRejected(final String source) {
        final var unit = parse("Main.zn", "package zep0104Rejected%s;\n".formatted(suffix())
                + source);
        final var packageName = unit.packageName();
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(unit)),
                packageName + ".Main", packageName, List.of(), false, List.of(),
                Path.of("target", "zeron-zep0104-rejected-" + suffix()));
        assertThrows(ResolutionError.class, compiler::resolve);
    }

    private static void assertInvalidSource(final String source, final String description) {
        final var packageName = "zep0104Invalid" + suffix();
        final var result = Parser.of(Scanner.from("package " + packageName + ";\n" + source,
                "Main.zn").scanTokens()).parseCompilationUnitWithDiagnostics("Main.zn");
        if (!result.diagnostics().isEmpty()) return;
        try {
            CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(result.compilationUnit())),
                    packageName + ".Main", packageName, List.of(), false, List.of(),
                    Path.of("target", "zeron-zep0104-invalid-" + suffix())).resolve();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(description + ": expected the source to be rejected");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
