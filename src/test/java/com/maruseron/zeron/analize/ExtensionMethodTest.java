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
    public void starImportsExtensionsAndExplicitImportsTakePrecedence() throws Exception {
        final var output = Files.createTempDirectory("zeron-extension-star-import");
        try {
            final var model = unit("""
                    package model;
                    public class Box {
                        public constructor new;
                    }
                    """, "Box.zn");
            final var starExtensions = unit("""
                    package star;
                    import model.Box;
                    public extension Box {
                        public fn origin(): String = "star";
                        public fn availableFromStar(): String = "available";
                    }
                    """, "StarExtensions.zn");
            final var explicitExtensions = unit("""
                    package explicit;
                    import model.Box;
                    public extension Box {
                        public fn origin(): String = "explicit";
                    }
                    """, "ExplicitExtensions.zn");
            final var app = unit("""
                    package app;
                    import model.Box;
                    import star.*;
                    import explicit.Box.origin;
                    fn result(): String {
                        let box = Box.new();
                        return box.origin() + ":" + box.availableFromStar();
                    }
                    """, "App.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app, starExtensions, explicitExtensions, model),
                    "app.ExtensionStarImport", "app", List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals("explicit:available", loader.loadClass("app.ExtensionStarImport")
                        .getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsAmbiguousStarImportedExtensionsAtCallSite() {
        final var model = unit("""
                package model;
                public class Box {
                    public constructor new;
                }
                """, "Box.zn");
        final var first = unit("""
                package first;
                import model.Box;
                public extension Box {
                    public fn label(): String = "first";
                }
                """, "FirstExtensions.zn");
        final var second = unit("""
                package second;
                import model.Box;
                public extension Box {
                    public fn label(): String = "second";
                }
                """, "SecondExtensions.zn");
        final var app = unit("""
                package app;
                import model.Box;
                import first.*;
                import second.*;
                fn result(): String = Box.new().label();
                """, "App.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(
                List.of(app, first, second, model));

        assertTrue(result.errors().toString(), result.errors().stream()
                .anyMatch(error -> error.message().contains("Ambiguous imported extension call")));
    }

    @Test
    public void projectsConcreteReceiversToGenericContractExtensions() throws Exception {
        final var output = Files.createTempDirectory("zeron-contract-extension-projection");
        try {
            final var model = unit("""
                    package model;
                    public contract Readable<T> {
                        read(): T;
                    }
                    public class Cell<T> is Readable<T> {
                        item: T;
                        public constructor new;
                        public read(): T = this.item;
                    }
                    """, "Model.zn");
            final var contractExtensions = unit("""
                    package extras;
                    import model.Readable;
                    public extension<T> Readable<T> {
                        public fn readExtended(): T = this.read();
                        public fn describe(): String = "contract";
                    }
                    """, "ReadableExtensions.zn");
            final var concreteExtensions = unit("""
                    package concrete;
                    import model.Cell;
                    public extension<T> Cell<T> {
                        public fn describe(): String = "concrete";
                    }
                    """, "CellExtensions.zn");
            final var app = unit("""
                    package app;
                    import model.Cell;
                    import model.Readable;
                    import extras.*;
                    import concrete.*;
                    fn concrete(): String = Cell<Int>.new(7).describe();
                    fn throughContract(value: Readable<Int>): Int = value.readExtended();
                    fn projected(value: Cell<Int>): Int = value.readExtended();
                    """, "App.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app, concreteExtensions, contractExtensions, model),
                    "app.ContractExtensionProjection", "app", List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass("app.ContractExtensionProjection");
                assertEquals("concrete", generated.getMethod("concrete").invoke(null));
                final var cell = loader.loadClass("model.Cell");
                final var instance = cell.getDeclaredConstructor(Object.class).newInstance(7);
                assertEquals(7, generated.getMethod("throughContract", loader.loadClass("model.Readable"))
                        .invoke(null, instance));
                assertEquals(7, generated.getMethod("projected", cell).invoke(null, instance));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void reportsAmbiguousExtensionsFromUnrelatedImplementedContracts() {
        final var model = unit("""
                package model;
                public contract First {
                }
                public contract Second {
                }
                public class Both is First, Second {
                    public constructor new;
                }
                """, "Model.zn");
        final var firstExtensions = unit("""
                package first;
                import model.First;
                public extension First {
                    public fn label(): String = "first";
                }
                """, "FirstExtensions.zn");
        final var secondExtensions = unit("""
                package second;
                import model.Second;
                public extension Second {
                    public fn label(): String = "second";
                }
                """, "SecondExtensions.zn");
        final var app = unit("""
                package app;
                import model.Both;
                import first.*;
                import second.*;
                fn result(): String = Both.new().label();
                """, "App.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(
                List.of(app, firstExtensions, secondExtensions, model));

        assertTrue(result.errors().toString(), result.errors().stream()
                .anyMatch(error -> error.message().contains("Ambiguous imported extension call")));
    }

    @Test
    public void packageStarImportsDoNotIncludeSubpackageExtensions() {
        final var model = unit("""
                package outer;
                public class Box {
                    public constructor new;
                }
                """, "Box.zn");
        final var extensions = unit("""
                package outer.extensions;
                import outer.Box;
                public extension Box {
                    public fn label(): String = "nested";
                }
                """, "Extensions.zn");
        final var app = unit("""
                package app;
                import outer.*;
                fn result(): String = Box.new().label();
                """, "App.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(
                List.of(app, extensions, model));

        assertTrue(result.errors().toString(), result.errors().stream()
                .anyMatch(error -> error.message().contains("Unknown method")));
    }

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
    public void compilesExtensionsForSupportedBuiltinReceivers() throws Exception {
        final var output = Files.createTempDirectory("zeron-builtin-extensions");
        try {
            final var extensions = unit("""
                    package extras;
                    public extension Int {
                        public property absoluteValue: Int {
                            get = if (this < 0) then -this else this;
                        }
                        public fn doubled(): Int = this * 2;
                    }
                    public extension Float {
                        public fn floatMarker(): Int = 1;
                    }
                    public extension Boolean {
                        public fn booleanMarker(): Int = if (this) then 3 else 0;
                    }
                    public extension String {
                        public fn stringMarker(): Int = if (this == "x") then 2 else 0;
                    }
                    public extension Unit {
                        public fn unitMarker(): Int = 4;
                    }
                    public extension<T> Array<T> {
                        public fn first(): T = this[0];
                    }
                    """, "BuiltinExtensions.zn");
            final var app = unit("""
                    package app;
                    import extras.Int.absoluteValue;
                    import extras.Int.doubled;
                    import extras.Float.floatMarker;
                    import extras.Boolean.booleanMarker;
                    import extras.String.stringMarker;
                    import extras.Unit.unitMarker;
                    import extras.Array.first;
                    fn result(): Int {
                        let value: Int = -5;
                        return ().unitMarker() + 1.5.floatMarker() + true.booleanMarker()
                                + "x".stringMarker() + value.absoluteValue + value.doubled()
                                + [7].first();
                    }
                    """, "App.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app, extensions), "app.BuiltinExtensions", "app",
                    List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals(12, loader.loadClass("app.BuiltinExtensions").getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void nullableReceiversRequireNarrowingOrSafeNavigationForExtensions() throws Exception {
        final var extensions = unit("""
                package extras;
                public extension Int {
                    public property sign: Int {
                        get = if (this < 0) then -1 else 1;
                    }
                    public fn doubled(): Int = this * 2;
                }
                """, "IntExtensions.zn");
        final var invalidMethod = unit("""
                package app;
                import extras.Int.doubled;
                fn invalid(value: Int?): Int = value.doubled();
                """, "InvalidMethod.zn");
        final var methodResult = new ResolutionService().resolveUnitsWithDiagnostics(
                List.of(invalidMethod, extensions));
        assertTrue(methodResult.errors().toString(), !methodResult.errors().isEmpty());

        final var invalidProperty = unit("""
                package app;
                import extras.Int.sign;
                fn invalid(value: Int?): Int = value.sign;
                """, "InvalidProperty.zn");
        final var propertyResult = new ResolutionService().resolveUnitsWithDiagnostics(
                List.of(invalidProperty, extensions));
        assertTrue(propertyResult.errors().toString(), !propertyResult.errors().isEmpty());

        final var output = Files.createTempDirectory("zeron-nullable-extensions");
        try {
            final var app = unit("""
                    package app;
                    import extras.Int.doubled;
                    import extras.Int.sign;
                    fn narrowed(value: Int?): Int {
                        if (value == null) return 0;
                        return value.doubled();
                    }
                    fn safeCall(value: Int?): Int? = value?.doubled();
                    fn safeProperty(value: Int?): Int? = value?.sign;
                    """, "App.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app, extensions), "app.NullableExtensions", "app",
                    List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass("app.NullableExtensions");
                assertEquals(0, generated.getMethod("narrowed", Integer.class).invoke(null, new Object[]{null}));
                assertEquals(6, generated.getMethod("narrowed", Integer.class).invoke(null, 3));
                assertEquals(null, generated.getMethod("safeCall", Integer.class).invoke(null, new Object[]{null}));
                assertEquals(-4, generated.getMethod("safeCall", Integer.class).invoke(null, -2));
                assertEquals(null, generated.getMethod("safeProperty", Integer.class).invoke(null, new Object[]{null}));
                assertEquals(-1, generated.getMethod("safeProperty", Integer.class).invoke(null, -2));
            }
        } finally {
            deleteTree(output);
        }
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
                    public extension Int {
                        public property absoluteValue: Int {
                            get = if (this < 0) then -this else this;
                        }
                    }
                    """, "BoxExtensions.zn");
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(extensions, model), "library.Provider", "extras",
                    List.of(), false, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            assertTrue(index.declarations().stream().anyMatch(ZeronLibraryIndex.ExtensionExport.class::isInstance));
            assertTrue(index.declarations().stream()
                    .filter(ZeronLibraryIndex.ExtensionExport.class::isInstance)
                    .map(ZeronLibraryIndex.ExtensionExport.class::cast)
                    .anyMatch(extension -> extension.property()
                            && extension.qualifiedName().equals("extras.Int.absoluteValue")));

            final var app = unit("""
                    package app;
                    import model.Box;
                    import model.Container;
                    import extras.Box.increment;
                    import extras.Container.readValue;
                    import extras.Int.absoluteValue;
                    fn result(): Int {
                        let box = Box.new(4);
                        box.increment();
                        return box.value;
                    }
                    fn genericResult(): Int = Container<Int>.new(9).readValue();
                    fn absoluteResult(): Int = (-8).absoluteValue;
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
                assertEquals(8, loader.loadClass("app.ExtensionConsumer")
                        .getMethod("absoluteResult").invoke(null));
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
