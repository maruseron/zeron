package com.maruseron.zeron;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class NamespaceTest {
    @Test
    public void compilesReopenedNamespacesAndQualifiedImmutableValues() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "namespaceLibrary" + suffix;
        final var clientPackage = "namespaceClient" + suffix;
        final var mainClass = clientPackage + ".Main";
        final var main = parse("Main.zn", """
                package %s;
                import %s.Array.plusOne as importedPlusOne;
                fn result(): Int {
                    return importedPlusOne(3) + %s.Array.factor;
                }
                """.formatted(clientPackage, libraryPackage, libraryPackage));
        final var firstFragment = parse("ArrayFirst.zn", """
                package %s;
                namespace Array {
                    public let factor: Int = computeFactor();
                    fn computeFactor(): Int = 4;
                    public fn scale(value: Int): Int = value * factor;
                }
                namespace Math {
                    public fn scale(value: Int): Int = value + 1;
                }
                """.formatted(libraryPackage));
        final var secondFragment = parse("ArraySecond.zn", """
                package %s;
                namespace Array {
                    public fn plusOne(value: Int): Int = scale(value) + 1;
                }
                """.formatted(libraryPackage));
        final var compiler = CompilationService.forCompilationUnits(
                List.of(main, firstFragment, secondFragment), mainClass, clientPackage);

        try {
            if (Files.exists(Path.of("dist"))) {
                deleteTree(Path.of("dist"));
            }
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(17, loader.loadClass(mainClass).getMethod("result").invoke(null));
            }
        } finally {
            if (Files.exists(Path.of("dist"))) deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void exportsNamespaceFunctionsAndValuesFromCompiledLibraries() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "namespaceApi" + suffix;
        final var clientPackage = "namespaceConsumer" + suffix;
        final var library = parse("Api.zn", """
                package %s;
                namespace Settings {
                    public let answer: Int = compute();
                    public fn compute(): Int = 42;
                }
                """.formatted(libraryPackage));
        final var clientMain = clientPackage + ".Main";
        final var client = parse("Main.zn", """
                package %s;
                import %s.Settings.answer;
                import %s.Settings.compute;
                fn result(): Int = answer + compute();
                """.formatted(clientPackage, libraryPackage, libraryPackage));

        try {
            if (Files.exists(Path.of("dist"))) deleteTree(Path.of("dist"));
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(library), libraryPackage + ".LibraryEntry", libraryPackage);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFrom(
                    Path.of("dist", "META-INF", "zeron", "api-v14.bin"));
            final var computeExport = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(function -> function.qualifiedName().equals(libraryPackage + ".Settings.compute"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Settings", computeExport.namespaceName());
            final var answerExport = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ValueExport.class::isInstance)
                    .map(ZeronLibraryIndex.ValueExport.class::cast)
                    .filter(value -> value.qualifiedName().equals(libraryPackage + ".Settings.answer"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Settings", answerExport.namespaceName());
            final var originalOutput = System.out;
            final var dumpOutput = new ByteArrayOutputStream();
            try {
                try (var redirectedOutput = new PrintStream(dumpOutput)) {
                    System.setOut(redirectedOutput);
                    ZeronLibraryIndexDump.main(
                            Path.of("dist", "META-INF", "zeron", "api-v14.bin").toString());
                }
            } finally {
                System.setOut(originalOutput);
            }
            assertTrue(dumpOutput.toString().contains("Namespace: Settings"));

            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), clientMain, clientPackage, List.of(index));
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(84, loader.loadClass(clientMain).getMethod("result").invoke(null));
            }
        } finally {
            if (Files.exists(Path.of("dist"))) deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void namespaceValuesAreImmutable() {
        final var packageName = "namespaceImmutable" + UUID.randomUUID().toString().replace("-", "");
        final var source = parse("Immutable.zn", """
                package %s;
                namespace Config {
                    let value: Int = 1;
                }
                fn update(): Unit {
                    Config.value = 2;
                }
                """.formatted(packageName));

        assertResolutionFails(List.of(source), packageName + ".Main", packageName);
    }

    @Test
    public void privateNamespaceValuesCannotBeAccessedFromAnotherPackage() {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "namespacePrivate" + suffix;
        final var clientPackage = "namespacePrivateClient" + suffix;
        final var client = parse("Main.zn", """
                package %s;
                fn read(): Int = %s.Config.value;
                """.formatted(clientPackage, libraryPackage));
        final var library = parse("Config.zn", """
                package %s;
                namespace Config {
                    let value: Int = 1;
                }
                """.formatted(libraryPackage));

        assertResolutionFails(List.of(client, library), clientPackage + ".Main", clientPackage);
    }

    @Test
    public void safeNavigationCannotBeAppliedToNamespaceValues() {
        final var packageName = "namespaceSafeNavigation"
                + UUID.randomUUID().toString().replace("-", "");
        final var source = parse("SafeNavigation.zn", """
                package %s;
                namespace Config {
                    let value: Int = 1;
                }
                fn read(): Int? = Config?.value;
                """.formatted(packageName));

        assertResolutionFails(List.of(source), packageName + ".Main", packageName);
    }

    private static void assertResolutionFails(
            final List<CompilationUnit> units, final String mainClass, final String packageName) {
        final var compiler = CompilationService.forCompilationUnits(units, mainClass, packageName);
        assertThrows(ResolutionError.class, compiler::resolve);
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens()).parseCompilationUnit(sourceName);
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
