package com.maruseron.zeron.analize;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ExtensionMethodTest {
    @Test
    public void resolvesImportedExtensionsWithInstancePrecedenceAndMutability() throws Exception {
        final var output = Files.createTempDirectory("zeron-extension-source");
        try {
            final var model = unit("""
                    package model;
                    public class Box {
                        public mut property value: Int;
                        private hidden(): Int = 100;
                        public selected(value: Int): String = "instance";
                        public priority(): String = "instance";
                        public constructor new;
                    }
                    """, "Box.zn");
            final var extensions = unit("""
                    package extras;
                    import model.Box;
                    public extension Box {
                        public fn doubleValue(): Int = this.value * 2;
                        public mut fn bump(amount: Int = 1): Unit {
                            this.value += amount;
                        }
                        public fn selected(): String = "extension";
                        public fn priority(): String = "extension";
                    }
                    """, "BoxExtensions.zn");
            final var app = unit("""
                    package app;
                    import model.Box;
                    import extras.Box.bump;
                    import extras.Box.doubleValue;
                    import extras.Box.priority;
                    import extras.Box.selected;
                    fn value(): Int {
                        let box = Box.new(3);
                        box.bump();
                        return box.doubleValue();
                    }
                    fn fallback(): String = Box.new(1).selected();
                    fn instanceWins(): String = Box.new(1).priority();
                    """, "App.zn");
            final var compiler = CompilationService.forCompilationUnits(List.of(app, extensions, model),
                    "app.ExtensionApp", "app", List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass("app.ExtensionApp");
                assertEquals(8, generated.getMethod("value").invoke(null));
                assertEquals("extension", generated.getMethod("fallback").invoke(null));
                assertEquals("instance", generated.getMethod("instanceWins").invoke(null));
            }

            final var readonlyApp = unit("""
                    package app;
                    import model.Box;
                    import extras.Box.bump;
                    fn invalid(box: Box): Unit {
                        box.bump();
                    }
                    """, "ReadonlyApp.zn");
            final var readonlyResult = new ResolutionService().resolveUnitsWithDiagnostics(
                    List.of(readonlyApp, extensions, model));
            assertTrue(readonlyResult.errors().toString(), readonlyResult.errors().stream()
                    .anyMatch(error -> error.message().contains("mutable receiver")));

            final var privateExtension = unit("""
                    package model;
                    public extension Box {
                        public fn forbidden(): Int = this.hidden();
                    }
                    """, "PrivateExtension.zn");
            final var privateResult = new ResolutionService().resolveUnitsWithDiagnostics(
                    List.of(privateExtension, model));
            assertTrue(privateResult.errors().toString(), !privateResult.errors().isEmpty());
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void infersGenericExtensionResultFromLambda() {
        final var model = unit("""
                package model;
                public class Box {
                    public property value: Int;
                    public constructor new;
                }
                """, "Box.zn");
        final var extensions = unit("""
                package extras;
                import model.Box;
                public extension Box {
                    public fn apply<U>(transform: (Int) -> U): U = transform(this.value);
                }
                """, "BoxExtensions.zn");
        final var app = unit("""
                package app;
                import model.Box;
                import extras.Box.apply;
                fn inferred(): Int = Box.new(3).apply(value -> value + 1);
                """, "App.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(app, extensions, model));

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void importsAndInvokesExtensionsFromCompiledLibraries() throws Exception {
        final var root = Files.createTempDirectory("zeron-extension-library");
        final var libraryOutput = root.resolve("library");
        final var appOutput = root.resolve("consumer");
        try {
            final var model = unit("""
                    package model;
                    public class Box {
                        public mut property value: Int;
                        public constructor new;
                    }
                    public class Container<T> {
                        public property value: T;
                        public constructor new;
                    }
                    """, "Box.zn");
            final var extensions = unit("""
                    package extras;
                    import model.Box;
                    import model.Container;
                    public extension Box {
                        public mut fn increment(): Unit {
                            this.value += 1;
                        }
                    }
                    public extension<T> Container<T> {
                        public fn readValue(): T = this.value;
                    }
                    """, "BoxExtensions.zn");
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(extensions, model), "library.Provider", "extras",
                    List.of(), false, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            assertTrue(index.declarations().stream().anyMatch(ZeronLibraryIndex.ExtensionExport.class::isInstance));

            final var app = unit("""
                    package app;
                    import model.Box;
                    import model.Container;
                    import extras.Box.increment;
                    import extras.Container.readValue;
                    fn result(): Int {
                        let box = Box.new(4);
                        box.increment();
                        return box.value;
                    }
                    fn genericResult(): Int = Container<Int>.new(9).readValue();
                    """, "Consumer.zn");
            final var consumerCompiler = CompilationService.forCompilationUnits(
                    List.of(app), "app.ExtensionConsumer", "app",
                    List.of(index), false, List.of(), appOutput);
            consumerCompiler.resolve();
            consumerCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    appOutput.toUri().toURL(), libraryOutput.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(5, loader.loadClass("app.ExtensionConsumer")
                        .getMethod("result").invoke(null));
                assertEquals(9, loader.loadClass("app.ExtensionConsumer")
                        .getMethod("genericResult").invoke(null));
            }
        } finally {
            deleteTree(root);
        }
    }

    private static CompilationUnit unit(final String source, final String name) {
        return Parser.of(Scanner.from(source, name).scanTokens()).parseCompilationUnit(name);
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
