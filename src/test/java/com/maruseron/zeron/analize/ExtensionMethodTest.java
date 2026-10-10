package com.maruseron.zeron.analize;

import com.maruseron.zeron.StandardLibrary;
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
    public void resolvesSameNamedExtensionsByReceiverType() throws Exception {
        final var output = Files.createTempDirectory("zeron-receiver-specific-extensions");
        try {
            final var app = unit("""
                    package app;
                    import zeron.collections.*;
                    extension List<Int> {
                        fn sum(): Int = this.stream().fold(0, (acc, item) -> acc + item);
                    }
                    extension List<String> {
                        fn sum(): String = this.stream().fold("", (acc, item) -> acc + item);
                    }
                    fn integerSum(): Int = List<Int>.of(1, 2, 3).sum();
                    fn stringSum(): String = List<String>.of("a", "b", "c").sum();
                    """, "Main.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app), "app.Main", "app", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass("app.Main");
                assertEquals(6, generated.getMethod("integerSum").invoke(null));
                assertEquals("abc", generated.getMethod("stringSum").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsDuplicateExtensionForSameReceiverAndName() {
        final var extensions = unit("""
                package app;
                extension List<Int> {
                    fn sum(): Int = 1;
                }
                extension List<Int> {
                    fn sum(): Int = 2;
                }
                """, "Extensions.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(extensions));

        assertTrue(result.errors().toString(), result.errors().stream()
                .anyMatch(error -> error.message().contains("An extension for this receiver and name")));
    }

    @Test
    public void rejectsExtensionsThatCollideAfterJvmErasure() {
        final var extensions = unit("""
                package app;
                import zeron.collections.List;
                extension List<Int> {
                    fn label(): String = "integer";
                }
                extension List<String> {
                    fn label(): String = "string";
                }
                """, "Extensions.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(extensions));

        assertTrue(result.errors().toString(), result.errors().stream()
                .anyMatch(error -> error.message().contains("same erased JVM signature")));

        final var defaultArgumentExtensions = unit("""
                package app;
                import zeron.collections.List;
                extension List<Int> {
                    fn format(value: Int = 0): String = "integer";
                }
                extension List<String> {
                    fn format(value: String = ""): String = "string";
                }
                """, "DefaultExtensions.zn");
        final var defaultResult = new ResolutionService().resolveUnitsWithDiagnostics(
                List.of(defaultArgumentExtensions));
        assertTrue(defaultResult.errors().toString(), defaultResult.errors().stream()
                .anyMatch(error -> error.message().contains("same erased JVM signature")));
    }

    @Test
    public void emitsLambdasDeclaredInsideExtensionMethodBodies() throws Exception {
        final var output = Files.createTempDirectory("zeron-extension-body-lambda");
        try {
            final var app = unit("""
                    package app;
                    import zeron.collections.*;
                    extension List<Int> {
                        fn sum(): Int = this.stream().fold(0, (acc, item) -> acc + item);
                    }
                    fn result(): Int = List<Int>.of(1, 2, 3).sum();
                    """, "Main.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app), "app.Main", "app", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(6, loader.loadClass("app.Main").getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void passesBroadlyAcceptingFunctionToGenericCollectionCallback() throws Exception {
        final var output = Files.createTempDirectory("zeron-broad-function-callback");
        try {
            final var app = unit("""
                    package app;
                    import zeron.collections.*;
                    fn main() {
                        List.of(1, 2, 3).forEach(println);
                    }
                    """, "Main.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app), "app.Main", "app", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                loader.loadClass("app.Main").getMethod("main").invoke(null);
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void infersNullableGenericExtensionArgumentFromReceiverWhenArgumentIsNull() throws Exception {
        final var output = Files.createTempDirectory("zeron-null-generic-extension-argument");
        try {
            final var app = unit("""
                    package app;
                    import zeron.collections.*;
                    fn indexOfNull(): Int {
                        let values: Array<Int?> = [1, 2, 3, null, 5];
                        return values.indexOf(null);
                    }
                    """, "Main.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app), "app.Main", "app", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(3, loader.loadClass("app.Main").getMethod("indexOfNull").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void extensionsForSameReceiverShareNamespaceOwnerAcrossFiles() throws Exception {
        final var output = Files.createTempDirectory("zeron-shared-extension-namespace");
        try {
            final var model = unit("""
                    package model;
                    public class Box {
                        public constructor new;
                    }
                    """, "Box.zn");
            final var firstExtension = unit("""
                    package extras;
                    import model.Box;
                    public extension Box {
                        public fn first(): Int = 1;
                    }
                    """, "FirstExtension.zn");
            final var secondExtension = unit("""
                    package extras;
                    import model.Box;
                    public extension Box {
                        public fn second(): Int = 2;
                    }
                    namespace Box {
                        public fn fromArray(values: Array<Int>): Int = values[0];
                    }
                    """, "SecondExtension.zn");
            final var app = unit("""
                    package app;
                    import model.Box;
                    import extras.Box.first;
                    import extras.Box.second;
                    import extras.Box.fromArray;
                    fn extensionResults(): Int {
                        let box = Box.new();
                        return box.first() + box.second();
                    }
                    fn namespaceResult(): Int = fromArray([3, 4]);
                    """, "Main.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app, firstExtension, secondExtension, model),
                    "app.SharedExtensionNamespace", "app", List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            final var sharedOwner = output.resolve("extras").resolve("$zeron$Namespace$Box.class");
            assertTrue(Files.exists(sharedOwner));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass("app.SharedExtensionNamespace");
                assertEquals(3, generated.getMethod("extensionResults").invoke(null));
                assertEquals(3, generated.getMethod("namespaceResult").invoke(null));
                final var namespace = loader.loadClass("extras.$zeron$Namespace$Box");
                assertEquals(1, namespace.getMethod("first", loader.loadClass("model.Box")).invoke(null,
                        loader.loadClass("model.Box").getConstructor().newInstance()));
                assertEquals(2, namespace.getMethod("second", loader.loadClass("model.Box")).invoke(null,
                        loader.loadClass("model.Box").getConstructor().newInstance()));
                final var fromArray = java.util.Arrays.stream(namespace.getMethods())
                        .filter(method -> method.getName().equals("fromArray"))
                        .filter(method -> method.getParameterCount() == 1)
                        .filter(method -> method.getParameterTypes()[0].isArray())
                        .findFirst()
                        .orElseThrow();
                final var values = java.lang.reflect.Array.newInstance(
                        fromArray.getParameterTypes()[0].getComponentType(), 2);
                java.lang.reflect.Array.set(values, 0, 3);
                java.lang.reflect.Array.set(values, 1, 4);
                assertEquals(3, fromArray.invoke(null, values));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsNamespaceFunctionAndExtensionNameCollision() {
        final var model = unit("""
                package model;
                public class Box {
                    public constructor new;
                }
                """, "Box.zn");
        final var declarations = unit("""
                package extras;
                import model.Box;
                public extension Box {
                    public fn inspect(): Int = 1;
                }
                namespace Box {
                    public fn inspect(box: Box): Int = 2;
                }
                """, "Declarations.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(
                List.of(declarations, model));

        assertTrue(result.errors().toString(), result.errors().stream()
                .anyMatch(error -> error.message().contains("Function and extension names")));
    }

    @Test
    public void extensionsAreAvailableWithinTheirDeclaringFileOnly() throws Exception {
        final var output = Files.createTempDirectory("zeron-file-local-extension");
        try {
            final var app = unit("""
                    package app;
                    fn result(): Int = 4.twice();
                    public extension Int {
                        public fn twice(): Int = this * 2;
                    }
                    """, "App.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app), "app.FileLocalExtension", "app", List.of(), false,
                    List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(8, loader.loadClass("app.FileLocalExtension")
                        .getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(output);
        }

        final var extension = unit("""
                package extras;
                public extension Int {
                    public fn twice(): Int = this * 2;
                }
                """, "IntExtensions.zn");
        final var appWithoutImport = unit("""
                package app;
                fn result(): Int = 4.twice();
                """, "AppWithoutImport.zn");
        final var result = new ResolutionService().resolveUnitsWithDiagnostics(
                List.of(appWithoutImport, extension));
        assertTrue(result.errors().toString(), result.errors().stream()
                .anyMatch(error -> error.message().contains("Unknown method")));
    }

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
                .anyMatch(error -> error.message().contains("Ambiguous extension method")));
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
                    import extras.Readable.readExtended;
                    import extras.Readable.describe as contractDescribe;
                    import concrete.Cell.describe as concreteDescribe;
                    fn concrete(): String = Cell<Int>.new(7).concreteDescribe();
                    fn throughContract(value: Readable<Int>): Int = value.readExtended();
                    fn projected(value: Cell<Int>): Int = value.readExtended();
                    fn contractResult(value: Cell<Int>): String = value.contractDescribe();
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
                final var instance = loader.loadClass("model.Cell").getDeclaredConstructor(Object.class)
                        .newInstance(7);
                assertEquals("contract", generated.getMethod("contractResult", loader.loadClass("model.Cell"))
                        .invoke(null, instance));
                final var cell = loader.loadClass("model.Cell");
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
                .anyMatch(error -> error.message().contains("Ambiguous extension method")));
    }

    @Test
    public void reportsAmbiguityBetweenConcreteAndProjectedContractExtensions() {
        final var model = unit("""
                package model;
                public contract Readable {
                }
                public class Cell is Readable {
                    public constructor new;
                }
                """, "Model.zn");
        final var concreteExtensions = unit("""
                package concrete;
                import model.Cell;
                public extension Cell {
                    public fn label(): String = "concrete";
                }
                """, "CellExtensions.zn");
        final var contractExtensions = unit("""
                package projected;
                import model.Readable;
                public extension Readable {
                    public fn label(): String = "contract";
                }
                """, "ReadableExtensions.zn");
        final var app = unit("""
                package app;
                import model.Cell;
                import concrete.*;
                import projected.*;
                fn result(): String = Cell.new().label();
                """, "App.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(
                List.of(app, concreteExtensions, contractExtensions, model));

        assertTrue(result.errors().toString(), result.errors().stream()
                .anyMatch(error -> error.message().contains("Ambiguous extension method")));
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
                    import extras.Box.selected as extensionSelected;
                    fn value(): Int {
                        let box = Box.new(3);
                        box.bump();
                        return box.doubleValue();
                    }
                    fn fallback(): String = Box.new(1).extensionSelected();
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
    public void convertsNumericTypesThroughPrivateIntrinsicBindings() throws Exception {
        final var output = Files.createTempDirectory("zeron-int-to-float");
        try {
            final var app = unit("""
                    package app;
                    fn positive(): Float = 2147483647.toFloat();
                    fn negative(): Float = (-2147483647 - 1).toFloat();
                    fn converted(value: Float): Int = value.toInt().getOrElse(0);
                    fn hasValue(value: Float): Boolean = value.toInt().isSome();
                    """, "App.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app), "app.IntToFloat", "app", List.of(), true,
                    List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass("app.IntToFloat");
                assertEquals(2147483647.0, generated.getMethod("positive").invoke(null));
                assertEquals(-2147483648.0, generated.getMethod("negative").invoke(null));
                final var converted = generated.getMethod("converted", double.class);
                final var hasValue = generated.getMethod("hasValue", double.class);
                assertEquals(2147483647, converted.invoke(null, 2147483647.9));
                assertEquals(-2147483648, converted.invoke(null, -2147483648.9));
                assertTrue((Boolean) hasValue.invoke(null, 2147483647.9));
                assertEquals(0, converted.invoke(null, Double.NaN));
                assertEquals(0, converted.invoke(null, Double.POSITIVE_INFINITY));
                assertEquals(0, converted.invoke(null, 2147483648.0));
                assertEquals(0, converted.invoke(null, -2147483649.0));
                assertEquals(false, hasValue.invoke(null, Double.NaN));
                assertEquals(false, hasValue.invoke(null, Double.POSITIVE_INFINITY));
                assertEquals(false, hasValue.invoke(null, Double.NEGATIVE_INFINITY));
                assertEquals(false, hasValue.invoke(null, 2147483648.0));
                assertEquals(false, hasValue.invoke(null, -2147483649.0));
            }
        } finally {
            deleteTree(output);
        }

        final var unauthorizedImport = unit("""
                package app;
                import zeron.lang.intToFloat;
                import zeron.lang.floatToInt;
                fn result(): Float = intToFloat(1);
                """, "UnauthorizedImport.zn");
        final var libraryAndUnauthorizedImport = new java.util.ArrayList<>(StandardLibrary.bundledUnits());
        libraryAndUnauthorizedImport.add(unauthorizedImport);
        final var importResult = new ResolutionService().resolveUnitsWithDiagnostics(libraryAndUnauthorizedImport);
        assertTrue(importResult.errors().toString(), importResult.errors().stream()
                .anyMatch(error -> error.message().contains("is not public")));
    }

    @Test
    public void supportsEqualityOperatorsOnGenericTypeParameters() throws Exception {
        final var output = Files.createTempDirectory("zeron-generic-equality");
        try {
            final var app = unit("""
                    package app;
                    fn equal<T>(left: T, right: T): Boolean = left == right;
                    fn notEqual<T>(left: T, right: T): Boolean = left != right;
                    fn sameValues(): Boolean = equal("value", "value") and notEqual(1, 2);
                    fn differentValues(): Boolean = notEqual("left", "right") and equal(3, 3);
                    """, "GenericEquality.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(app), "app.GenericEquality", "app", List.of(), false,
                    List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass("app.GenericEquality");
                assertEquals(true, generated.getMethod("equal", Object.class, Object.class)
                        .invoke(null, "value", "value"));
                assertEquals(true, generated.getMethod("notEqual", Object.class, Object.class)
                        .invoke(null, 1, 2));
                assertEquals(true, generated.getMethod("sameValues").invoke(null));
                assertEquals(true, generated.getMethod("differentValues").invoke(null));
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
                    public extension Container<Int> {
                        public fn sum(): Int = 1;
                    }
                    public extension Container<String> {
                        public fn sum(): String = "strings";
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
            assertEquals(2, index.declarations().stream()
                    .filter(ZeronLibraryIndex.ExtensionExport.class::isInstance)
                    .map(ZeronLibraryIndex.ExtensionExport.class::cast)
                    .filter(extension -> extension.qualifiedName().equals("extras.Container.sum"))
                    .count());

            final var app = unit("""
                    package app;
                    import model.Box;
                    import model.Container;
                    import extras.Box.increment;
                    import extras.Container.readValue;
                    import extras.Int.absoluteValue;
                    import extras.Container.sum;
                    fn result(): Int {
                        let box = Box.new(4);
                        box.increment();
                        return box.value;
                    }
                    fn genericResult(): Int = Container<Int>.new(9).readValue();
                    fn absoluteResult(): Int = (-8).absoluteValue;
                    fn integerSum(): Int = Container<Int>.new(3).sum();
                    fn stringSum(): String = Container<String>.new("abc").sum();
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
                assertEquals(1, loader.loadClass("app.ExtensionConsumer")
                        .getMethod("integerSum").invoke(null));
                assertEquals("strings", loader.loadClass("app.ExtensionConsumer")
                        .getMethod("stringSum").invoke(null));
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
