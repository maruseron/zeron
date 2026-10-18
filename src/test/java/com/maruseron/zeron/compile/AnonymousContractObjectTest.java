package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class AnonymousContractObjectTest {
    @Test
    public void compilesStatefulObjectWithPropertyAndImmutableCapture() throws Exception {
        final var unit = parse("""
                package anonymousfixture;
                public contract Counter {
                    mut property value: Int;
                    mut next(): Int;
                    read(): Int;
                }
                public contract GenericBox<T> {
                    get(): T;
                }
                public contract Marker {
                    mark(): Int;
                }
                public contract Label {
                    property label: String;
                }
                public contract FunctionProvider {
                    property apply: (Int) -> Int;
                }
                public fn applyBounded<T: FunctionProvider>(provider: T): Int = provider.apply(41);
                public fn result(): Int {
                    let base: Int = 7;
                    let counter = object : Counter + Marker {
                        count: Int = 3;
                        public mut property value: Int = 5;
                        public mut next(): Int {
                            this.count = this.count + 1;
                            return this.count + base;
                        }
                        public read(): Int = this.value;
                        public mark(): Int = 1;
                    };
                    let first = counter.next();
                    counter.value = 9;
                    let view: Counter = counter;
                    return first + counter.value + counter.next() + view.read();
                }
                public fn genericResult(): Int {
                    let box: GenericBox<Int> = object : GenericBox<Int> {
                        public get(): Int = 42;
                    };
                    return box.get();
                }
                public fn customPropertyResult(): String {
                    let prefix: String = "anonymous";
                    let labeled: Label = object : Label {
                        suffix: String = " object";
                        public property label: String {
                            get = this.suffix + prefix;
                        }
                    };
                    return labeled.label;
                }
                public fn functionPropertyResult(): Int {
                    let provider: FunctionProvider = object : FunctionProvider {
                        public property apply: (Int) -> Int = value -> value + 1;
                    };
                    return applyBounded(provider);
                }
                public fn safeFunctionPropertyResult(): Int {
                    let implementation: FunctionProvider = object : FunctionProvider {
                        public property apply: (Int) -> Int = value -> value + 1;
                    };
                    let provider: FunctionProvider? = implementation;
                    return provider?.apply(41) ?? 0;
                }
                """);
        deleteTree(Path.of("dist"));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), "anonymousfixture.AnonymousContractObjectMain", "anonymousfixture");
            compiler.resolve();
            compiler.compile();

            try (var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass("anonymousfixture.AnonymousContractObjectMain");
                assertEquals(41, main.getMethod("result").invoke(null));
                assertEquals(42, main.getMethod("genericResult").invoke(null));
                assertEquals(" objectanonymous", main.getMethod("customPropertyResult").invoke(null));
                assertEquals(42, main.getMethod("functionPropertyResult").invoke(null));
                assertEquals(42, main.getMethod("safeFunctionPropertyResult").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void rejectsMutableCapturesAndConstructorBearingContracts() {
        final var mutableCapture = parse("""
                contract Readable { read(): Int; }
                fn invalid(): Int {
                    let mut count = 1;
                    let value = object : Readable { public read(): Int = count; };
                    return value.read();
                }
                """);
        final var captureError = assertThrows(ResolutionError.class,
                () -> CompilationService.forCompilationUnits(List.of(mutableCapture), "CaptureMain", "")
                        .resolve());
        assertTrue(captureError.getMessage().contains("Cannot capture mutable binding"));

        final var constructorRequirement = parse("""
                contract Factory { constructor create(); }
                fn invalid() = object : Factory {};
                """);
        final var constructorError = assertThrows(ResolutionError.class,
                () -> CompilationService.forCompilationUnits(List.of(constructorRequirement), "FactoryMain", "")
                        .resolve());
        assertTrue(constructorError.getMessage().contains("named contract constructors"));
    }

    private static CompilationUnit parse(final String source) {
        return Parser.of(Scanner.from(source, "AnonymousContractObject.zn").scanWithDiagnostics())
                .parseCompilationUnit("AnonymousContractObject.zn");
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
