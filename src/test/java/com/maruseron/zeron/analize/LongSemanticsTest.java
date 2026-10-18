package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.LongDescriptor;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LongSemanticsTest {
    @Test
    public void compilesLongLiteralsArithmeticComparisonsAndCheckedConversions() throws Exception {
        final var className = "LongSemantics" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-long-");
        final var unit = parse("LongSemantics.zn", """
                import zeron.lang.*;

                fn minimum(): Long = -9223372036854775808L;
                fn maximum(): Long = 9223372036854775807L;
                fn add(left: Long, right: Long): Long = left + right;
                fn shift(value: Long, count: Int): Long = value << count;
                fn bitwise(left: Long, right: Long): Long = (left & right) | (left ^ right);
                fn complement(value: Long): Long = ~value;
                fn equal(left: Long, right: Long): Boolean = left == right;
                fn less(left: Long, right: Long): Boolean = left < right;
                fn matchLong(value: Long): Int = match (value) {
                    case -9223372036854775808L -> 1;
                    case 42L -> 2;
                    case _ -> 0;
                };
                fn widened(): Long = 42.toLong();
                fn narrowed(value: Long): Int = value.toInt().getOrElse(-1);
                fn identity<T>(value: T): T = value;
                fn generic(): Long = identity(42L);
                fn arrayValue(): Long = [42L][0];
                fn applyLong(transform: (Long) -> Long, value: Long): Long = transform(value);
                fn lambdaLong(): Long = applyLong(value -> value + 1L, 41L);
                fn boxed(value: Long): Any = value;
                fn isLong(value: Any): Boolean = value is Long;
                fn unbox(value: Any): Long = value as Long;
                fn display<T: Display<T>>(value: T): String = value.toString();
                fn equalEvidence<T: Eq<T>>(left: T, right: T): Boolean = left.eq(right);
                fn hashEvidence<T: Hashable<T>>(value: T): Int = value.hash();
                fn displayLong(value: Long): String = display(value);
                fn equalLong(left: Long, right: Long): Boolean = equalEvidence(left, right);
                fn hashLong(value: Long): Int = hashEvidence(value);
                """);

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    com.maruseron.zeron.StandardLibrary.withBundledUnits(List.of(unit)),
                    className, "", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new java.net.URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(Long.MIN_VALUE, generated.getMethod("minimum").invoke(null));
                assertEquals(Long.MAX_VALUE, generated.getMethod("maximum").invoke(null));
                assertEquals(9L, generated.getMethod("add", long.class, long.class).invoke(null, 4L, 5L));
                assertEquals(20L, generated.getMethod("shift", long.class, int.class).invoke(null, 5L, 2));
                assertEquals(7L, generated.getMethod("bitwise", long.class, long.class)
                        .invoke(null, 3L, 7L));
                assertEquals(~42L, generated.getMethod("complement", long.class).invoke(null, 42L));
                assertTrue((Boolean) generated.getMethod("equal", long.class, long.class)
                        .invoke(null, 8L, 8L));
                assertFalse((Boolean) generated.getMethod("less", long.class, long.class)
                        .invoke(null, 8L, 2L));
                assertEquals(1, generated.getMethod("matchLong", long.class).invoke(null, Long.MIN_VALUE));
                assertEquals(2, generated.getMethod("matchLong", long.class).invoke(null, 42L));
                assertEquals(42L, generated.getMethod("widened").invoke(null));
                final var narrowed = generated.getMethod("narrowed", long.class);
                assertEquals(23, narrowed.invoke(null, 23L));
                assertEquals(-1, narrowed.invoke(null, (long) Integer.MAX_VALUE + 1));
                assertEquals(-1, narrowed.invoke(null, Long.MIN_VALUE));
                assertEquals(42L, generated.getMethod("generic").invoke(null));
                assertEquals(42L, generated.getMethod("arrayValue").invoke(null));
                assertEquals(42L, generated.getMethod("lambdaLong").invoke(null));
                final var boxed = generated.getMethod("boxed", long.class).invoke(null, 42L);
                assertTrue((Boolean) generated.getMethod("isLong", Object.class).invoke(null, boxed));
                assertEquals(42L, generated.getMethod("unbox", Object.class).invoke(null, boxed));
                assertEquals("42", generated.getMethod("displayLong", long.class).invoke(null, 42L));
                assertTrue((Boolean) generated.getMethod("equalLong", long.class, long.class)
                        .invoke(null, 42L, 42L));
                assertEquals(Long.hashCode(42L),
                        generated.getMethod("hashLong", long.class).invoke(null, 42L));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void forwardsJavaLauncherArgumentsToZeronMain() throws Exception {
        final var className = "ArgumentMain" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-main-args-");
        final var unit = parse("ArgumentMain.zn", """
                import zeron.io.println;
                fn main(args: Array<String>): Unit {
                    println(args[0]);
                }
                """);

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    com.maruseron.zeron.StandardLibrary.withBundledUnits(List.of(unit)),
                    className, "", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            final var javaCommand = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
            final var process = new ProcessBuilder(javaCommand.toString(), "-cp",
                    output.toAbsolutePath().toString(), className, "first", "second")
                    .redirectErrorStream(true)
                    .start();
            final var outputText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor());
            assertEquals("first" + System.lineSeparator(), outputText);
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void preservesLongTypesAcrossLibraryIndexes() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "longlibrary" + suffix;
        final var clientPackage = "longclient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-long-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-long-client-");
        final var library = parse("Library.zn", """
                package %s;
                public fn echo(value: Long): Long = value;
                """.formatted(libraryPackage));
        final var client = parse("Main.zn", """
                package %s;
                import %s.echo;
                fn result(): Long = echo(42L);
                """.formatted(clientPackage, libraryPackage));

        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(library), libraryPackage + ".Library", libraryPackage,
                    List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var libraryIndex = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var exported = libraryIndex.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(function -> function.qualifiedName().equals(libraryPackage + ".echo"))
                    .findFirst().orElseThrow();
            assertTrue(exported.signature().parameters().getFirst() instanceof LongDescriptor);
            assertTrue(exported.signature().returnType() instanceof LongDescriptor);

            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), clientPackage + ".Main", clientPackage,
                    List.of(libraryIndex), true, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new java.net.URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals(42L, loader.loadClass(clientPackage + ".Main")
                        .getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
        }
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens())
                .parseCompilationUnit(sourceName);
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
