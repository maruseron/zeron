package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ResolutionService;
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
import static org.junit.Assert.assertTrue;

public final class StaticFunctionsTest {
    @Test
    public void staticFunctionsAreCallableThroughTheClassAndCoexistWithTheNamespace() throws Exception {
        final var packageName = "staticFunctions" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-static-functions-");
        final var unit = parse("Main.zn", """
                package %s;
                public class Box<T: mut> {
                    public property value: T;
                    public constructor new;
                    public static fn of<U: mut>(value: U): Box<U> = Box<U>.new(value);
                    public static fn twice(value: Int): Int = value * 2;
                }
                namespace Box {
                    public fn triple(value: Int): Int = value * 3;
                }
                fn boxed(): String = Box.of("x").value;
                fn arithmetic(): Int = Box.twice(4) + Box.triple(2);
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals("x", main.getMethod("boxed").invoke(null));
                assertEquals(14, main.getMethod("arithmetic").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void compiledLibraryStaticFunctionsRemainCallableFromClients() throws Exception {
        final var suffix = suffix();
        final var libraryPackage = "staticLibrary" + suffix;
        final var appPackage = "staticClient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-static-library-");
        final var appOutput = Files.createTempDirectory(Path.of("target"), "zeron-static-client-");
        final var library = parse("Library.zn", """
                package %s;
                public class Tool {
                    public property label: String;
                    public constructor new;
                    public static fn named(label: String): Tool = Tool.new(label);
                }
                """.formatted(libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(List.of(library),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();

            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var app = parse("Main.zn", """
                    package %s;
                    import %s.Tool;
                    fn label(): String = Tool.named("tool").label;
                    """.formatted(appPackage, libraryPackage));
            final var appCompiler = CompilationService.forCompilationUnits(List.of(app),
                    appPackage + ".Main", appPackage, List.of(index), true, List.of(), appOutput);
            appCompiler.resolve();
            appCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    appOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals("tool", loader.loadClass(appPackage + ".Main")
                        .getMethod("label").invoke(null));
            }
        } finally {
            deleteTree(libraryOutput);
            deleteTree(appOutput);
        }
    }

    @Test
    public void instanceMembersClassParametersAndInstanceStateAreRejected() {
        assertDiagnostic("""
                class Collision {
                    public constructor new;
                    public run(): Int = 1;
                    public static fn run(): Int = 2;
                }
                """, "conflicts with another class member");
        assertDiagnostic("""
                class Widget {
                    public constructor new;
                    public static fn size(): Int = 1;
                }
                extension Widget {
                    fn size(): Int = 2;
                }
                """, "Function and extension names cannot collide");
        assertDiagnostic("""
                class Receiver {
                    public constructor new;
                    value: Int;
                    public static fn bad(): Int = this.value;
                }
                """, "Unknown symbol: 'this'");
        assertDiagnostic("""
                class Generic<T: mut> {
                    public constructor new;
                    value: T;
                    public static fn bad(value: T): T = value;
                }
                """, "Unknown type");
    }

    private static void assertDiagnostic(final String source, final String expectedMessage) {
        final var unit = parse("Diagnostic.zn", source);
        final var errors = new ResolutionService().resolveUnitsWithDiagnostics(List.of(unit)).errors();
        assertTrue("Expected a diagnostic containing: " + expectedMessage + " but got " + errors,
                errors.stream().anyMatch(error -> error.message().contains(expectedMessage)));
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
