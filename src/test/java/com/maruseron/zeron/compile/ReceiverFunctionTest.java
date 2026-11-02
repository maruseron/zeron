package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.FunctionDescriptor;
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
import static org.junit.Assert.assertThrows;

public final class ReceiverFunctionTest {
    @Test
    public void invokesReceiverFunctionsFromParametersAndProperties() throws Exception {
        final var packageName = "receiverfixture" + UUID.randomUUID().toString().replace("-", "");
        final var unit = parse("Main.zn", """
                package %s;
                public contract Runner {
                    value(): Int;
                    property callback: Runner.() -> Int;
                }
                fn apply(runner: Runner, body: Runner.() -> Int): Int = runner.body();
                fn applyWithArgument(runner: Runner, body: Runner.(Int) -> Int): Int =
                    runner.body(5);
                fn fixedCallback(): Runner.() -> Int = () -> 42;
                public fn result(): Int {
                    let runner: Runner = object : Runner {
                        public value(): Int = 40;
                        public property callback: Runner.() -> Int = fixedCallback();
                    };
                    return apply(runner) { this.value() } + runner.callback()
                            + applyWithArgument(runner, value -> value);
                }
                """.formatted(packageName));
        deleteTree(Path.of("dist"));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), packageName + ".Main", packageName);
            compiler.resolve();
            compiler.compile();

            try (var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(87, main.getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void resolvesNearestMatchingEnclosingReceiverAndCapturesIt() throws Exception {
        final var packageName = "nestedreceiver" + UUID.randomUUID().toString().replace("-", "");
        final var unit = parse("Nested.zn", """
                package %s;
                public contract Inner { value(): Int; }
                public contract Outer {
                    value(): Int;
                    inner(): Inner;
                }
                fn invokeOuter(outer: Outer, body: Outer.() -> Int): Int = outer.body();
                fn invokeInner(inner: Inner, body: Inner.() -> Int): Int = inner.body();
                public fn result(): Int {
                    let outer: Outer = object : Outer {
                        public value(): Int = 20;
                        public inner(): Inner = object : Inner { public value(): Int = 3; };
                    };
                    return invokeOuter(outer, () ->
                        invokeInner(inner(), () -> Outer.this.value() + value()));
                }
                """.formatted(packageName));
        deleteTree(Path.of("dist"));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), packageName + ".Nested", packageName);
            compiler.resolve();
            compiler.compile();

            try (var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Nested");
                assertEquals(23, main.getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void doesNotConvertReceiverFunctionsToOrdinaryFunctions() {
        final var source = parse("Invalid.zn", """
                contract Scope { value(): Int; }
                fn invalid(callback: Scope.() -> Int): Int {
                    let ordinary: (Scope) -> Int = callback;
                    return 0;
                }
                """);

        final var error = assertThrows(ResolutionError.class,
                () -> CompilationService.forCompilationUnits(List.of(source), "Invalid", "").resolve());
        assertTrue(error.getMessage().contains("Expected (Scope) -> Int"));
    }

    @Test
    public void doesNotEraseMutableReceiverCapabilities() {
        final var source = parse("Mutable.zn", """
                contract Counter { mut increment(): Unit; }
                fn invalid(callback: &Counter.() -> Unit) {
                    let readOnly: Counter.() -> Unit = callback;
                }
                """);

        final var error = assertThrows(ResolutionError.class,
                () -> CompilationService.forCompilationUnits(List.of(source), "Mutable", "").resolve());
        assertTrue(error.getMessage().contains("Counter.() -> Unit"));
        assertTrue(error.getMessage().contains("&Counter.() -> Unit"));
    }

    @Test
    public void preservesReceiverFunctionTypesAcrossCompiledLibraries() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "receiverlib" + suffix;
        final var clientPackage = "receiverclient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-receiver-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-receiver-client-");
        final var libraryUnit = parse("Library.zn", """
                package %s;
                public contract Runner { value(): Int; }
                public fn apply(runner: Runner, body: Runner.() -> Int): Int = runner.body();
                public fn fixed(): Runner.() -> Int = () -> value();
                """.formatted(libraryPackage));
        final var clientUnit = parse("Client.zn", """
                package %s;
                import %s.Runner;
                import %s.apply;
                import %s.fixed;
                public fn result(): Int {
                    let runner: Runner = object : Runner { public value(): Int = 51; };
                    return apply(runner, fixed());
                }
                """.formatted(clientPackage, libraryPackage, libraryPackage, libraryPackage));
        deleteTree(Path.of("dist"));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(List.of(libraryUnit),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var apply = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(function -> function.qualifiedName().equals(libraryPackage + ".apply"))
                    .findFirst().orElseThrow();
            final var callback = (FunctionDescriptor) apply.signature().parameters().get(1);
            assertTrue(callback.isReceiverFunction());

            final var clientCompiler = CompilationService.forCompilationUnits(List.of(clientUnit),
                    clientPackage + ".Client", clientPackage, List.of(index), true, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();
            try (var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(clientPackage + ".Client");
                assertEquals(51, main.getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
            deleteTree(Path.of("dist"));
        }
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
