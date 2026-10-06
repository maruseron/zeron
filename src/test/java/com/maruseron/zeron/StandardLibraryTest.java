package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.compile.CompilationService;
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
    public void matchExhaustivelySelectsOptionCasesAndEvaluatesScrutineeOnce() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "matchFixture" + suffix;
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-match-");
        final var entry = sourceRoot.resolve(packageName).resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package %s;
                import zeron.lang.Option;
                import zeron.lang.Some;
                import zeron.lang.None;
                let mut calls = 0;
                fn next(): Option<Int> {
                    calls += 1;
                    return Some<Int>.from(42);
                }
                fn someResult(): Int = match (next()) {
                    case Some<Int> as some -> some.value;
                    case None<Int> -> -1;
                };
                fn noneResult(): Int {
                    let value: Option<Int> = None<Int>.none();
                    return match (value) {
                        case Some<Int> as some -> some.value;
                        case None<Int> -> -1;
                    };
                }
                fn wildcardResult(): Int {
                    let value: Option<Int> = None<Int>.none();
                    return match (value) {
                        case Some<Int> as some -> some.value;
                        case _ -> 7;
                    };
                }
                fn acceptsExternalNull(value: Option<Int>): Int = match (value) {
                    case Some<Int> as some -> some.value;
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
    public void matchRejectsMissingDuplicateAndUnreachableCases() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "invalidMatch" + suffix;
        final var sourceRoot = Files.createTempDirectory(Path.of("target"), "zeron-match-invalid-");
        final var entry = sourceRoot.resolve(packageName).resolve("Main.zn");
        Files.createDirectories(entry.getParent());
        final var header = """
                package %s;
                import zeron.lang.Option;
                import zeron.lang.Some;
                import zeron.lang.None;
                fn result(value: Option<Int>): Int =
                """.formatted(packageName);

        try {
            for (final var match : List.of(
                    "match (value) { case Some<Int> -> 1; }",
                    "match (value) { case Some<Int> -> 1; case Some<Int> -> 2; case None<Int> -> 3; }",
                    "match (value) { case _ -> 1; case Some<Int> -> 2; }",
                    "match (value) { case Some<Int> -> 1; case None<Int> -> 2; case _ -> 3; }",
                    "match (value) { case Option<Int> -> 1; }",
                    "match (value) { case Some<String> -> 1; case None<Int> -> 2; }")) {
                Files.writeString(entry, header + match + ";");
                deleteTree(Path.of("dist"));
                assertTrue(Zeron.runCli("--root", sourceRoot.toString(),
                        "--entry", Path.of(packageName, "Main.zn").toString()) != 0);
            }
            Files.writeString(entry, """
                    package %s;
                    import zeron.lang.Option;
                    import zeron.lang.Some;
                    import zeron.lang.None;
                    fn result(value: Option<Int>?): Int = match (value) {
                        case Some<Int> -> 1;
                        case None<Int> -> 2;
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
                    new String(index.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
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
    public void cliRunsGeneratedClassAndBuildsMissingStandardLibraryJar() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "runClient" + suffix;
        final var className = packageName + ".Main";
        final var classFile = Path.of("dist", packageName, "Main.class");
        final var standardLibraryJar = Path.of("target", "zeron-stdlib-run-" + suffix + ".jar");
        final var source = parse("Main.zn", """
                package %s;
                import zeron.io.println;
                fn main(): Unit {
                    println("run command works");
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(source)), className, packageName);

        try {
            deleteTree(Path.of("dist"));
            compiler.resolve();
            compiler.compile();
            assertFalse(Files.exists(standardLibraryJar));
            assertEquals(0, Zeron.runCli("--run-class", classFile.toString(),
                    "--stdlib-jar", standardLibraryJar.toString()));
            assertTrue(Files.exists(standardLibraryJar));
        } finally {
            deleteTree(Path.of("dist"));
            Files.deleteIfExists(standardLibraryJar);
        }
    }

    @Test
    public void optionDistinguishesSomeNullableValueFromNone() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "optionClient" + suffix;
        final var className = packageName + ".OptionTest" + suffix;
        final var source = parse("OptionTest.zn", """
                package %s;
                import zeron.lang.Option;
                import zeron.lang.Some;

                fn someValue(): Int = Option.some(41).fold(value -> value + 1, () -> 0);
                fn noneValue(): Int = Option.none<Int>().fold(value -> value, () -> 42);
                fn someNullIsPresent(): Boolean {
                    let option: Option<String?> = Some<String?>.from(null);
                    return option.isSome()
                        and option.fold(value -> value == null, () -> false);
                }
                fn noneIsAbsent(): Boolean = Option.none<String?>().isSome() == false;
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
                import zeron.lang.Ok;
                import zeron.lang.Err;

                fn increment(value: Int): Result<Int, String> =
                    Ok<Int, String>.from(value + 1);
                fn success(): Result<Int, String> = Ok<Int, String>.from(41)
                    .map(value -> value + 1)
                    .andThen(value -> increment(value + 1));
                fn failure(): Result<Int, String> = Err<Int, String>.from("bad")
                    .map(value -> value + 1);
                fn mappedFailure(): Result<Int, Int> = Err<Int, String>.from("bad")
                    .mapError(error -> 3);
                fn successAfterErrorMap(): Result<Int, Int> =
                    Ok<Int, String>.from(42).mapError(error -> 3);
                fn foldedSuccess(): Int = success().fold(value -> value, error -> -1);
                fn foldedError(): Int = failure().fold(value -> value, error -> -1);
                fn matchedSuccess(): Int = match (success()) {
                    case Ok<Int, String> as ok -> ok.value;
                    case Err<Int, String> -> -1;
                };
                fn matchedError(): Boolean = match (failure()) {
                    case Ok<Int, String> -> false;
                    case Err<Int, String> as err -> err.error == "bad";
                };
                fn successIsOk(): Boolean = success().isOk() and not success().isErr();
                fn errorIsRetained(): Boolean = failure().isErr()
                    and failure().fold(value -> false, error -> error == "bad");
                fn errorShortCircuitsAndThen(): Result<Int, String> = failure()
                    .andThen(value -> increment(value + 1));
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
                final var mappedFailure = test.getMethod("mappedFailure").invoke(null);
                assertEquals(3, mappedFailure.getClass().getMethod("$zeron$get$error").invoke(mappedFailure));
                final var successAfterErrorMap = test.getMethod("successAfterErrorMap").invoke(null);
                assertEquals(42, successAfterErrorMap.getClass().getMethod("$zeron$get$value")
                        .invoke(successAfterErrorMap));
                final var errorShortCircuited = test.getMethod("errorShortCircuitsAndThen").invoke(null);
                assertEquals("bad", errorShortCircuited.getClass().getMethod("$zeron$get$error")
                        .invoke(errorShortCircuited));
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
        final var iterableName = "BundledSequence" + suffix;
        final var classFile = Path.of("dist", "app", className.substring("app.".length()) + ".class");
        final var iteratorFile = Path.of("dist", "app", iteratorName + ".class");
        final var iterableFile = Path.of("dist", "app", iterableName + ".class");
        final var source = """
            package app;

            import zeron.collections.Iterator;
            import zeron.collections.Iterable;
            import zeron.lang.Option;
            import zeron.lang.Some;
            import zeron.lang.None;

            class %s is Iterator<Int> {
                value: Int;
                ready: Boolean;
                public constructor new;
                public mut next(): Option<Int> {
                    if (not this.ready) return None<Int>.none();
                    this.ready = false;
                    return Some<Int>.from(this.value);
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
                        && method.methodType().equalsString("()Lzeron/lang/Option;"))
                    .findFirst()
                    .orElseThrow();
                assertFalse(nextMethod.code().orElseThrow().elementStream()
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
    public void lazySequenceOperatorsComposeAndSupportOrdinaryIteration() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "sequenceClient" + suffix;
        final var className = packageName + ".SequenceTest" + suffix;
        final var source = parse("SequenceTest.zn", """
                package %s;
                import zeron.collections.Sequence;
                import zeron.collections.generateArray;
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
                    let values = Sequence<Int>.fromArray([1, 2, 3, 4, 5, 6])
                        .map(value -> value * 2)
                        .filter(value -> value > 4)
                        .drop(1)
                        .take(2);
                    let mut total = 0;
                    for (let value in values) total += value;
                    return total;
                }
                fn folded(): Int = Sequence<Int>.fromArray([1, 2, 3, 4]).fold(0,
                    (total, value) -> total + value);
                fn predicates(): Boolean {
                    let values = Sequence<Int>.fromArray([2, 4, 6]);
                    return values.any(value -> value == 4) and values.all(value -> value > 0);
                }
                fn count(): Int = Sequence<Int>.fromArray([1, 2, 3]).filter(value -> value > 1).count();
                fn forEachTotal(): Int {
                    let total = Total.new(0);
                    Sequence<Int>.fromArray([3, 5, 7]).forEach(value -> total.add(value));
                    return total.read();
                }
                fn lazyEvaluation(): Int {
                    let calls = Total.new(0);
                    let values = Sequence<Int>.fromArray([4, 5, 6])
                        .map(value -> calls.record(value));
                    let beforeConsumption = calls.read();
                    let first = values.take(1).fold(0, (sum, value) -> sum + value);
                    return beforeConsumption * 100 + first * 10 + calls.read();
                }
                fn emptySequencePredicates(): Boolean {
                    let values = Sequence<Int>.fromArray([1, 2, 3]).filter(value -> false);
                    return values.count() == 0 and not values.any(value -> true)
                        and values.all(value -> false);
                }
                fn lazyCallbackOrder(): Int {
                    let trace = Trace.new(0);
                    let values = Sequence<Int>.fromArray([1, 2, 3])
                        .map(value -> trace.map(value))
                        .filter(value -> trace.keep(value))
                        .take(1);
                    let beforeConsumption = trace.read();
                    let total = values.fold(0, (sum, value) -> sum + value);
                    return beforeConsumption * 100000 + trace.read() * 10 + total;
                }
                fn zeroAndNegativeCounts(): Int {
                    let values = Sequence<Int>.fromArray([1, 2, 3]);
                    return values.take(0).count() * 1000
                        + values.take(-1).count() * 100
                        + values.drop(0).count() * 10
                        + values.drop(-1).count();
                }
                fn repeatedSequenceTraversal(): Int {
                    let values = Sequence<Int>.fromArray([2, 3]);
                    return values.count() * 10 + values.count();
                }
                fn nullableSequenceElements(): Int {
                    let values = Sequence<String?>.fromArray(["a", null, "b"]);
                    return values.count();
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
                    return values.length + throughParameter + Sequence<Int>.fromArray([]).count();
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
                assertEquals(15, test.getMethod("forEachTotal").invoke(null));
                assertEquals(41, test.getMethod("lazyEvaluation").invoke(null));
                assertEquals(true, test.getMethod("emptySequencePredicates").invoke(null));
                assertEquals(12122, test.getMethod("lazyCallbackOrder").invoke(null));
                assertEquals(33, test.getMethod("zeroAndNegativeCounts").invoke(null));
                assertEquals(22, test.getMethod("repeatedSequenceTraversal").invoke(null));
                assertEquals(3, test.getMethod("nullableSequenceElements").invoke(null));
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
                    return total + replaced + removed + readonly.size;
                }
                fn empty(): Boolean = List<Int>.empty().isEmpty();
                fn copied(): Int {
                    let mut values: &List<Int> = List<Int>.fromArray([7, 8, 9]);
                    values.add(10);
                    values.clear();
                    values.add(11);
                    return values.at(0) + values.size;
                }
                fn nullableElements(): Boolean {
                    let mut values: &List<String?> = List<String?>.empty();
                    values.add(null);
                    values.add("present");
                    let mut sawNull = false;
                    let mut iterated = 0;
                    for (let value in values) {
                        if (value == null) sawNull = true;
                        iterated += 1;
                    }
                    return (values.at(0) ?? "missing") == "missing"
                        and (values.at(1) ?? "missing") == "present"
                        and sawNull
                        and iterated == 2;
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
                assertEquals(true, test.getMethod("nullableElements").invoke(null));
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
            final var libraryIndex = libraryRoot.resolve(Path.of("META-INF", "zeron", "api-v12.bin"));
            Files.createDirectories(libraryIndex.getParent());
            Files.copy(Path.of("dist", "META-INF", "zeron", "api-v12.bin"), libraryIndex);

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
                assertTrue(jar.getJarEntry("META-INF/zeron/api-v12.bin") != null);
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
        final var apiIndex = libraryOutput.resolve(Path.of("META-INF", "zeron", "api-v12.bin"));
        final var iterableClass = libraryOutput.resolve(Path.of("zeron", "collections", "Iterable.class"));
        final var iteratorClass = libraryOutput.resolve(Path.of("zeron", "collections", "Iterator.class"));
        final var arrayIteratorClass = libraryOutput.resolve(
                Path.of("zeron", "collections", "ArrayIterator.class"));
        final var rangeClass = libraryOutput.resolve(Path.of("zeron", "ranges", "IntRange.class"));
        final var unitClass = libraryOutput.resolve(Path.of("zeron", "lang", "Unit.class"));
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package %s;
                import zeron.collections.Iterable;
                import zeron.collections.ArrayIterator;
                import zeron.io.print;
                import zeron.io.println;
                fn sum(values: Iterable<Int>): Int {
                    let mut total = 0;
                    for (let value in values) total += value;
                    return total;
                }
                fn result(): Int = sum(1..3);
                fn arrayIteratorResult(): Int {
                    let iterator = ArrayIterator<Int>.from([2, 3, 5]);
                    let mut total = 0;
                    let mut item = iterator.next();
                    while (item.isSome()) {
                        total += item.fold(value -> value, () -> 0);
                        item = iterator.next();
                    }
                    return total;
                }
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
            assertTrue(Files.exists(rangeClass));
            assertTrue(Files.exists(unitClass));
            try (final var jar = new JarFile(libraryJar.toFile())) {
                assertTrue(jar.getJarEntry("META-INF/zeron/api-v12.bin") != null);
                assertTrue(jar.getJarEntry("zeron/collections/Sequence.class") != null);
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
                    assertEquals(6, client.getMethod("result").invoke(null));
                    assertEquals(10, client.getMethod("arrayIteratorResult").invoke(null));
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
            final var option = next.invoke(iterator);
            if (!(boolean) option.getClass().getMethod("isSome").invoke(option)) break;
            values.add((Integer) option.getClass().getMethod("$zeron$get$value").invoke(option));
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
