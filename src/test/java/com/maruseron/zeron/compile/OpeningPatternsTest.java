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

public final class OpeningPatternsTest {
    @Test
    public void opensAStarViewAndPassesTheBinderToAGenericCall() throws Exception {
        assertCompiledResult("star", """
                package %s;
                public class Box<T: nullable> {
                    public property value: T;
                    public constructor new;
                }
                public fn size<T: nullable>(box: Box<T>): Int = 1;
                public fn opened(box: Box<*>): Int {
                    if (box is Box<let T>) {
                        return size(box);
                    }
                    return 0;
                }
                fn use(): Int {
                    let box: Box<Int> = Box.new(7);
                    return opened(box);
                }
                """, "use", 1);
    }

    @Test
    public void exposesTheBinderInBranchLocalTypeAnnotations() throws Exception {
        assertCompiledResult("annotation", """
                package %s;
                public class Box<T: nullable> {
                    public property value: T;
                    public constructor new;
                }
                public fn read(box: Box<*>): String {
                    if (box is Box<let T>) {
                        let value: T = box.value;
                        return "box";
                    }
                    return "other";
                }
                fn use(): String {
                    let box: Box<String> = Box.new("value");
                    return read(box);
                }
                """, "use", "box");
    }

    @Test
    public void inheritsACompatibleBoundFromAnAnonymousExistential() throws Exception {
        assertCompiledResult("bounded", """
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
                public fn describe(box: Box<: Render>): String {
                    if (box is Box<let T>) {
                        let value: T = box.value;
                        return value.render();
                    }
                    return "none";
                }
                fn use(): String = describe(Box.new(Text.new()));
                """, "use", "text");
    }

    @Test
    public void inheritsTheDescriptorFromAReifiedAnonymousExistential() throws Exception {
        assertCompiledResult("reified", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public fn identify<T: reified>(value: T): T = value;
                public fn inspect(box: Box<: reified>): Int {
                    if (box is Box<let T>) {
                        let value: T = box.value;
                        identify(value);
                        return 1;
                    }
                    return 0;
                }
                fn use(): Int = inspect(Box.new(7));
                """, "use", 1);
    }

    @Test
    public void opensMultipleArgumentsInOnePattern() throws Exception {
        assertCompiledResult("multiple", """
                package %s;
                public class Pair<K: nullable, V: nullable> {
                    public property first: K;
                    public property second: V;
                    public constructor new;
                }
                public fn sizeOf<K: nullable, V: nullable>(pair: Pair<K, V>): Int = 1;
                public fn read(pair: Pair<*, *>): Int {
                    if (pair is Pair<let K, let V>) {
                        let first: K = pair.first;
                        let second: V = pair.second;
                        return sizeOf(pair);
                    }
                    return 0;
                }
                fn use(): Int {
                    let pair: Pair<Int, String> = Pair.new(1, "value");
                    return read(pair);
                }
                """, "use", 1);
    }

    @Test
    public void opensARawAnyTestWithoutInventingEvidence() throws Exception {
        assertCompiledResult("raw", """
                package %s;
                public class Box<T: nullable> {
                    public property value: T;
                    public constructor new;
                }
                public fn classify(value: Any): String {
                    if (value is Box<let T>) {
                        return "box";
                    }
                    return "other";
                }
                fn useBox(): String = classify(Box.new(7));
                fn useOther(): String = classify("value");
                """, "useBox", "box");
    }

    @Test
    public void bindsAKnownConcreteArgumentForTheBranch() throws Exception {
        assertCompiledResult("known", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public fn readKnown(box: Box<Int>): Int {
                    if (box is Box<let T>) {
                        let value: T = box.value;
                        return value;
                    }
                    return 0;
                }
                fn use(): Int = readKnown(Box.new(9));
                """, "use", 9);
    }

    @Test
    public void scopesTheBinderToAWhileBody() throws Exception {
        assertCompiledResult("while", """
                package %s;
                public class Box<T: nullable> {
                    public property value: T;
                    public constructor new;
                }
                public fn first(box: Box<*>): Int {
                    while (box is Box<let T>) {
                        let value: T = box.value;
                        return 1;
                    }
                    return 0;
                }
                fn use(): Int {
                    let box: Box<Int> = Box.new(5);
                    return first(box);
                }
                """, "use", 1);
    }

    @Test
    public void rejectsAnEscapingPatternTypeThroughAReturn() {
        assertRejected("""
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public fn leak(box: Box<*>): Box<*> {
                    if (box is Box<let T>) {
                        return box;
                    }
                    return box;
                }
                """);
    }

    @Test
    public void rejectsAnEscapingPatternTypeThroughAnOuterAssignment() {
        assertRejected("""
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public fn leak(box: Box<*>): Unit {
                    var sink: Box<*> = box;
                    if (box is Box<let T>) {
                        sink = box;
                    }
                }
                """);
    }

    @Test
    public void rejectsComposedOpeningConditions() {
        assertRejected("""
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public fn bad(box: Box<*>): Int {
                    if (box is Box<let T> && true) {
                        return 1;
                    }
                    return 0;
                }
                """);
    }

    @Test
    public void rejectsMixedAndNestedOpeningArguments() {
        assertRejected("""
                public class Pair<K, V> {
                    public property first: K;
                    public property second: V;
                    public constructor new;
                }
                public fn bad(pair: Pair<*, *>): Int {
                    if (pair is Pair<Int, let V>) {
                        return 1;
                    }
                    return 0;
                }
                """);
        assertRejected("""
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public fn bad(box: Box<Box<*>>): Int {
                    if (box is Box<Box<let T>>) {
                        return 1;
                    }
                    return 0;
                }
                """);
    }

    @Test
    public void rejectsDuplicateBindersAndBindersOutsideTheBranch() {
        assertRejected("""
                public class Pair<K, V> {
                    public property first: K;
                    public property second: V;
                    public constructor new;
                }
                public fn bad(pair: Pair<*, *>): Int {
                    if (pair is Pair<let T, let T>) {
                        return 1;
                    }
                    return 0;
                }
                """);
        assertRejected("""
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public fn bad(box: Box<*>): Unit {
                    if (box is Box<let T>) {
                    }
                    let leaked: T = box.value;
                }
                """);
    }

    @Test
    public void rejectsOpeningAnInProjection() {
        assertRejected("""
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public fn bad(box: Box<in Int>): Int {
                    if (box is Box<let T>) {
                        return 1;
                    }
                    return 0;
                }
                """);
    }

    private static void assertCompiledResult(final String testName,
                                             final String source,
                                             final String method,
                                             final Object expected) throws Exception {
        final var packageName = "zep0105" + testName + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-zep0105-");
        final var unit = parse("Main.zn", source.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, OpeningPatternsTest.class.getClassLoader())) {
                assertEquals(expected, loader.loadClass(packageName + ".Main")
                        .getMethod(method).invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static void assertRejected(final String source) {
        final var packageName = "zep0105Rejected" + suffix();
        try {
            final var unit = parse("Main.zn",
                    "package " + packageName + ";\n" + source);
            CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(),
                    Path.of("target", "zeron-zep0105-rejected-" + suffix())).resolve();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected the opening pattern source to be rejected");
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
