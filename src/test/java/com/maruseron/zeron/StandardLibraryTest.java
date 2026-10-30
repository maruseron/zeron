package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.jar.JarFile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class StandardLibraryTest {

    @Test
    public void cellSharesMutableStateAcrossClosuresAndEscapesItsScope() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "cellFixture" + suffix;
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-cell-");
        final var entry = sourceRoot.resolve(packageName).resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package %s;
                import zeron.lang.Cell;
                fn shared(): Int {
                    let cell = Cell<Int>.new(0);
                    let increment = () -> {
                        cell.set(cell.get() + 1);
                        return cell.get();
                    };
                    let current = () -> cell.get();
                    increment();
                    increment();
                    return current();
                }
                fn escaped(): () -> Int {
                    let cell = Cell<Int>.new(42);
                    return () -> cell.get();
                }
                """.formatted(packageName));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of(packageName, "Main.zn").toString()));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(2, main.getMethod("shared").invoke(null));
                final var escaped = main.getMethod("escaped").invoke(null);
                assertEquals(42, escaped.getClass().getInterfaces()[0]
                        .getMethod("invoke").invoke(escaped));
            }
        } finally {
            deleteTree(sourceRoot);
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void cellMutationRequiresMutableReferenceView() {
        final var userUnit = parse("cell-read-only.zn", """
                package cellReadOnly;
                import zeron.lang.Cell;
                fn invalid(cell: Cell<Int>): Unit = cell.set(1);
                """);
        final var units = StandardLibrary.withBundledUnits(List.of(userUnit));

        assertThrows(ResolutionError.class,
                () -> new com.maruseron.zeron.analize.ResolutionService().resolveUnits(units));
    }

    @Test
    public void classPatternsExtractMultipleOutputsAndShortCircuitNestedRefutation() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "patternFixture" + suffix;
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-pattern-");
        final var entry = sourceRoot.resolve(packageName).resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package %s;
                public sealed contract Tree permits Node, Leaf, End {}
                public class Node is Tree {
                    head: Int;
                    tail: Tree;
                    public pattern uncons(value: Int, rest: Tree) {
                        value = this.head;
                        rest = this.tail;
                    }
                    public pattern positive(value: Int) when this.head > 0 {
                        value = this.head;
                    }
                }
                public class Leaf is Tree {
                    value: Int;
                    rest: Tree;
                    public pattern uncons(number: Int, tail: Tree) {
                        number = this.value;
                        tail = this.rest;
                    }
                }
                public class End is Tree {}
                fn nested(value: Tree): Int = match (value) {
                    case Node.uncons(first, Node.uncons(second, _)) -> first + second;
                    case _ -> 0;
                };
                fn positive(value: Tree): Int = match (value) {
                    case Node.positive(number) -> number;
                    case _ -> 0;
                };
                fn alternative(value: Tree): Int = match (value) {
                    case Node.uncons(number, _) | Leaf.uncons(number, _) -> number;
                    case _ -> 0;
                };
                """.formatted(packageName));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of(packageName, "Main.zn").toString()));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                final var node = loader.loadClass(packageName + ".Node");
                final var end = loader.loadClass(packageName + ".End");
                final var tree = loader.loadClass(packageName + ".Tree");
                final var endValue = end.getConstructor().newInstance();
                final var nested = node.getConstructor(int.class, tree).newInstance(20,
                        node.getConstructor(int.class, tree).newInstance(22, endValue));
                assertEquals(42, main.getMethod("nested", loader.loadClass(packageName + ".Tree"))
                        .invoke(null, nested));
                assertEquals(7, main.getMethod("positive", loader.loadClass(packageName + ".Tree"))
                        .invoke(null, node.getConstructor(int.class, tree).newInstance(7, endValue)));
                assertEquals(0, main.getMethod("positive", loader.loadClass(packageName + ".Tree"))
                        .invoke(null, node.getConstructor(int.class, tree).newInstance(-1, endValue)));
                final var leaf = loader.loadClass(packageName + ".Leaf")
                        .getConstructor(int.class, tree).newInstance(9, endValue);
                assertEquals(9, main.getMethod("alternative", tree).invoke(null, leaf));
            }
        } finally {
            deleteTree(sourceRoot);
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void resolvesStarImportsAgainstDeclarationsAndProtectsCurrentPackage() {
        final var source = """
                import zeron.collections.*;

                type List<T> = Nil | Cons(head: T, tail: List<T>);

                fn stringify<T: Display>(value: T): String = value.toString();
                fn main() {}
                """;
        final var userUnit = parse("main.zn", source);
        final var units = StandardLibrary.withBundledUnits(List.of(userUnit));
        final var compiler = CompilationService.forCompilationUnits(units, "Main", "");

        final var resolution = compiler.resolveWithDiagnostics();

        assertTrue(resolution.errors().toString(), resolution.errors().isEmpty());
    }

    @Test
    public void reportsAmbiguousUnqualifiedTypeFromStarImports() {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var firstPackage = "typesA" + suffix;
        final var secondPackage = "typesB" + suffix;
        final var appPackage = "typesApp" + suffix;
        final var first = parse("first.zn", """
                package %s;
                public contract Clash {}
                """.formatted(firstPackage));
        final var second = parse("second.zn", """
                package %s;
                public contract Clash {}
                """.formatted(secondPackage));
        final var app = parse("app.zn", """
                package %s;
                import %s.*;
                import %s.*;
                fn require<T: Clash>(value: T): Unit {}
                """.formatted(appPackage, firstPackage, secondPackage));
        final var compiler = CompilationService.forCompilationUnits(
                List.of(first, second, app), "Main", appPackage);

        final var resolution = compiler.resolveWithDiagnostics();

        assertTrue(resolution.errors().toString(),
                resolution.errors().stream().anyMatch(error -> error.message().contains("Ambiguous type 'Clash'")));
    }

    @Test
    public void matchExhaustivelySelectsOptionCasesAndEvaluatesScrutineeOnce() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "matchFixture" + suffix;
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-match-");
        final var entry = sourceRoot.resolve(packageName).resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package %s;
                import zeron.lang.Option;
                let mut calls = 0;
                fn next(): Option<Int> {
                    calls += 1;
                    return Option.Some(42);
                }
                fn someResult(): Int = match (next()) {
                    case Option.Some(value) -> value;
                    case Option.None() -> -1;
                };
                fn noneResult(): Int {
                    let value: Option<Int> = Option<Int>.None();
                    return match (value) {
                        case Option.Some(item) -> item;
                        case Option.None() -> -1;
                    };
                }
                fn wildcardResult(): Int {
                    let value: Option<Int> = Option<Int>.None();
                    return match (value) {
                        case Option.Some(item) -> item;
                        case _ -> 7;
                    };
                }
                fn acceptsExternalNull(value: Option<Int>): Int = match (value) {
                    case Option.Some(item) -> item;
                    case _ -> 7;
                };
                fn callCount(): Int = calls;
                """.formatted(packageName));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of(packageName, "Main.zn").toString()));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(42, main.getMethod("someResult").invoke(null));
                assertEquals(1, main.getMethod("callCount").invoke(null));
                assertEquals(-1, main.getMethod("noneResult").invoke(null));
                assertEquals(7, main.getMethod("wildcardResult").invoke(null));
                final var optionClass = loader.loadClass("zeron.lang.Option");
                final var nullFailure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> main.getMethod("acceptsExternalNull", optionClass)
                                .invoke(null, new Object[]{null}));
                assertTrue(nullFailure.getCause() instanceof IllegalStateException);
            }
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(sourceRoot);
        }
    }

    @Test
    public void matchClassHierarchyIncludesBundledTypesAlongsideLambdaShapes() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-match-hierarchy-");
        final var source = parse("Main.zn", """
                import zeron.collections.List;
                import zeron.lang.Option;
                import List.map;
                namespace List {
                    public fn sort<T>(list: &List<T>): Unit = ();
                }
                public extension<T> List<T> {
                    public fn map<U>(f: (T) -> U): &List<U> {
                        let result = List<U>.empty();
                        for (let value in this) result.add(f(value));
                        return result;
                    }
                }
                fn result(): Int {
                    let values = List<Int>.of(1, 2);
                    let mapped = values.map(value -> value + 1);
                    List.sort(mapped);
                    let option: Option<Int> = Option.Some(5);
                    return match (option) {
                        case Option.Some(value) -> value;
                        case Option.None() -> -1;
                    };
                }
                """);
        final var compiler = CompilationService.forCompilationUnits(List.of(source),
                "MatchHierarchyRegression", "", List.of(), true, List.of(), output);

        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(5, loader.loadClass("MatchHierarchyRegression").getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void genericClassFactoryCallsInferArgumentsFromValuesAndContext() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-class-inference-");
        final var source = parse("Main.zn", """
                import zeron.collections.List;
                class Cell<T> {
                    public property value: T;
                    public constructor new;
                    public constructor from(value: T) = Cell<T>.new(value);
                }
                class Generated<T> {
                    public property value: T;
                    public constructor new;
                    public constructor create(make: () -> T) = Generated<T>.new(make());
                }
                fn inferredFromValues(): Int {
                    let values = List.of(1, 2, 3);
                    return values.size;
                }
                fn inferredFromAnnotation(): Int {
                    let values: List<Int> = List.empty();
                    return values.size;
                }
                fn returnsInferredList(): List<Int> = List.empty();
                fn acceptsList(values: List<Int>): Int = values.size;
                fn inferredFromArgument(): Int = acceptsList(List.empty());
                fn inferredCanonicalConstructor(): Int = Cell.new(7).value;
                fn inferredNamedConstructor(): Int = Cell.from(9).value;
                fn inferredFromLambdaResult(): Int = Generated.create(() -> 13).value;
                """);

        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(source),
                    "ClassInferenceRegression", "", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass("ClassInferenceRegression");
                assertEquals(3, main.getMethod("inferredFromValues").invoke(null));
                assertEquals(0, main.getMethod("inferredFromAnnotation").invoke(null));
                final var returnedList = main.getMethod("returnsInferredList").invoke(null);
                assertEquals(0, returnedList.getClass().getMethod("$zeron$get$size").invoke(returnedList));
                assertEquals(0, main.getMethod("inferredFromArgument").invoke(null));
                assertEquals(7, main.getMethod("inferredCanonicalConstructor").invoke(null));
                assertEquals(9, main.getMethod("inferredNamedConstructor").invoke(null));
                assertEquals(13, main.getMethod("inferredFromLambdaResult").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void genericClassFactoryInferenceRejectsMissingAndConflictingEvidence() {
        final var noEvidence = parse("Main.zn", """
                import zeron.collections.List;
                fn result() = List.empty();
                """);
        final var noEvidenceCompiler = CompilationService.forCompilationUnits(
                List.of(noEvidence), "ClassInferenceMissing", "");
        assertThrows(ResolutionError.class, noEvidenceCompiler::resolve);

        final var conflicting = parse("Main.zn", """
                import zeron.collections.List;
                fn result() = List.of(1, "text");
                """);
        final var conflictingCompiler = CompilationService.forCompilationUnits(
                List.of(conflicting), "ClassInferenceConflict", "");
        assertThrows(ResolutionError.class, conflictingCompiler::resolve);

        final var incompatibleExpectedType = parse("ExpectedType.zn", """
                import zeron.collections.List;
                fn result(): List<String> = List.of(1);
                """);
        final var incompatibleExpectedTypeCompiler = CompilationService.forCompilationUnits(
                List.of(incompatibleExpectedType), "ClassInferenceExpectedConflict", "");
        assertThrows(ResolutionError.class, incompatibleExpectedTypeCompiler::resolve);

        final var incompatibleExplicitType = parse("ExplicitType.zn", """
                import zeron.collections.List;
                fn result() = List<String>.of(1);
                """);
        final var incompatibleExplicitTypeCompiler = CompilationService.forCompilationUnits(
                List.of(incompatibleExplicitType), "ClassInferenceExplicitConflict", "");
        assertThrows(ResolutionError.class, incompatibleExplicitTypeCompiler::resolve);
    }

    @Test
    public void genericClassFactoryAndNamespaceFunctionNameIsAlwaysAmbiguous() {
        final var source = parse("Main.zn", """
                class Box<T> {
                    value: T;
                    public constructor new;
                    public constructor make(value: T) = Box<T>.new(value);
                }
                namespace Box {
                    public fn make(value: Int): &Box<Int> = Box<Int>.new(value);
                }
                fn result() = Box.make(1);
                """);
        final var compiler = CompilationService.forCompilationUnits(List.of(source),
                "ClassNamespaceAmbiguity", "");

        final var error = assertThrows(ResolutionError.class, compiler::resolve);

        assertTrue(error.getMessage().contains("Ambiguous name"));

        final var nonMatchingFactory = parse("NonMatchingFactory.zn", """
                class Factory<T> {
                    public constructor new;
                    public constructor create(value: Int) = Factory<T>.new();
                }
                namespace Factory {
                    public fn create(value: String): &Factory<String> = Factory<String>.new();
                }
                fn result(): &Factory<String> = Factory.create("value");
                """);
        final var nonMatchingCompiler = CompilationService.forCompilationUnits(List.of(nonMatchingFactory),
                "ClassNamespaceSelection", "");
        final var nonMatchingError = assertThrows(ResolutionError.class, nonMatchingCompiler::resolve);
        assertTrue(nonMatchingError.getMessage().contains("Ambiguous name"));
    }

    @Test
    public void matchDestructuresGenericOptionAndResultPropertiesWithGuards() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "matchPayload" + suffix;
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-match-payload-");
        final var entry = sourceRoot.resolve(packageName).resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package %s;
                import zeron.lang.Option;
                import zeron.lang.Result;
                fn optionValue(value: Option<Int>): Int = match (value) {
                    case Option.Some(item) if item > 0 -> item;
                    case Option.Some(_) -> 0;
                    case Option.None() -> -1;
                };
                fn resultValue(value: Result<Int, String>): Int = match (value) {
                    case Result.Ok(item) if item > 0 -> item;
                    case Result.Ok(_) -> 0;
                    case Result.Err(message) -> if (message == "bad") then 7 else -1;
                };
                fn some(): Option<Int> = Option.Some(42);
                fn negative(): Option<Int> = Option.Some(-2);
                fn none(): Option<Int> = Option<Int>.None();
                fn ok(): Result<Int, String> = Result.ok<Int, String>(9);
                fn bad(): Result<Int, String> = Result.err<Int, String>("bad");
                """.formatted(packageName));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of(packageName, "Main.zn").toString()));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                final var some = main.getMethod("some").invoke(null);
                final var negative = main.getMethod("negative").invoke(null);
                final var none = main.getMethod("none").invoke(null);
                final var ok = main.getMethod("ok").invoke(null);
                final var bad = main.getMethod("bad").invoke(null);
                final var optionClass = loader.loadClass("zeron.lang.Option");
                final var resultClass = loader.loadClass("zeron.lang.Result");
                assertEquals(42, main.getMethod("optionValue", optionClass)
                        .invoke(null, some));
                assertEquals(0, main.getMethod("optionValue", optionClass)
                        .invoke(null, negative));
                assertEquals(-1, main.getMethod("optionValue", optionClass)
                        .invoke(null, none));
                assertEquals(9, main.getMethod("resultValue", resultClass)
                        .invoke(null, ok));
                assertEquals(7, main.getMethod("resultValue", resultClass)
                        .invoke(null, bad));
            }
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(sourceRoot);
        }
    }

    @Test
    public void matchRejectsMissingDuplicateAndUnreachableCases() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "invalidMatch" + suffix;
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-match-invalid-");
        final var entry = sourceRoot.resolve(packageName).resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        final var header = """
                package %s;
                import zeron.lang.Option;
                fn result(value: Option<Int>): Int =
                """.formatted(packageName);

        try {
            for (final var match : List.of(
                    "match (value) { case Option.Some(_) -> 1; }",
                    "match (value) { case Option.Some(_) -> 1; case Option.Some(_) -> 2; case Option.None() -> 3; }",
                    "match (value) { case Option.Some(_) if true -> 1; case Option.None() -> 2; }",
                    "match (value) { case Option.Some(_) -> 1; case Option.None() if true -> 2; }",
                    "match (value) { case _ if true -> 1; }",
                    "match (value) { case Option.Some(_) if 1 -> 1; case Option.None() -> 2; }",
                    "match (value) { case Option.Some(_) -> 1; case Option.None() -> 2; case Option.Some(_) -> 3; }",
                    "match (value) { case _ -> 1; case Option.Some(_) -> 2; }",
                    "match (value) { case Option.Some(_) -> 1; case Option.None() -> 2; case _ -> 3; }",
                    "match (value) { case Option<Int> -> 1; }",
                    "match (value) { case Option<String>.Some(_) -> 1; case Option.None() -> 2; }")) {
                Files.writeString(entry, header + match + ";");
                deleteTree(Path.of("dist"));
                assertTrue("Expected invalid match to fail: " + match,
                        Zeron.runCli("--root", sourceRoot.toString(),
                                "--entry", Path.of(packageName, "Main.zn").toString()) != 0);
            }
            Files.writeString(entry, """
                    package %s;
                    import zeron.lang.Option;
                    fn result(value: Option<Int>?): Int = match (value) {
                        case Option.Some(_) -> 1;
                        case Option.None() -> 2;
                    };
                    """.formatted(packageName));
            deleteTree(Path.of("dist"));
            assertTrue(Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of(packageName, "Main.zn").toString()) != 0);
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(sourceRoot);
        }
    }

    @Test
    public void trailingDefaultsEvaluateInOrderForFunctionsAndContractMethods() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "defaults" + suffix;
        final var source = Files.createTempFile(Path.of("target"), "Defaults" + suffix, ".zn");
        Files.writeString(source, """
                package %s;
                let mut evaluations = 0;
                fn next(value: Int): Int {
                    evaluations += 1;
                    return value;
                }
                fn compute(first: Int, second: Int = next(first + 1),
                           third: Int = next(second + 1)): Int = third;
                fn choose<T>(value: T, fallback: T = value): T = fallback;
                contract Incrementer {
                    increment(value: Int, amount: Int = value + 2): Int;
                }
                class Counter is Incrementer {
                    public increment(value: Int, amount: Int): Int = value + amount;
                }
                class LocalCounter {
                    public increment(value: Int, amount: Int = 3): Int = value + amount;
                    public choose<T>(value: T, fallback: T = value): T = fallback;
                }
                fn makeCounter(): Incrementer = Counter.new();
                fn throughContract(counter: Incrementer): Int = counter.increment(10);
                fn throughClass(): Int = Counter.new().increment(10);
                fn throughLocalMethod(): Int = LocalCounter.new().increment(10);
                fn genericFunctionDefault(): Int = choose<Int>(17);
                fn genericMethodDefault(): Int = LocalCounter.new().choose<Int>(19);
                fn evaluationCount(): Int = evaluations;
                """.formatted(packageName));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli(source.toString()));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + "." +
                        source.getFileName().toString().replaceFirst("\\.zn$", ""));
                assertEquals(4, main.getMethod("compute", int.class).invoke(null, 2));
                assertEquals(2, main.getMethod("evaluationCount").invoke(null));
                final var contract = loader.loadClass(packageName + ".Incrementer");
                final var counter = main.getMethod("makeCounter").invoke(null);
                assertEquals(22, main.getMethod("throughContract", contract).invoke(null, counter));
                assertEquals(22, main.getMethod("throughClass").invoke(null));
                assertEquals(13, main.getMethod("throughLocalMethod").invoke(null));
                assertEquals(17, main.getMethod("genericFunctionDefault").invoke(null));
                assertEquals(19, main.getMethod("genericMethodDefault").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
            Files.deleteIfExists(source);
        }
    }

    @Test
    public void variadicParametersPackDirectCallsAndKeepArrayFunctionValues() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "variadic" + suffix;
        final var source = Files.createTempFile(Path.of("target"), "Variadic" + suffix, ".zn");
        Files.writeString(source, """
                package %s;
                import zeron.collections.List;
                fn tally(prefix: Int, values: Int...): Int = prefix + values.length;
                fn count<T>(values: T...): Int = values.length;
                fn arrayCount(values: Int...): Int = values.length;
                fn withDefault(prefix: Int = 6, values: Int...): Int = prefix + values.length;
                contract Metric {
                    measure(prefix: Int = 4, values: Int...): Int;
                }
                class Counter is Metric {
                    public measure(prefix: Int, values: Int...): Int = prefix + values.length;
                }
                class LocalCounter {
                    public measure(prefix: Int = 2, values: Int...): Int = prefix + values.length;
                }
                fn makeCounter(): Metric = Counter.new();
                fn empty(): Int = tally(10);
                fn many(): Int = tally(10, 1, 2, 3);
                fn generic(): Int = count(1, 2, 3);
                fn withDefaultEmpty(): Int = withDefault();
                fn withDefaultMany(): Int = withDefault(9, 1, 2);
                fn defaults(): Int = Counter.new().measure();
                fn contractCall(metric: Metric): Int = metric.measure(7, 1, 2);
                fn localDefault(): Int = LocalCounter.new().measure();
                fn localMany(): Int = LocalCounter.new().measure(3, 1, 2);
                fn indirect(): Int {
                    let operation: (Array<Int>) -> Int = arrayCount;
                    return operation([1, 2, 3]);
                }
                fn listEmpty(): Int = List<Int>.of().size;
                fn listMany(): Int = List<Int>.of(1, 2, 3).size;
                """.formatted(packageName));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli(source.toString()));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + "." +
                        source.getFileName().toString().replaceFirst("\\.zn$", ""));
                assertEquals(10, main.getMethod("empty").invoke(null));
                assertEquals(13, main.getMethod("many").invoke(null));
                assertEquals(3, main.getMethod("generic").invoke(null));
                assertEquals(6, main.getMethod("withDefaultEmpty").invoke(null));
                assertEquals(11, main.getMethod("withDefaultMany").invoke(null));
                assertEquals(4, main.getMethod("defaults").invoke(null));
                assertEquals(2, main.getMethod("localDefault").invoke(null));
                assertEquals(5, main.getMethod("localMany").invoke(null));
                final var metric = loader.loadClass(packageName + ".Metric");
                final var counter = main.getMethod("makeCounter").invoke(null);
                assertEquals(9, main.getMethod("contractCall", metric).invoke(null, counter));
                assertEquals(3, main.getMethod("indirect").invoke(null));
                assertEquals(0, main.getMethod("listEmpty").invoke(null));
                assertEquals(3, main.getMethod("listMany").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
            Files.deleteIfExists(source);
        }
    }

    @Test
    public void contractImplementationsMustPreserveVariadicParameterIdentity() {
        final var packageName = "variadicMismatch" + UUID.randomUUID().toString().replace("-", "");
        final var unit = parse("Mismatch.zn", """
                package %s;
                contract Collector {
                    collect(values: Int...): Int;
                }
                class InvalidCollector is Collector {
                    public collect(values: Array<Int>): Int = values.length;
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(unit)),
                packageName + ".Mismatch", packageName);

        assertThrows(ResolutionError.class, compiler::resolve);
    }

    @Test
    public void defaultsCannotReferToLaterParameters() {
        final var packageName = "invalidDefaults" + UUID.randomUUID().toString().replace("-", "");
        final var source = parse("InvalidDefaults.zn", """
                package %s;
                fn invalid(first: Int = second, second: Int = 1): Int = first;
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(source)),
                packageName + ".InvalidDefaults", packageName);

        assertThrows(ResolutionError.class, compiler::resolve);
    }

    @Test
    public void externalFunctionDefaultsUseTheRegisteredFullArityBinding() throws Exception {
        final var packageName = "externalDefaults" + UUID.randomUUID().toString().replace("-", "");
        final var source = parse("ExternalDefaults.zn", """
                package %s;
                external fn nativeAdd(left: Int, right: Int = left + 5): Int;
                external fn nativeCount(prefix: Int, values: Int...): Int;
                fn result(): Int = nativeAdd(7);
                fn variadicResult(): Int = nativeCount(10, 1, 2, 3);
                """.formatted(packageName));
        final var signature = com.maruseron.zeron.domain.TypeDescriptor.functionOf(
                "nativeAdd",
                com.maruseron.zeron.domain.TypeDescriptor.ofInt(),
                com.maruseron.zeron.domain.TypeDescriptor.ofInt(),
                com.maruseron.zeron.domain.TypeDescriptor.ofInt());
        final var variadicSignature = com.maruseron.zeron.domain.TypeDescriptor.functionOf(
                "nativeCount",
                com.maruseron.zeron.domain.TypeDescriptor.ofInt(),
                com.maruseron.zeron.domain.TypeDescriptor.ofInt(),
                com.maruseron.zeron.domain.TypeDescriptor.arrayOf(
                        com.maruseron.zeron.domain.TypeDescriptor.ofInt()));
        final var bindings = com.maruseron.zeron.domain.FunctionBindingRegistry.of(java.util.Map.of(
                packageName + ".nativeAdd",
                new com.maruseron.zeron.domain.FunctionBindingRegistry.Binding(signature,
                        new com.maruseron.zeron.domain.FunctionBindingRegistry.StaticMethod(
                                java.lang.constant.ClassDesc.of("com.maruseron.zeron.StandardLibraryTest"),
                                "nativeAdd",
                                java.lang.constant.MethodTypeDesc.of(
                                        java.lang.constant.ConstantDescs.CD_int,
                                        java.lang.constant.ConstantDescs.CD_int,
                                        java.lang.constant.ConstantDescs.CD_int))),
                packageName + ".nativeCount",
                new com.maruseron.zeron.domain.FunctionBindingRegistry.Binding(variadicSignature,
                        new com.maruseron.zeron.domain.FunctionBindingRegistry.StaticMethod(
                                java.lang.constant.ClassDesc.of("com.maruseron.zeron.StandardLibraryTest"),
                                "nativeCount",
                                java.lang.constant.MethodTypeDesc.of(
                                        java.lang.constant.ConstantDescs.CD_int,
                                        java.lang.constant.ConstantDescs.CD_int,
                                        java.lang.constant.ConstantDescs.CD_Object.arrayType())))));

        try {
            deleteTree(Path.of("dist"));
            final var compiler = CompilationService.forCompilationUnits(List.of(source),
                    packageName + ".ExternalDefaults", packageName, List.of(), true,
                    List.of(Path.of("target", "test-classes")), bindings);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(19, loader.loadClass(packageName + ".ExternalDefaults")
                        .getMethod("result").invoke(null));
                assertEquals(13, loader.loadClass(packageName + ".ExternalDefaults")
                        .getMethod("variadicResult").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    public static int nativeAdd(final int left, final int right) {
        return left + right;
    }

    public static int nativeCount(final int prefix, final Object[] values) {
        return prefix + values.length;
    }

    @Test
    public void bundledSourcesFollowTheirPackageDirectoryStructure() throws Exception {
        final var sourceRoot = Path.of("src", "main", "resources", "stdlib");
        final var expectedSources = new ArrayList<String>();
        try (final var sources = Files.walk(sourceRoot)) {
            sources.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".zn"))
                    .map(sourceRoot::relativize)
                    .map(path -> "/stdlib/" + path.toString().replace('\\', '/'))
                    .sorted()
                    .forEach(expectedSources::add);
        }
        assertEquals(expectedSources, StandardLibrary.BUNDLED_SOURCE_PATHS);
        try (final var index = StandardLibrary.class.getResourceAsStream("/stdlib/sources.index")) {
            assertTrue(index != null);
            assertEquals(expectedSources,
                    new String(index.readAllBytes(), StandardCharsets.UTF_8).lines().sorted().toList());
        }
        for (final var resourcePath : StandardLibrary.BUNDLED_SOURCE_PATHS) {
            final var sourcePath = sourceRoot.resolve(resourcePath.substring("/stdlib/".length()));
            final var relativeDirectory = sourceRoot.relativize(sourcePath).getParent()
                    .toString().replace('\\', '.').replace('/', '.');
            final var sourceUnit = Parser.of(Scanner.from(Files.readString(sourcePath)).scanTokens())
                    .parseCompilationUnit(sourcePath.toString());
            assertEquals(sourcePath.toString(), relativeDirectory, sourceUnit.packageName());
        }
    }

    @Test
    public void vector2DelegatingWitnessUsesItsVectorContractType() {
        final var result = new com.maruseron.zeron.analize.ResolutionService()
                .resolveUnitsWithDiagnostics(StandardLibrary.bundledUnits());
        final var vector2Errors = result.errors().stream()
                .filter(diagnostic -> diagnostic.primarySpan().sourcePath() != null
                        && diagnostic.primarySpan().sourcePath().replace('\\', '/')
                                .endsWith("/zeron/numeric/vector2.zn"))
                .toList();

        assertTrue(vector2Errors.toString(), vector2Errors.isEmpty());
    }

    @Test
    public void installedStandardLibrarySourcesResolveFromDistributionRoot() throws Exception {
        final var distribution = Files.createTempDirectory("zeron-stdlib-distribution");
        final var relativePath = Path.of("zeron", "lang", "option.zn");
        final var source = distribution.resolve("share").resolve("zeron").resolve("stdlib")
                .resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.copy(Path.of("src", "main", "resources", "stdlib").resolve(relativePath), source);
        final var previousHome = System.getProperty("zeron.home");
        try {
            System.setProperty("zeron.home", distribution.toString());
            assertEquals(source.toAbsolutePath().normalize(),
                    StandardLibrary.bundledSourceFile("/stdlib/zeron/lang/option.zn").orElseThrow());
        } finally {
            if (previousHome == null) System.clearProperty("zeron.home");
            else System.setProperty("zeron.home", previousHome);
            deleteTree(distribution);
        }
    }

    @Test
    public void cliErrorStateDoesNotLeakBetweenInvocations() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var invalidSource = Files.createTempFile(Path.of("target"), "InvalidCli" + suffix, ".zn");
        final var validSource = Files.createTempFile(Path.of("target"), "ValidCli" + suffix, ".zn");
        final var generatedClass = Path.of("dist", validSource.getFileName().toString()
                .replaceFirst("\\.zn$", "") + ".class");
        Files.writeString(invalidSource, "fn invalid(): Int = missing;");
        Files.writeString(validSource, "fn valid(): Int = 42;");

        try {
            assertEquals(71, Zeron.runCli(invalidSource.toString()));
            assertEquals(0, Zeron.runCli(validSource.toString()));
            assertTrue(Files.exists(generatedClass));
        } finally {
            Files.deleteIfExists(invalidSource);
            Files.deleteIfExists(validSource);
            Files.deleteIfExists(generatedClass);
        }
    }

    @Test
    public void cliCompilesProjectRootsAndEnforcesPackageDirectoryLayout() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-project-" + suffix);
        final var packageDirectory = sourceRoot.resolve("app");
        final var mainSource = packageDirectory.resolve("Main.zn");
        final var helperSource = packageDirectory.resolve("Helper.zn");
        final var className = "app.Main";
        final var mainClass = Path.of("dist", "app", "Main.class");
        Files.createDirectories(packageDirectory);
        Files.writeString(mainSource, """
                package app;
                fn result(): Int = answer();
                """);
        Files.writeString(helperSource, """
                package app;
                fn answer(): Int = 42;
                """);

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--root", sourceRoot.toString(), "--entry", "app/Main.zn"));
            assertTrue(Files.exists(mainClass));
            try (final var generatedClasses = Files.list(Path.of("dist", "app"))) {
                assertTrue(generatedClasses.filter(path -> path.toString().endsWith(".class")).count() >= 2);
            }
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(className).getMethod("result").invoke(null));
            }

            deleteTree(Path.of("dist"));
            Files.writeString(helperSource, "package wrong; fn answer(): Int = 42;");
            assertEquals(65, Zeron.runCli("--root", sourceRoot.toString(), "--entry", "app/Main.zn"));
            assertFalse(Files.exists(mainClass));
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(sourceRoot);
        }
    }

    @Test
    public void bundledPrintFunctionsUseExternalClassFacades() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "ioClient" + suffix;
        final var classSimpleName = "PrintFunctions" + suffix;
        final var className = packageName + "." + classSimpleName;
        final var outputDirectory = Path.of("dist", packageName);
        final var source = parse("PrintFunctions.zn", """
                package %s;
                import zeron.io.print;
                import zeron.io.println;
                import java.lang.System;
                fn emit(): Unit {
                    print("a");
                    print(42);
                    println("b");
                    println(null);
                    System.out?.println("direct");
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(source)), className, packageName);

        try {
            compiler.resolve();
            compiler.compile();
            final var output = new ByteArrayOutputStream();
            final var originalOutput = System.out;
            try (final var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                System.setOut(capture);
                try (final var loader = new URLClassLoader(
                        new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                    loader.loadClass(className).getMethod("emit").invoke(null);
                } finally {
                    System.setOut(originalOutput);
                }
            }
            assertEquals("a42b" + System.lineSeparator() + "null" + System.lineSeparator()
                            + "direct" + System.lineSeparator(),
                    output.toString(StandardCharsets.UTF_8));
        } finally {
            deleteTree(outputDirectory);
        }
    }

    @Test
    public void generatedUnitMainCanBeLaunchedByJava() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "launchClient" + suffix;
        final var className = packageName + ".Main";
        final var source = parse("Main.zn", """
                package %s;
                import zeron.io.println;
                fn main(): Unit {
                    println("launched");
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(source)), className, packageName);

        try {
            deleteTree(Path.of("dist"));
            compiler.resolve();
            compiler.compile();

            final var javaCommand = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
            final var process = new ProcessBuilder(javaCommand.toString(), "-cp",
                    Path.of("dist").toAbsolutePath().toString(), className)
                    .redirectErrorStream(true)
                    .start();
            final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor());
            assertEquals("launched" + System.lineSeparator(), output);
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void cliRunsProjectFromManifest() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "runClient" + suffix;
        final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-run-project-" + suffix)
                .toAbsolutePath().normalize();
        final var sourcePath = projectRoot.resolve("src").resolve(packageName).resolve("Main.zn");
        final var manifest = projectRoot.resolve("zeron.toml");
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, """
                package %s;
                fn main(): Unit {}
                """.formatted(packageName));
        Files.writeString(manifest, """
                [project]
                entry = "src/%s/Main.zn"
                source-roots = ["src"]
                output = "output"
                """.formatted(packageName));

        try {
            assertEquals(0, Zeron.runCli("run", "--manifest", manifest.toString()));
            assertTrue(Files.isRegularFile(projectRoot.resolve("output").resolve(packageName).resolve("Main.class")));
        } finally {
            deleteTree(projectRoot);
        }
    }

    @Test
    public void explainCommandPrintsBundledDiagnosticDescription() throws Exception {
        final var output = new ByteArrayOutputStream();
        final var originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertEquals(0, Zeron.runCli("explain", "ZR2014"));
        } finally {
            System.setOut(originalOut);
        }
        final var explanation = output.toString(StandardCharsets.UTF_8);
        assertTrue(explanation.startsWith("ZR2014: Type mismatch"));
        assertTrue(explanation.contains("Typical example:"));
        assertTrue(explanation.contains("    let value: Int = \"text\";"));
        assertFalse(explanation.contains("```"));
    }

    @Test
    public void optionDistinguishesSomeNullableValueFromNone() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "optionClient" + suffix;
        final var className = packageName + ".OptionTest" + suffix;
        final var source = parse("OptionTest.zn", """
                package %s;
                import zeron.lang.Option;

                fn someValue(): Int = Option.Some(41).fold(value -> value + 1, () -> 0);
                fn noneValue(): Int = Option<Int>.None().fold(value -> value, () -> 42);
                fn mappedValue(): Int = Option.Some(41).map(value -> value + 1).getOrElse(0);
                fn filteredValue(): Int = Option.Some(42)
                    .filter(value -> value > 0).getOrElse(0);
                fn rejectedValue(): Int = Option.Some(42)
                    .filter(value -> false).getOrElse(-1);
                fn someNullIsPresent(): Boolean {
                    let option: Option<String?> = Option.Some(null);
                    return option.isSome()
                        and option.fold(value -> value == null, () -> false);
                }
                fn noneIsAbsent(): Boolean = Option<String?>.None().isSome() == false;
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(source)), className, packageName);

        try {
            deleteTree(Path.of("dist"));
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var test = loader.loadClass(className);
                assertEquals(42, test.getMethod("someValue").invoke(null));
                assertEquals(42, test.getMethod("noneValue").invoke(null));
                assertEquals(42, test.getMethod("mappedValue").invoke(null));
                assertEquals(42, test.getMethod("filteredValue").invoke(null));
                assertEquals(-1, test.getMethod("rejectedValue").invoke(null));
                assertEquals(true, test.getMethod("someNullIsPresent").invoke(null));
                assertEquals(true, test.getMethod("noneIsAbsent").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void resultPreservesSuccessAndErrorAcrossCombinators() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "resultClient" + suffix;
        final var className = packageName + ".ResultTest" + suffix;
        final var source = parse("ResultTest.zn", """
                package %s;
                import zeron.lang.Result;

                fn increment(value: Int): Result<Int, String> =
                    Result.ok<Int, String>(value + 1);
                fn success(): Result<Int, String> = Result.ok<Int, String>(41)
                    .map(value -> value + 1)
                    .andThen(value -> increment(value + 1));
                fn failure(): Result<Int, String> = Result.err<Int, String>("bad")
                    .map(value -> value + 1);
                fn mappedFailure(): Result<Int, Int> = Result.err<Int, String>("bad")
                    .mapError(error -> 3);
                fn successAfterErrorMap(): Result<Int, Int> =
                    Result.ok<Int, String>(42).mapError(error -> 3);
                fn mappedFailureHasMappedError(): Boolean =
                    mappedFailure().fold(value -> false, error -> error == 3);
                fn successAfterErrorMapPreservesValue(): Boolean =
                    successAfterErrorMap().fold(value -> value == 42, error -> false);
                fn foldedSuccess(): Int = success().fold(value -> value, error -> -1);
                fn foldedError(): Int = failure().fold(value -> value, error -> -1);
                fn matchedSuccess(): Int = match (success()) {
                    case Result.Ok(value) -> value;
                    case Result.Err(_) -> -1;
                };
                fn matchedError(): Boolean = match (failure()) {
                    case Result.Ok(_) -> false;
                    case Result.Err(error) -> error == "bad";
                };
                fn successIsOk(): Boolean = success().isOk() and not success().isErr();
                fn errorIsRetained(): Boolean = failure().isErr()
                    and failure().fold(value -> false, error -> error == "bad");
                fn errorShortCircuitsAndThen(): Boolean = failure()
                    .andThen(value -> increment(value + 1))
                    .fold(value -> false, error -> error == "bad");
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(source)), className, packageName);

        try {
            deleteTree(Path.of("dist"));
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var test = loader.loadClass(className);
                assertEquals(44, test.getMethod("foldedSuccess").invoke(null));
                assertEquals(-1, test.getMethod("foldedError").invoke(null));
                assertEquals(44, test.getMethod("matchedSuccess").invoke(null));
                assertEquals(true, test.getMethod("matchedError").invoke(null));
                assertEquals(true, test.getMethod("successIsOk").invoke(null));
                assertEquals(true, test.getMethod("errorIsRetained").invoke(null));
                assertEquals(true, test.getMethod("mappedFailureHasMappedError").invoke(null));
                assertEquals(true, test.getMethod("successAfterErrorMapPreservesValue").invoke(null));
                assertEquals(true, test.getMethod("errorShortCircuitsAndThen").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void standardCollectionsExposeTheirHigherKindedOperations() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "collectionHkt" + suffix;
        final var className = packageName + ".CollectionHktTest" + suffix;
        final var source = parse("CollectionHktTest.zn", """
                package %s;
                import zeron.collections.List;
                import zeron.collections.Stream;
                import zeron.control.Foldable;
                import zeron.control.Functor;
                import zeron.control.Monad;
                import zeron.lang.Option;
                import zeron.lang.Result;

                fn mapAll<F<_>: Functor<F>, A, B>(source: F<A>, transform: (A) -> B): F<B> =
                    source.map(transform);
                fn flatMapAll<F<_>: Monad<F>, A, B>(
                    source: F<A>, transform: (A) -> F<B>
                ): F<B> = source.flatMap(transform);
                fn applyAll<F<_>: Monad<F>, A, B>(
                    values: F<A>, functions: F<(A) -> B>
                ): F<B> = values.ap(functions);
                fn pureAll<F<_>: Monad<F>, A>(value: A): F<A> = F.pure(value);
                fn foldAll<F<_>: Foldable<F>>(values: F<Int>): Int =
                    values.foldLeft(0, (total, value) -> total + value);

                fn streamMap(): Int =
                    foldAll<Stream<_>>(
                        mapAll<Stream<_>, Int, Int>(Stream<Int>.of(1, 2, 3), value -> value + 1));
                fn streamFlatMap(): Int =
                    foldAll<Stream<_>>(flatMapAll<Stream<_>, Int, Int>(
                        Stream<Int>.of(1, 2), value -> Stream<Int>.of(value, value + 10)));
                fn streamApply(): Int =
                    foldAll<Stream<_>>(applyAll<Stream<_>, Int, Int>(
                        Stream<Int>.of(1, 2),
                        Stream<(Int) -> Int>.of(value -> value + 1, value -> value * 2)));
                fn streamPure(): Int = foldAll<Stream<_>>(pureAll<Stream<_>, Int>(7));

                fn optionMap(): Int =
                    foldAll<Option<_>>(
                        mapAll<Option<_>, Int, Int>(Option.Some(4), value -> value + 1));
                fn optionFlatMap(): Int =
                    foldAll<Option<_>>(flatMapAll<Option<_>, Int, Int>(
                        Option.Some(5), value -> Option.Some(value + 1)));
                fn optionApply(): Int {
                    let transform: (Int) -> Int = value -> value * 3;
                    return foldAll<Option<_>>(applyAll<Option<_>, Int, Int>(
                        Option.Some(4), Option.Some(transform)));
                }
                fn optionPure(): Int = foldAll<Option<_>>(pureAll<Option<_>, Int>(8));

                fn resultMap(): Int =
                    foldAll<Result<_, String>>(
                        mapAll<Result<_, String>, Int, Int>(
                            Result.ok<Int, String>(5), value -> value + 2));
                fn resultFlatMap(): Int =
                    foldAll<Result<_, String>>(flatMapAll<Result<_, String>, Int, Int>(
                        Result.ok<Int, String>(5), value -> Result.ok<Int, String>(value + 2)));
                fn resultApply(): Int =
                    foldAll<Result<_, String>>(applyAll<Result<_, String>, Int, Int>(
                        Result.ok<Int, String>(2),
                        Result.ok<(Int) -> Int, String>(value -> value + 10)));
                fn resultPure(): Int =
                    foldAll<Result<_, String>>(pureAll<Result<_, String>, Int>(9));
                fn resultErrorFold(): Int =
                    foldAll<Result<_, String>>(Result.err<Int, String>("bad"));

                fn listFold(): Int = List<Int>.of(1, 2, 3).foldLeft(0,
                    (total, value) -> total + value);
                fn listGenericFold(): Int =
                    foldAll<List<_>>(List<Int>.of(1, 2, 3));
                fn arrayFold(): Int {
                    let values: &Array<Int> = [4, 5, 6];
                    return values.foldLeft(0, (total, value) -> total + value);
                }
                fn arrayGenericFold(): Int {
                    let values: &Array<Int> = [4, 5, 6];
                    return foldAll<Array<_>>(values);
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(source)), className, packageName);

        try {
            deleteTree(Path.of("dist"));
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var test = loader.loadClass(className);
                assertEquals(9, test.getMethod("streamMap").invoke(null));
                assertEquals(26, test.getMethod("streamFlatMap").invoke(null));
                assertEquals(11, test.getMethod("streamApply").invoke(null));
                assertEquals(7, test.getMethod("streamPure").invoke(null));
                assertEquals(5, test.getMethod("optionMap").invoke(null));
                assertEquals(6, test.getMethod("optionFlatMap").invoke(null));
                assertEquals(12, test.getMethod("optionApply").invoke(null));
                assertEquals(8, test.getMethod("optionPure").invoke(null));
                assertEquals(7, test.getMethod("resultMap").invoke(null));
                assertEquals(7, test.getMethod("resultFlatMap").invoke(null));
                assertEquals(12, test.getMethod("resultApply").invoke(null));
                assertEquals(9, test.getMethod("resultPure").invoke(null));
                assertEquals(0, test.getMethod("resultErrorFold").invoke(null));
                assertEquals(6, test.getMethod("listFold").invoke(null));
                assertEquals(6, test.getMethod("listGenericFold").invoke(null));
                assertEquals(15, test.getMethod("arrayFold").invoke(null));
                assertEquals(15, test.getMethod("arrayGenericFold").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void compilesUserIterableAgainstBundledContracts() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "app.BundledIterableGenerated" + suffix;
        final var iteratorName = "BundledIterator" + suffix;
        final var iterableName = "BundledStream" + suffix;
        final var classFile = Path.of("dist", "app", className.substring("app.".length()) + ".class");
        final var iteratorFile = Path.of("dist", "app", iteratorName + ".class");
        final var iterableFile = Path.of("dist", "app", iterableName + ".class");
        final var source = """
            package app;

            import zeron.collections.Iterator;
            import zeron.collections.Iterable;
            import zeron.collections.Step;

            class %s is Iterator<Int> {
                value: Int;
                ready: Boolean;
                public constructor new;
                public mut next(): Step<Int> {
                    if (not this.ready) return Step<Int>.Done();
                    this.ready = false;
                    return Step.Yield(this.value);
                }
                }
                class %s is Iterable<Int> {
                    value: Int;
                    public constructor new;
                    public iterator(): &Iterator<Int> = %s.new(this.value, true);
                }
                fn total(): Int {
                    let mut result = 0;
                    for (let value in %s.new(9)) {
                        result += value;
                    }
                    return result;
                }
                """.formatted(iteratorName, iterableName, iteratorName, iterableName);
        final var userUnit = parse("bundled-iterable.zn", source);
        final var units = StandardLibrary.withBundledUnits(List.of(userUnit));

        try {
            final var compiler = CompilationService.forCompilationUnits(units, className, "app");
            compiler.resolve();
            compiler.compile();

                final var iteratorModel = ClassFile.of().parse(iteratorFile);
                final var nextMethod = iteratorModel.methods().stream()
                    .filter(method -> method.methodName().equalsString("next")
                        && method.methodType().equalsString("()Lzeron/collections/Step;"))
                    .findFirst()
                    .orElseThrow();
                assertTrue(nextMethod.code().orElseThrow().elementStream()
                    .filter(Instruction.class::isInstance)
                    .map(Instruction.class::cast)
                    .map(Instruction::opcode)
                    .anyMatch(opcode -> opcode == Opcode.GETSTATIC));

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(9, loader.loadClass(className).getMethod("total").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(iteratorFile);
            Files.deleteIfExists(iterableFile);
        }
    }

    @Test
    public void cliCompilesAliasedTypeImportAcrossPackages() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var appPackage = "app" + suffix;
        final var geometryPackage = "geometry" + suffix;
        final var mainName = "ImportMain" + suffix;
        final var sourceDirectory = Files.createTempDirectory(Path.of("target"), "import-cli-");
        final var appSource = sourceDirectory.resolve(mainName + ".zn");
        final var geometrySource = sourceDirectory.resolve("Point.zn");
        final var generatedMain = Path.of("dist", appPackage, mainName + ".class");
        final var generatedPoint = Path.of("dist", geometryPackage, "Point.class");

        Files.writeString(appSource, """
                package %s;
                import %s.Point as Dot;
                fn result(): Int = Dot.new(42).read();
                """.formatted(appPackage, geometryPackage));
        Files.writeString(geometrySource, """
                package %s;
                public class Point {
                    value: Int;
                    public constructor new;
                    public read(): Int = this.value;
                }
                """.formatted(geometryPackage));

        try {
            assertEquals(0, Zeron.runCli(appSource.toString(), geometrySource.toString()));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(appPackage + "." + mainName)
                        .getMethod("result").invoke(null));
            }
        } finally {
            Files.deleteIfExists(generatedMain);
            Files.deleteIfExists(generatedPoint);
            Files.deleteIfExists(appSource);
            Files.deleteIfExists(geometrySource);
            Files.deleteIfExists(sourceDirectory);
        }
    }

    @Test
    public void projectRootCompilesAndImportsPublicFunctions() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var appPackage = "app" + suffix;
        final var mathPackage = "math" + suffix;
        final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-project-");
        final var entryRelativePath = Path.of(appPackage, "Main.zn");
        final var entryFile = projectRoot.resolve(entryRelativePath);
        final var libraryFile = projectRoot.resolve(mathPackage).resolve("Math.zn");
        final var appOutput = Path.of("dist", appPackage);
        final var mathOutput = Path.of("dist", mathPackage);
        Files.createDirectories(entryFile.getParent());
        Files.createDirectories(libraryFile.getParent());
        Files.writeString(entryFile, """
                package %s;
                import %s.add as sum;
                fn result(): Int = sum(19, 23);
                """.formatted(appPackage, mathPackage));
        Files.writeString(libraryFile, """
                package %s;
                public fn add(left: Int, right: Int): Int = left + right;
                """.formatted(mathPackage));

        try {
            assertEquals(0, Zeron.runCli(
                    "--root", projectRoot.toString(), "--entry", entryRelativePath.toString()));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(appPackage + ".Main").getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(appOutput);
            deleteTree(mathOutput);
            deleteTree(projectRoot);
        }
    }

    @Test
    public void projectValuesImportAcrossRootsAndInitializeDependenciesFirst() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var appPackage = "app" + suffix;
        final var configPackage = "config" + suffix;
        final var appRoot = Files.createTempDirectory(Path.of("target"), "zeron-value-app-");
        final var configRoot = Files.createTempDirectory(Path.of("target"), "zeron-value-config-");
        final var entry = appRoot.resolve(appPackage).resolve("Main.zn");
        final var values = configRoot.resolve(configPackage).resolve("Values.zn");
        Files.createDirectories(entry.getParent());
        Files.createDirectories(values.getParent());
        Files.writeString(entry, """
                package %s;
                import %s.answer as importedAnswer;
                import %s.*;
                let resultValue = importedAnswer + 2;
                fn result(): Int = resultValue;
                fn starImportedValue(): Int = answer;
                """.formatted(appPackage, configPackage, configPackage));
        Files.writeString(values, """
                package %s;
                public let answer = 40;
                """.formatted(configPackage));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--root", appRoot.toString(),
                    "--root", configRoot.toString(), "--entry", Path.of(appPackage, "Main.zn").toString()));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(appPackage + ".Main").getMethod("result").invoke(null));
                assertEquals(40, loader.loadClass(appPackage + ".Main")
                        .getMethod("starImportedValue").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(appRoot);
            deleteTree(configRoot);
        }
    }

    @Test
    public void rejectsDirectAndFunctionMediatedProjectValueCycles() throws Exception {
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-value-cycle-");
        final var entry = sourceRoot.resolve("app").resolve("Main.zn");
        final var second = sourceRoot.resolve("app").resolve("Second.zn");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package app;
                let first: Int = readSecond();
                fn readSecond(): Int = second;
                fn result(): Int = first;
                """);
        Files.writeString(second, "package app; let second: Int = first;");

        try {
            assertTrue(Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of("app", "Main.zn").toString()) != 0);
            Files.writeString(entry, """
                    package app;
                    let first: Int = second;
                    fn result(): Int = first;
                    """);
            Files.writeString(second, "package app; let second: Int = first;");
            assertTrue(Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of("app", "Main.zn").toString()) != 0);
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(sourceRoot);
        }
    }

    @Test
    public void independentProjectValuesInitializeInStableSourceOrder() throws Exception {
        final var firstRoot = Files.createTempDirectory(Path.of("target"), "zeron-value-order-first-");
        final var secondRoot = Files.createTempDirectory(Path.of("target"), "zeron-value-order-second-");
        final var entry = firstRoot.resolve("app").resolve("Main.zn");
        final var second = secondRoot.resolve("app").resolve("Second.zn");
        Files.createDirectories(entry.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(entry, """
                package app;
                import zeron.io.println;
                let first: Int = markFirst();
                fn markFirst(): Int {
                    println("first");
                    return 1;
                }
                fn result(): Int = first + second;
                """);
        Files.writeString(second, """
                package app;
                import zeron.io.println;
                let second: Int = markSecond();
                fn markSecond(): Int {
                    println("second");
                    return 2;
                }
                """);

        try {
            assertEquals(0, Zeron.runCli("--root", firstRoot.toString(),
                    "--root", secondRoot.toString(), "--entry", Path.of("app", "Main.zn").toString()));
            final var output = new ByteArrayOutputStream();
            final var originalOutput = System.out;
            try (final var capture = new PrintStream(output, true, StandardCharsets.UTF_8);
                 final var loader = new URLClassLoader(
                         new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                System.setOut(capture);
                assertEquals(3, loader.loadClass("app.Main").getMethod("result").invoke(null));
            } finally {
                System.setOut(originalOutput);
            }
            assertEquals("first" + System.lineSeparator() + "second" + System.lineSeparator(),
                    output.toString(StandardCharsets.UTF_8));
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(firstRoot);
            deleteTree(secondRoot);
        }
    }

    @Test
    public void initializerFailurePreventsEntryMainFromRunning() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "initializerFailure" + suffix;
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-value-failure-");
        final var entry = sourceRoot.resolve(packageName).resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package %s;
                import zeron.io.println;
                let broken: Int = 1 / 0;
                fn main(): Unit {
                    println("main");
                }
                """.formatted(packageName));

        try {
            assertEquals(0, Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of(packageName, "Main.zn").toString()));
            final var output = new ByteArrayOutputStream();
            final var originalOutput = System.out;
            try (final var capture = new PrintStream(output, true, StandardCharsets.UTF_8);
                 final var loader = new URLClassLoader(
                         new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                System.setOut(capture);
                final var mainClass = loader.loadClass(packageName + ".Main");
                final var failure = assertThrows(ExceptionInInitializerError.class,
                        () -> mainClass.getMethod("main", String[].class).invoke(null, (Object) new String[0]));
                assertTrue(failure.getCause() instanceof ArithmeticException);
            } finally {
                System.setOut(originalOutput);
            }
            assertEquals("", output.toString(StandardCharsets.UTF_8));
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(sourceRoot);
        }
    }

    @Test
    public void dynamicReadBeforeInitializationFailsInsteadOfReturningDefault() throws Exception {
        final var packageName = "dynamicInitialization";
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-value-dynamic-");
        final var entry = sourceRoot.resolve(packageName).resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package %s;
                let first: Int = reader();
                let reader = () -> second;
                let second: Int = first;
                fn result(): Int = first;
                """.formatted(packageName));

        try {
            assertEquals(0, Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of(packageName, "Main.zn").toString()));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var mainClass = loader.loadClass(packageName + ".Main");
                final var failure = assertThrows(ExceptionInInitializerError.class,
                        () -> mainClass.getMethod("result").invoke(null));
                assertTrue(failure.getCause() instanceof IllegalStateException);
                assertTrue(failure.getCause().getMessage().contains("reader"));
            }
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(sourceRoot);
        }
    }

    @Test
    public void projectValuesRequireInitializersAndPublicMutabilityIsRejected() throws Exception {
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-value-validation-");
        final var entry = sourceRoot.resolve("app").resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "package app; let value: Int?;");

        try {
            assertTrue(Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of("app", "Main.zn").toString()) != 0);
            Files.writeString(entry, "package app; public let mut value: Int = 1;");
            assertTrue(Zeron.runCli("--root", sourceRoot.toString(),
                    "--entry", Path.of("app", "Main.zn").toString()) != 0);
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(sourceRoot);
        }
    }

    @Test
    public void projectRootRejectsPackageDirectoryMismatch() throws Exception {
        final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-package-path-");
        final var entry = projectRoot.resolve("app").resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package wrong;
                fn result(): Int = 42;
                """);

        try {
            assertTrue(Zeron.runCli("--root", projectRoot.toString(),
                    "--entry", Path.of("app", "Main.zn").toString()) != 0);
        } finally {
            deleteTree(projectRoot);
            deleteTree(Path.of("dist", "wrong"));
        }
    }

    @Test
    public void starImportsExposePublicTypesAndFunctionsAndExplicitImportsWin() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var appPackage = "starApp" + suffix;
        final var geometryPackage = "starGeometry" + suffix;
        final var mathPackage = "starMath" + suffix;
        final var projectRoot = Files.createTempDirectory(Path.of("target"), "zeron-star-import-");
        final var entryRelative = Path.of(appPackage, "Main.zn");
        final var entry = projectRoot.resolve(entryRelative);
        final var pointFile = projectRoot.resolve(geometryPackage).resolve("Point.zn");
        final var mathFile = projectRoot.resolve(mathPackage).resolve("Functions.zn");
        Files.createDirectories(entry.getParent());
        Files.createDirectories(pointFile.getParent());
        Files.createDirectories(mathFile.getParent());
        Files.writeString(entry, """
                package %s;
                import %s.*;
                import %s.*;
                import %s.score;
                fn result(): Int {
                    let point: Point = Point.new(40);
                    return point.value() + add(1, 1) + score();
                }
                """.formatted(appPackage, geometryPackage, mathPackage, mathPackage));
        Files.writeString(pointFile, """
                package %s;
                public class Point {
                    amount: Int;
                    public constructor new;
                    public value(): Int = this.amount;
                }
                """.formatted(geometryPackage));
        Files.writeString(mathFile, """
                package %s;
                public fn add(left: Int, right: Int): Int = left + right;
                public fn score(): Int = 1;
                """.formatted(mathPackage));

        try {
            assertEquals(0, Zeron.runCli("--root", projectRoot.toString(),
                    "--entry", entryRelative.toString()));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(43, loader.loadClass(appPackage + ".Main").getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist", appPackage));
            deleteTree(Path.of("dist", geometryPackage));
            deleteTree(Path.of("dist", mathPackage));
            deleteTree(projectRoot);
        }
    }

    @Test
    public void ambiguousStarImportedFunctionsRequireExplicitImport() {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var consumerPackage = "ambiguousConsumer" + suffix;
        final var firstPackage = "ambiguousFirst" + suffix;
        final var secondPackage = "ambiguousSecond" + suffix;
        final var consumer = parse("Main.zn", """
                package %s;
                import %s.*;
                import %s.*;
                fn result(): Int = score();
                """.formatted(consumerPackage, firstPackage, secondPackage));
        final var first = parse("First.zn", """
                package %s;
                public fn score(): Int = 1;
                """.formatted(firstPackage));
        final var second = parse("Second.zn", """
                package %s;
                public fn score(): Int = 2;
                """.formatted(secondPackage));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(consumer, first, second)),
                consumerPackage + ".Main", consumerPackage);

        assertThrows(ResolutionError.class, compiler::resolve);
    }

    @Test
    public void explicitTypeImportDisambiguatesStarImportedType() {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var consumerPackage = "typeImportConsumer" + suffix;
        final var firstPackage = "typeImportFirst" + suffix;
        final var secondPackage = "typeImportSecond" + suffix;
        final var consumer = parse("Main.zn", """
                package %s;
                import %s.*;
                import %s.*;
                import %s.Point as SelectedPoint;
                fn result(point: SelectedPoint): Int = point.read();
                """.formatted(consumerPackage, firstPackage, secondPackage, secondPackage));
        final var first = parse("First.zn", """
                package %s;
                public class Point { public read(): Int = 1; }
                """.formatted(firstPackage));
        final var second = parse("Second.zn", """
                package %s;
                public class Point { public read(): Int = 2; }
                """.formatted(secondPackage));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(consumer, first, second)),
                consumerPackage + ".Main", consumerPackage);

        compiler.resolve();
    }

    @Test
    public void ambiguousStarImportedTypesRequireExplicitImport() {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var consumerPackage = "ambiguousTypeConsumer" + suffix;
        final var firstPackage = "ambiguousTypeFirst" + suffix;
        final var secondPackage = "ambiguousTypeSecond" + suffix;
        final var consumer = parse("Main.zn", """
                package %s;
                import %s.*;
                import %s.*;
                fn result(point: Point): Int = point.read();
                """.formatted(consumerPackage, firstPackage, secondPackage));
        final var first = parse("First.zn", """
                package %s;
                public class Point { public read(): Int = 1; }
                """.formatted(firstPackage));
        final var second = parse("Second.zn", """
                package %s;
                public class Point { public read(): Int = 2; }
                """.formatted(secondPackage));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(consumer, first, second)),
                consumerPackage + ".Main", consumerPackage);

        assertThrows(ResolutionError.class, compiler::resolve);
    }

    @Test
    public void forExpressionsCreateLazyStreamsAndCollectDirectly() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "forExpressionClient" + suffix;
        final var className = packageName + ".ForExpressionTest" + suffix;
        final var source = parse("ForExpressionTest.zn", """
                package %s;
                import zeron.collections.List;
                import zeron.collections.Stream;
                import zeron.collections.Step;
                class Calls {
                    value: Int;
                    public constructor new;
                    public mut record(item: Int): Int {
                        this.value = this.value + 1;
                        return item;
                    }
                    public read(): Int = this.value;
                }
                fn lazyArrayPipeline(): Int {
                    let calls = Calls.new(0);
                    let values: Stream<Int> = for item in [1, 2, 3]
                        if item > 1
                        yield calls.record(item);
                    let before = calls.read();
                    let total = values.fold(0, (sum, item) -> sum + item);
                    return before * 100 + total * 10 + calls.read();
                }
                fn collectedArrayPipeline(): Int {
                    let values = for item in [1, 2, 3, 4]
                        if item %% 2 == 0
                        collect item * 3 into List<Int>.empty();
                    return values.size * 100 + values.at(0).getOrElse(-1) + values.at(1).getOrElse(-1);
                }
                fn collectedArrayPipelineSize(): Int {
                    let values = for item in [1, 2, 3, 4]
                        if item %% 2 == 0
                        collect item * 3 into List<Int>.empty();

                    return values.size;
                }
                fn collectedArrayPipelineFirst(): Int {
                    let values = for item in [1, 2, 3, 4]
                        if item %% 2 == 0
                        collect item * 3 into List<Int>.empty();

                    return values.at(0).getOrElse(-1);
                }
                fn collectedArrayPipelineSecond(): Int {
                    let values = for item in [1, 2, 3, 4]
                        if item %% 2 == 0
                        collect item * 3 into List<Int>.empty();

                    return values.at(1).getOrElse(-1);
                }
                fn collectedArrayPipelineReversed(): Int {
                    let values = for item in [1, 2, 3, 4]
                        if item %% 2 == 0
                        collect item * 3 into List<Int>.empty();
                    return values.at(1).getOrElse(-1)
                        + values.at(0).getOrElse(-1) * 100;
                }
                fn collectedFirstElementTimes100(): Int {
                    let values = for item in [1, 2, 3, 4]
                        if item %% 2 == 0
                        collect item * 3 into List<Int>.empty();
                    return values.at(0).getOrElse(-1) * 100;
                }
                fn collectedArrayWithoutFilter(): Int {
                    let values = for item in [1, 2, 3, 4]
                        collect item into List<Int>.empty();
                    return values.size * 1000
                        + values.at(0).getOrElse(-1) * 100
                        + values.at(1).getOrElse(-1) * 10
                        + values.at(2).getOrElse(-1);
                }
                fn collectedArrayWithFilter(): Int {
                    let values = for item in [1, 2, 3, 4]
                        if item %% 2 == 0
                        collect item into List<Int>.empty();
                    return values.size * 100 + values.at(0).getOrElse(-1) * 10
                        + values.at(1).getOrElse(-1);
                }
                fn collectedArrayWithMap(): Int {
                    let values = for item in [1, 2, 3, 4]
                        collect item * 3 into List<Int>.empty();
                    return values.size * 100 + values.at(0).getOrElse(-1) * 10
                        + values.at(1).getOrElse(-1);
                }
                fn collectedArrayFilterThenMap(): Int {
                    let values = for item in [1, 2, 3, 4]
                        if item %% 2 == 0
                        collect item * 3 into List<Int>.empty();
                    return values.size * 100 + values.at(0).getOrElse(-1) * 10
                        + values.at(1).getOrElse(-1);
                }
                fn collectedArrayPipelineAlternativeFilter(): Int {
                    let values = for item in [1, 2, 3, 4]
                        if item == 2 or item == 4
                        collect item * 3 into List<Int>.empty();
                    return values.size * 100 + values.at(0).getOrElse(-1) * 10
                        + values.at(1).getOrElse(-1);
                }
                fn mappedFilteredStream(): Int {
                    let stream = Stream<Int>.fromArray([1, 2, 3, 4])
                        .filter(item -> item %% 2 == 0)
                        .map(item -> item * 3);

                    let iterator = stream.iterator();
                    let first = iterator.next();
                    let second = iterator.next();

                    return match (first) {
                        case Step.Yield(a) ->
                            match (second) {
                                case Step.Yield(b) -> a * 100 + b;
                                case Step.Done() -> -1;
                            };
                        case Step.Done() -> -2;
                    };
                }
                fn mappedFilteredFold(): Int {
                    let stream = Stream<Int>.fromArray([1, 2, 3, 4])
                        .filter(item -> item %% 2 == 0)
                        .map(item -> item * 3);

                    return stream.fold(0, (acc, item) -> acc * 100 + item);
                }
                fn directListAccess(): Int {
                    let values = List<Int>.of(6, 12);
                    return values.size * 100
                        + values.at(0).getOrElse(-1)
                        + values.at(1).getOrElse(-1);
                }
                fn collectedIterablePipeline(): Int {
                    let values = for item in Stream<Int>.fromArray([2, 5])
                        collect item + 1 into List<Int>.empty();
                    return values.at(0).getOrElse(-1) * 10 + values.at(1).getOrElse(-1);
                }
                fn collectedCapturePipeline(factor: Int): Int {
                    let values = for item in [1, 2, 3]
                        let scaled = item * factor
                        if scaled > factor
                        for expanded in List<Int>.of(scaled, scaled + factor)
                        collect expanded into List<Int>.empty();
                    return values.size * 100 + values.at(0).getOrElse(-1) + values.at(1).getOrElse(-1)
                        + values.at(2).getOrElse(-1) + values.at(3).getOrElse(-1);
                }
                fn lazyFlatMapPipeline(): Int {
                    let calls = Calls.new(0);
                    let values = for item in [1, 2, 3]
                        for output in List<Int>.of(calls.record(item), calls.record(item + 10))
                        yield output;
                    let before = calls.read();
                    let sum = values.fold(0, (total, item) -> total + item);
                    return before * 1000 + calls.read() * 100 + sum;
                }
                fn collectedFlatMapPipeline(): Int {
                    let values = for item in [1, 2, 3]
                        for output in List<Int>.of(item, item + 1)
                        if output %% 2 == 0
                        collect output into List<Int>.empty();
                    return values.size * 100 + values.at(0).getOrElse(-1) + values.at(1).getOrElse(-1) + values.at(2).getOrElse(-1);
                }
                fn nestedFlatMapPipeline(): Int {
                    let values = for outer in [1, 2]
                        for middle in List<Int>.of(outer, outer + 1)
                        for inner in List<Int>.of(middle * 10, middle * 100)
                        collect inner into List<Int>.empty();
                    return values.size * 1000 + values.at(0).getOrElse(-1) + values.at(1).getOrElse(-1) + values.at(7).getOrElse(-1);
                }
                fn lazyLimitAndWhilePipeline(): Int {
                    let values: Stream<Int> = (for item in [1, 2, 3, 4, 5, 6, 7]
                        yield item).drop(1).take(5)
                            .dropWhile((item) -> item < 3)
                            .takeWhile((item) -> item < 6);
                    return values.fold(0, (sum, item) -> sum * 10 + item);
                }
                fn collectedLimitAndWhilePipeline(): Int {
                    let values = (for item in [1, 2, 3, 4, 5, 6, 7, 8]
                        yield item).drop(1).take(6)
                            .dropWhile((item) -> item < 4)
                            .takeWhile((item) -> item < 7).toList();
                    return values.size * 100 + values.at(0).getOrElse(-1) * 10 + values.at(1).getOrElse(-1) + values.at(2).getOrElse(-1);
                }
                fn collectedLimitsAfterFlatMap(): Int {
                    let values = (for item in [1, 2, 3]
                        for output in List<Int>.of(item, item * 10)
                        yield output).drop(2).take(3).toList();
                    return values.size * 1000 + values.at(0).getOrElse(-1) * 100 + values.at(1).getOrElse(-1) * 10 + values.at(2).getOrElse(-1);
                }
                fn collectedTakeStopsUpstream(): Int {
                    let calls = Calls.new(0);
                    let values = (for item in [1, 2, 3, 4]
                        yield calls.record(item)).take(2).toList();
                    return calls.read() * 100 + values.size * 10 + values.at(0).getOrElse(-1) + values.at(1).getOrElse(-1);
                }
                fn collectedTakeWithFilteredDownstream(): Int {
                    let calls = Calls.new(0);
                    let values = (for item in [1, 2, 3, 4]
                        yield calls.record(item)).take(2)
                            .filter((item) -> item > 2).toList();
                    return calls.read() * 100 + values.size;
                }
                fn collectedTakeBeforeFlatMap(): Int {
                    let values = (for item in [1, 2, 3]
                        for output in [item, item * 10]
                        yield output).take(4).toList();
                    return values.size * 100 + values.at(0).getOrElse(-1) + values.at(1).getOrElse(-1)
                        + values.at(2).getOrElse(-1) + values.at(3).getOrElse(-1);
                }
                fn collectedTakeWhileStopsUpstream(): Int {
                    let calls = Calls.new(0);
                    let values = (for item in [1, 2, 3, 4]
                        yield calls.record(item))
                            .takeWhile((item) -> item < 3).toList();
                    return calls.read() * 100 + values.size * 10 + values.at(0).getOrElse(-1) + values.at(1).getOrElse(-1);
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(List.of(source), className, packageName);

        try {
            deleteTree(Path.of("dist"));
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var test = loader.loadClass(className);
                assertEquals(52, test.getMethod("lazyArrayPipeline").invoke(null));
                assertEquals(218, test.getMethod("collectedArrayPipeline").invoke(null));
                assertEquals(2, test.getMethod("collectedArrayPipelineSize").invoke(null));
                assertEquals(6, test.getMethod("collectedArrayPipelineFirst").invoke(null));
                assertEquals(12, test.getMethod("collectedArrayPipelineSecond").invoke(null));
                assertEquals(612, test.getMethod("collectedArrayPipelineReversed").invoke(null));
                assertEquals(600, test.getMethod("collectedFirstElementTimes100").invoke(null));
                assertEquals(218, test.getMethod("directListAccess").invoke(null));
                assertEquals(4123, test.getMethod("collectedArrayWithoutFilter").invoke(null));
                assertEquals(436, test.getMethod("collectedArrayWithMap").invoke(null));
                assertEquals(272, test.getMethod("collectedArrayFilterThenMap").invoke(null));
                assertEquals(272, test.getMethod("collectedArrayPipelineAlternativeFilter").invoke(null));
                assertEquals(224, test.getMethod("collectedArrayWithFilter").invoke(null));
                assertEquals(612, test.getMethod("mappedFilteredStream").invoke(null));
                assertEquals(612, test.getMethod("mappedFilteredFold").invoke(null));
                assertEquals(36, test.getMethod("collectedIterablePipeline").invoke(null));
                assertEquals(424, test.getMethod("collectedCapturePipeline", int.class).invoke(null, 2));
                assertEquals(642, test.getMethod("lazyFlatMapPipeline").invoke(null));
                assertEquals(308, test.getMethod("collectedFlatMapPipeline").invoke(null));
                assertEquals(8410, test.getMethod("nestedFlatMapPipeline").invoke(null));
                assertEquals(345, test.getMethod("lazyLimitAndWhilePipeline").invoke(null));
                assertEquals(351, test.getMethod("collectedLimitAndWhilePipeline").invoke(null));
                assertEquals(3403, test.getMethod("collectedLimitsAfterFlatMap").invoke(null));
                assertEquals(223, test.getMethod("collectedTakeStopsUpstream").invoke(null));
                assertEquals(200, test.getMethod("collectedTakeWithFilteredDownstream").invoke(null));
                assertEquals(433, test.getMethod("collectedTakeBeforeFlatMap").invoke(null));
                assertEquals(323, test.getMethod("collectedTakeWhileStopsUpstream").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void forExpressionCollectRequiresMutableSink() {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "immutableForSink" + suffix;
        final var source = parse("Main.zn", """
                package %s;
                import zeron.collections.List;
                fn collect(sink: List<Int>): List<Int> =
                    for item in [1, 2] collect item into sink;
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(source)),
                packageName + ".Main", packageName);

        assertThrows(ResolutionError.class, compiler::resolve);
    }

    @Test
    public void lazyStreamOperatorsComposeAndSupportOrdinaryIteration() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "sequenceClient" + suffix;
        final var className = packageName + ".StreamTest" + suffix;
        final var source = parse("StreamTest.zn", """
                package %s;
                import zeron.collections.Stream;
                import zeron.internal.generateArray;
                class Total {
                    value: Int;
                    public constructor new;
                    public mut add(next: Int): Unit { this.value = this.value + next; }
                    public mut record(value: Int): Int {
                        this.value = this.value + 1;
                        return value;
                    }
                    public mut recordIndex(index: Int): Int {
                        this.value = this.value * 10 + index;
                        return this.value;
                    }
                    public read(): Int = this.value;
                }
                class Trace {
                    value: Int;
                    public constructor new;
                    public mut map(value: Int): Int {
                        this.value = this.value * 10 + 1;
                        return value;
                    }
                    public mut keep(value: Int): Boolean {
                        this.value = this.value * 10 + 2;
                        return value >= 2;
                    }
                    public read(): Int = this.value;
                }
                fn transformed(): Int {
                    let values = Stream<Int>.fromArray([1, 2, 3, 4, 5, 6])
                        .map(value -> value * 2)
                        .filter(value -> value > 4)
                        .drop(1)
                        .take(2);
                    let mut total = 0;
                    for (let value in values) total += value;
                    return total;
                }
                fn folded(): Int = Stream<Int>.fromArray([1, 2, 3, 4]).fold(0,
                    (total, value) -> total + value);
                fn predicates(): Boolean {
                    let values = Stream<Int>.fromArray([2, 4, 6]);
                    return values.any(value -> value == 4) and values.all(value -> value > 0);
                }
                fn count(): Int = Stream<Int>.fromArray([1, 2, 3]).filter(value -> value > 1).count();
                fn whileOperators(): Int {
                    return Stream<Int>.fromArray([1, 2, 3, 4, 5, 6])
                        .dropWhile(value -> value < 3)
                        .takeWhile(value -> value < 6)
                        .fold(0, (sum, value) -> sum + value);
                }
                fn forEachTotal(): Int {
                    let total = Total.new(0);
                    Stream<Int>.fromArray([3, 5, 7]).forEach(value -> total.add(value));
                    return total.read();
                }
                fn lazyEvaluation(): Int {
                    let calls = Total.new(0);
                    let values = Stream<Int>.fromArray([4, 5, 6])
                        .map(value -> calls.record(value));
                    let beforeConsumption = calls.read();
                    let first = values.take(1).fold(0, (sum, value) -> sum + value);
                    return beforeConsumption * 100 + first * 10 + calls.read();
                }
                fn emptyStreamPredicates(): Boolean {
                    let values = Stream<Int>.fromArray([1, 2, 3]).filter(value -> false);
                    return values.count() == 0 and not values.any(value -> true)
                        and values.all(value -> false);
                }
                fn lazyCallbackOrder(): Int {
                    let trace = Trace.new(0);
                    let values = Stream<Int>.fromArray([1, 2, 3])
                        .map(value -> trace.map(value))
                        .filter(value -> trace.keep(value))
                        .take(1);
                    let beforeConsumption = trace.read();
                    let total = values.fold(0, (sum, value) -> sum + value);
                    return beforeConsumption * 100000 + trace.read() * 10 + total;
                }
                fn zeroAndNegativeCounts(): Int {
                    let values = Stream<Int>.fromArray([1, 2, 3]);
                    return values.take(0).count() * 1000
                        + values.take(-1).count() * 100
                        + values.drop(0).count() * 10
                        + values.drop(-1).count();
                }
                fn repeatedStreamTraversal(): Int {
                    let values = Stream<Int>.fromArray([2, 3]);
                    return values.count() * 10 + values.count();
                }
                fn generatedArray(): Int {
                    let calls = Total.new(0);
                    let values = generateArray<Int>(4, index -> calls.recordIndex(index));
                    let mut total = 0;
                    for (let value in values) total += value;
                    return calls.read() * 1000 + total;
                }
                fn generatedEmptyArray(): Int {
                    let calls = Total.new(0);
                    let values = generateArray<Int>(0, index -> calls.record(index));
                    return calls.read() + values.length;
                }
                fn contextuallyEmptyArray(): Int {
                    let values: &Array<Int> = [];
                    let throughParameter = arrayLength([]);
                    return values.length + throughParameter + Stream<Int>.fromArray([]).count();
                }
                fn arrayLength(values: Array<Int>): Int = values.length;
                fn emptyArrayIteration(): Int {
                    let values: &Array<Int> = [];
                    let mut total = 0;
                    for (let value in values) total += value;
                    return total;
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(List.of(source), className, packageName);

        try {
            deleteTree(Path.of("dist"));
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var test = loader.loadClass(className);
                assertEquals(18, test.getMethod("transformed").invoke(null));
                assertEquals(10, test.getMethod("folded").invoke(null));
                assertEquals(true, test.getMethod("predicates").invoke(null));
                assertEquals(2, test.getMethod("count").invoke(null));
                assertEquals(12, test.getMethod("whileOperators").invoke(null));
                assertEquals(15, test.getMethod("forEachTotal").invoke(null));
                assertEquals(41, test.getMethod("lazyEvaluation").invoke(null));
                assertEquals(true, test.getMethod("emptyStreamPredicates").invoke(null));
                assertEquals(12122, test.getMethod("lazyCallbackOrder").invoke(null));
                assertEquals(33, test.getMethod("zeroAndNegativeCounts").invoke(null));
                assertEquals(22, test.getMethod("repeatedStreamTraversal").invoke(null));
                assertEquals(123136, test.getMethod("generatedArray").invoke(null));
                assertEquals(0, test.getMethod("generatedEmptyArray").invoke(null));
                assertEquals(0, test.getMethod("contextuallyEmptyArray").invoke(null));
                assertEquals(0, test.getMethod("emptyArrayIteration").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void arrayBackedListMutatesGrowsAndProjectsToReadOnlyView() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "listClient" + suffix;
        final var className = packageName + ".ListTest" + suffix;
        final var source = parse("ListTest.zn", """
                package %s;
                import zeron.collections.List;
                fn result(): Int {
                    let mut values: &List<Int> = List<Int>.empty();
                    values.add(2);
                    values.add(4);
                    values.add(6);
                    values.add(8);
                    values.add(10);
                    values.insert(1, 3);
                    let replaced = values.replaceAt(2, 5);
                    let removed = values.removeAt(4);
                    let readonly: List<Int> = values;
                    let mut total = 0;
                    for (let value in readonly) total += value;
                    return total + replaced.getOrElse(-1) + removed.getOrElse(-1) + readonly.size;
                }
                fn empty(): Boolean = List<Int>.empty().isEmpty();
                fn copied(): Int {
                    let mut values: &List<Int> = List<Int>.fromArray([7, 8, 9]);
                    values.add(10);
                    values.clear();
                    values.add(11);
                    return values.at(0).getOrElse(-1) + values.size;
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(List.of(source), className, packageName);

        try {
            deleteTree(Path.of("dist"));
            compiler.resolve();
            compiler.compile();
            assertFalse(Files.exists(Path.of("dist", "zeron", "collections", "ListEntry.class")));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var test = loader.loadClass(className);
                assertEquals(43, test.getMethod("result").invoke(null));
                assertEquals(true, test.getMethod("empty").invoke(null));
                assertEquals(12, test.getMethod("copied").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void listMutationRequiresMutableReferenceView() {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "readonlyList" + suffix;
        final var source = parse("ReadOnlyList.zn", """
                package %s;
                import zeron.collections.List;
                fn invalid(values: List<Int>): Unit {
                    values.add(1);
                }
                """.formatted(packageName));

        assertThrows(ResolutionError.class, () -> CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(source)),
                packageName + ".ReadOnlyList", packageName).resolve());
    }

    @Test
    public void cliLoadsCompiledZeronLibraryDirectory() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "compiled" + suffix;
        final var appPackage = "client" + suffix;
        final var libraryRoot = Files.createTempDirectory(Path.of("target"), "zeron-library-");
        final var clientSource = Files.createTempFile(Path.of("target"), "LibraryClient", ".zn");
        final var libraryUnit = parse("Math.zn", """
                package %s;
                public fn identity<T>(value: T): T = value;
                public fn add(base: Int, amount: Int = 7): Int = base + amount;
                public contract Incrementer {
                    increment(value: Int, amount: Int = value + 2): Int;
                }
                public class Counter is Incrementer {
                    public increment(value: Int, amount: Int): Int = value + amount;
                }
                public class LocalCounter {
                    public increment(value: Int, amount: Int = 3): Int = value + amount;
                }
                public fn counter(): Counter = Counter.new();
                """.formatted(libraryPackage));

        try {
            deleteTree(Path.of("dist"));
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(libraryUnit), libraryPackage + ".LibraryBuilder", libraryPackage);
            libraryCompiler.resolve();
            libraryCompiler.compile();

            copyTree(Path.of("dist", libraryPackage), libraryRoot.resolve(libraryPackage));
            final var libraryIndex = libraryRoot.resolve(ZeronVersions.API_INDEX_RELATIVE_PATH);
            Files.createDirectories(libraryIndex.getParent());
            Files.copy(Path.of("dist").resolve(ZeronVersions.API_INDEX_RELATIVE_PATH), libraryIndex);

            deleteTree(Path.of("dist"));
            Files.writeString(clientSource, """
                    package %s;
                    import %s.identity as identity;
                    import %s.add;
                    import %s.Counter;
                    import %s.LocalCounter;
                    import %s.counter;
                    fn result(): Int = identity<Int>(63) + add(10);
                    fn contractCall(): Int = counter().increment(10);
                    fn classCall(): Int = Counter.new().increment(10);
                    fn defaultMethodCall(): Int = LocalCounter.new().increment(10);
                    """.formatted(appPackage, libraryPackage, libraryPackage, libraryPackage,
                            libraryPackage, libraryPackage));
            assertEquals(0, Zeron.runCli(clientSource.toString(), "--library", libraryRoot.toString()));

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), libraryRoot.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(appPackage + "." +
                        clientSource.getFileName().toString().replaceFirst("\\.zn$", ""));
                assertEquals(80, main.getMethod("result").invoke(null));
                assertEquals(22, main.getMethod("contractCall").invoke(null));
                assertEquals(22, main.getMethod("classCall").invoke(null));
                assertEquals(13, main.getMethod("defaultMethodCall").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(libraryRoot);
            Files.deleteIfExists(clientSource);
        }
    }

    @Test
    public void cliPackagesAndLoadsZeronLibraryJar() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "jarlibrary" + suffix;
        final var appPackage = "jarclient" + suffix;
        final var librarySource = Files.createTempFile(Path.of("target"), "ZeronJarLibrary" + suffix, ".zn");
        final var clientSource = Files.createTempFile(Path.of("target"), "ZeronJarClient" + suffix, ".zn");
        final var libraryJar = Path.of("target", "zeron-library-" + suffix + ".jar");
        final var secondLibraryJar = Path.of("target", "zeron-library-repeat-" + suffix + ".jar");
        final var clientClass = appPackage + "." + clientSource.getFileName().toString().replaceFirst("\\.zn$", "");
        Files.writeString(librarySource, """
                package %s;
                public class Answer {
                    value: Int;
                    public constructor new;
                    public read(): Int = this.value;
                }
                public fn answer(): Int = Answer.new(42).read();
                """.formatted(libraryPackage));
        Files.writeString(clientSource, """
                package %s;
                import %s.answer;
                fn result(): Int = answer();
                """.formatted(appPackage, libraryPackage));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli(librarySource.toString(), "--jar-output", libraryJar.toString()));
            assertFalse(Files.exists(Path.of("dist")));
            assertEquals(0, Zeron.runCli(librarySource.toString(), "--jar-output", secondLibraryJar.toString()));
            assertArrayEquals(Files.readAllBytes(libraryJar), Files.readAllBytes(secondLibraryJar));
            try (final var jar = new JarFile(libraryJar.toFile())) {
                assertTrue(jar.getJarEntry(ZeronVersions.API_INDEX_JAR_ENTRY) != null);
                assertTrue(jar.stream().anyMatch(entry -> entry.getName().equals(
                        libraryPackage.replace('.', '/') + "/Answer.class")));
            }

            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli(clientSource.toString(), "--library", libraryJar.toString()));
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), libraryJar.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(clientClass).getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
            Files.deleteIfExists(librarySource);
            Files.deleteIfExists(clientSource);
            Files.deleteIfExists(libraryJar);
            Files.deleteIfExists(secondLibraryJar);
        }
    }

    @Test
    public void buildsBundledStandardLibraryAndUsesItWithoutSourceInjection() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var appPackage = "compiledStdlibClient" + suffix;
        final var libraryOutput = Path.of("target", "zeron-stdlib-" + suffix);
        final var libraryJar = Path.of("target", "zeron-stdlib-" + suffix + ".jar");
        final var sourceFile = Path.of("target", "CompiledStdlibClient" + suffix + ".zn");
        final var entryName = sourceFile.getFileName().toString().replaceFirst("\\.zn$", "");
        final var apiIndex = libraryOutput.resolve(ZeronVersions.API_INDEX_RELATIVE_PATH);
        final var iterableClass = libraryOutput.resolve(Path.of("zeron", "collections", "Iterable.class"));
        final var iteratorClass = libraryOutput.resolve(Path.of("zeron", "collections", "Iterator.class"));
        final var arrayIteratorClass = libraryOutput.resolve(
                Path.of("zeron", "collections", "ArrayIterator.class"));
        final var stepClass = libraryOutput.resolve(Path.of("zeron", "collections", "Step.class"));
        final var rangeClass = libraryOutput.resolve(Path.of("zeron", "ranges", "IntRange.class"));
        final var cellClass = libraryOutput.resolve(Path.of("zeron", "lang", "Cell.class"));
        final var unitClass = libraryOutput.resolve(Path.of("zeron", "lang", "Unit.class"));
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package %s;
                import zeron.collections.Iterable;
                import zeron.collections.ArrayIterator;
                import zeron.collections.Step;
                import zeron.lang.Cell;
                import zeron.numeric.Fractional;
                import zeron.numeric.Integral;
                import zeron.numeric.Numeric;
                import zeron.numeric.Floating;
                import zeron.numeric.Ratio;
                import zeron.io.print;
                import zeron.io.println;
                fn cellResult(): Int = Cell<Int>.new(42).get();
                fn sum(values: Iterable<Int>): Int {
                    let mut total = 0;
                    for (let value in values) total += value;
                    return total;
                }
                fn result(): Int = sum(1..3);
                fn arrayIteratorResult(): Int {
                    let iterator = ArrayIterator<Int>.from([2, 3, 5]);
                    let mut total = 0;
                    let mut done = false;
                    while (not done) {
                        let item = iterator.next();
                        if (item matches Step.Yield(value)) {
                            total += value;
                        } else {
                            done = true;
                        }
                    }
                    return total;
                }
                fn nullableArrayIteratorResult(): Int {
                    let nullValue: String? = null;
                    let values: Array<String?> = [nullValue];
                    let iterator = ArrayIterator<String?>.from(values);
                    let first = iterator.next();
                    if (first matches Step.Yield(value)) {
                        if (value === null) {
                            let second = iterator.next();
                            if (second matches Step.Done()) return 1;
                        }
                    }
                    return 0;
                }
                fn add<T: mut + Numeric<T>>(left: T, right: T): T = left + right;
                fn subtract<T: mut + Numeric<T>>(left: T, right: T): T = left - right;
                fn multiply<T: mut + Numeric<T>>(left: T, right: T): T = left * right;
                fn reciprocal<T: mut + Fractional<T>>(value: T): T = value.reciprocal();
                fn numericResult(): Int = subtract(add(20, 24), 2);
                fn reciprocalResult(): Float = reciprocal(2.0);
                fn announce(): Unit {
                    print("compiled");
                    println("!");
                }
                """.formatted(appPackage));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--build-stdlib", libraryOutput.toString(),
                    "--jar-output", libraryJar.toString()));
            assertTrue(Files.exists(apiIndex));
            assertTrue(Files.exists(iterableClass));
            assertTrue(Files.exists(iteratorClass));
            assertTrue(Files.exists(arrayIteratorClass));
            assertTrue(Files.exists(stepClass));
            assertTrue(Files.exists(rangeClass));
            assertTrue(Files.exists(cellClass));
            assertTrue(Files.exists(unitClass));
            final var standardLibraryIndex = ZeronLibraryIndex.readFrom(apiIndex);
            assertTrue(standardLibraryIndex.declarations().stream()
                    .filter(ZeronLibraryIndex.ClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ClassExport.class::cast)
                    .anyMatch(export -> export.qualifiedName().equals("zeron.lang.Cell")
                            && export.methods().stream().anyMatch(method -> method.name().equals("get"))
                            && export.methods().stream().anyMatch(method -> method.name().equals("set"))));
            assertTrue(standardLibraryIndex.declarations().stream()
                    .filter(ZeronLibraryIndex.ContractExport.class::isInstance)
                    .map(ZeronLibraryIndex.ContractExport.class::cast)
                    .map(ZeronLibraryIndex.ContractExport::qualifiedName)
                    .toList()
                    .containsAll(List.of("zeron.numeric.Ratio", "zeron.numeric.Numeric",
                            "zeron.numeric.Integral", "zeron.numeric.Fractional",
                            "zeron.numeric.Floating")));
            try (final var jar = new JarFile(libraryJar.toFile())) {
                assertTrue(jar.getJarEntry(ZeronVersions.API_INDEX_JAR_ENTRY) != null);
                assertTrue(jar.getJarEntry("zeron/collections/Stream.class") != null);
                assertTrue(jar.getJarEntry("zeron/collections/Step.class") != null);
                assertTrue(jar.getJarEntry("zeron/lang/Unit.class") != null);
            }

            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli(sourceFile.toString(), "--stdlib", "compiled",
                    "--library", libraryJar.toString()));
            final var output = new ByteArrayOutputStream();
            final var originalOutput = System.out;
            try (final var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                System.setOut(capture);
                try (final var loader = new URLClassLoader(new java.net.URL[]{
                        Path.of("dist").toUri().toURL(), libraryJar.toUri().toURL()}, getClass().getClassLoader())) {
                    final var client = loader.loadClass(appPackage + "." + entryName);
                    assertEquals(42, client.getMethod("cellResult").invoke(null));
                    assertEquals(6, client.getMethod("result").invoke(null));
                    assertEquals(10, client.getMethod("arrayIteratorResult").invoke(null));
                    assertEquals(1, client.getMethod("nullableArrayIteratorResult").invoke(null));
                    assertEquals(42, client.getMethod("numericResult").invoke(null));
                    assertEquals(0.5, client.getMethod("reciprocalResult").invoke(null));
                    client.getMethod("announce").invoke(null);
                } finally {
                    System.setOut(originalOutput);
                }
            }
            assertEquals("compiled!" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(libraryOutput);
            Files.deleteIfExists(libraryJar);
            Files.deleteIfExists(sourceFile);
        }
    }

    @Test
    public void cliCallsPublicJavaMethodsFromClassDirectory() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "javaClient" + suffix;
        final var sourceFile = Files.createTempFile(Path.of("target"), "JavaClassPathClient", ".zn");
        final var className = packageName + "." + sourceFile.getFileName().toString().replaceFirst("\\.zn$", "");
        final var classFile = Path.of("dist", packageName, className.substring(packageName.length() + 1) + ".class");
        Files.writeString(sourceFile, """
                package %s;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn result(): Int = Fixture.add(19, 23);
                """.formatted(packageName));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli(
                    sourceFile.toString(), "--java-classpath", "target/test-classes"));
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), Path.of("target", "test-classes").toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(className).getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
            Files.deleteIfExists(sourceFile);
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void intRangeStopsAtInclusiveIntegerBounds() throws Exception {
        final var sourcePath = Path.of("src", "main", "resources", "stdlib",
                "zeron", "ranges", "intrange.zn");
        final var rangeClass = "zeron.ranges.IntRange";
        final var mainClass = "zeron.ranges.IntRangeValidation";
        final var rangeFile = Path.of("dist", "zeron", "ranges", "IntRange.class");
        final var iteratorFile = Path.of("dist", "zeron", "ranges", "IntIterator.class");
        final var mainFile = Path.of("dist", "zeron", "ranges", "IntRangeValidation.class");
        final var sourceUnit = Parser.of(Scanner.from(Files.readString(sourcePath)).scanTokens())
                .parseCompilationUnit(sourcePath.toString());
        final var compiler = CompilationService.forCompilationUnits(
                List.of(sourceUnit), mainClass, "zeron.ranges");

        try {
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var intRange = loader.loadClass(rangeClass);
                final var ascending = intRange.getMethod("ascending", int.class, int.class)
                        .invoke(null, Integer.MAX_VALUE - 1, Integer.MAX_VALUE);
                assertEquals(List.of(Integer.MAX_VALUE - 1, Integer.MAX_VALUE), values(ascending));
                final var descending = intRange.getMethod("descending", int.class, int.class)
                        .invoke(null, Integer.MIN_VALUE + 1, Integer.MIN_VALUE);
                assertEquals(List.of(Integer.MIN_VALUE + 1, Integer.MIN_VALUE), values(descending));
            }
        } finally {
            Files.deleteIfExists(rangeFile);
            Files.deleteIfExists(iteratorFile);
            Files.deleteIfExists(mainFile);
        }
    }

    private static List<Integer> values(final Object iterable) throws Exception {
        final var iterator = iterable.getClass().getMethod("iterator").invoke(iterable);
        final var next = iterator.getClass().getMethod("next");
        final var values = new ArrayList<Integer>();
        for (;;) {
            final var step = next.invoke(iterator);
            if (!step.getClass().getName().endsWith("Step$$sum$Yield")) break;
            values.add((Integer) step.getClass().getMethod("$zeron$payload$0").invoke(step));
        }
        return values;
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static void copyTree(final Path source, final Path target) throws Exception {
        try (final var paths = Files.walk(source)) {
            for (final var path : paths.toList()) {
                final var destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(sourcePath);
    }
}
