package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.compile.Compiler;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class StandardLibraryTest {

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
    public void bundledPrintFunctionsUseRegisteredJvmBindings() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "ioClient" + suffix;
        final var classSimpleName = "PrintFunctions" + suffix;
        final var className = packageName + "." + classSimpleName;
        final var outputDirectory = Path.of("dist", packageName);
        final var source = parse("PrintFunctions.zn", """
                package %s;
                import zeron.io.print;
                import zeron.io.println;
                fn emit(): Unit {
                    print("a");
                    print(42);
                    println("b");
                    println(null);
                }
                """.formatted(packageName));
        final var compiler = Compiler.forCompilationUnits(
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
            assertEquals("a42b" + System.lineSeparator() + "null" + System.lineSeparator(),
                    output.toString(StandardCharsets.UTF_8));
        } finally {
            deleteTree(outputDirectory);
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

                class %s is Iterator<Int> {
                    value: Int;
                    ready: Boolean;
                    public constructor new;
                    public mut hasNext(): Boolean = this.ready;
                    public mut next(): Int {
                        this.ready = false;
                        return this.value;
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
        final var units = List.of(userUnit, StandardLibrary.iterationUnit());

        try {
            final var compiler = Compiler.forCompilationUnits(units, className, "app");
            compiler.resolve();
            compiler.compile();

                final var iteratorModel = ClassFile.of().parse(iteratorFile);
                final var nextMethod = iteratorModel.methods().stream()
                    .filter(method -> method.methodName().equalsString("next")
                        && method.methodType().equalsString("()I"))
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
    public void lazySequenceOperatorsComposeAndSupportOrdinaryIteration() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "sequenceClient" + suffix;
        final var className = packageName + ".SequenceTest" + suffix;
        final var source = parse("SequenceTest.zn", """
                package %s;
                import zeron.collections.Sequence;
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
                """.formatted(packageName));
        final var compiler = Compiler.forCompilationUnits(List.of(source), className, packageName);

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
                    return total + replaced + removed + readonly.size();
                }
                fn empty(): Boolean = List<Int>.empty().isEmpty();
                fn copied(): Int {
                    let mut values: &List<Int> = List<Int>.fromArray([7, 8, 9]);
                    values.add(10);
                    values.clear();
                    values.add(11);
                    return values.at(0) + values.size();
                }
                fn nullableElements(): Boolean {
                    let mut values: &List<String?> = List<String?>.empty();
                    values.add(null);
                    values.add("present");
                    return (values.at(0) ?? "missing") == "missing"
                        and (values.at(1) ?? "missing") == "present";
                }
                """.formatted(packageName));
        final var compiler = Compiler.forCompilationUnits(List.of(source), className, packageName);

        try {
            deleteTree(Path.of("dist"));
            compiler.resolve();
            compiler.compile();
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

        assertThrows(ResolutionError.class, () -> Compiler.forCompilationUnits(
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
                """.formatted(libraryPackage));

        try {
            deleteTree(Path.of("dist"));
            final var libraryCompiler = Compiler.forCompilationUnits(
                    List.of(libraryUnit), libraryPackage + ".LibraryBuilder", libraryPackage);
            libraryCompiler.resolve();
            libraryCompiler.compile();

            copyTree(Path.of("dist", libraryPackage), libraryRoot.resolve(libraryPackage));
            final var libraryIndex = libraryRoot.resolve(Path.of("META-INF", "zeron", "api-v4.bin"));
            Files.createDirectories(libraryIndex.getParent());
            Files.copy(Path.of("dist", "META-INF", "zeron", "api-v4.bin"), libraryIndex);

            deleteTree(Path.of("dist"));
            Files.writeString(clientSource, """
                    package %s;
                    import %s.identity as identity;
                    fn result(): Int = identity<Int>(63);
                    """.formatted(appPackage, libraryPackage));
            assertEquals(0, Zeron.runCli(clientSource.toString(), "--library", libraryRoot.toString()));

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), libraryRoot.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(63, loader.loadClass(appPackage + "." +
                        clientSource.getFileName().toString().replaceFirst("\\.zn$", ""))
                        .getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(libraryRoot);
            Files.deleteIfExists(clientSource);
        }
    }

    @Test
    public void buildsBundledStandardLibraryAndUsesItWithoutSourceInjection() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var appPackage = "compiledStdlibClient" + suffix;
        final var libraryOutput = Path.of("target", "zeron-stdlib-" + suffix);
        final var sourceFile = Path.of("target", "CompiledStdlibClient" + suffix + ".zn");
        final var entryName = sourceFile.getFileName().toString().replaceFirst("\\.zn$", "");
        final var apiIndex = libraryOutput.resolve(Path.of("META-INF", "zeron", "api-v4.bin"));
        final var iterableClass = libraryOutput.resolve(Path.of("zeron", "collections", "Iterable.class"));
        final var iteratorClass = libraryOutput.resolve(Path.of("zeron", "collections", "Iterator.class"));
        final var arrayIteratorClass = libraryOutput.resolve(
                Path.of("zeron", "collections", "ArrayIterator.class"));
        final var rangeClass = libraryOutput.resolve(Path.of("zeron", "ranges", "IntRange.class"));
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
                    while (iterator.hasNext()) total += iterator.next();
                    return total;
                }
                fn announce(): Unit {
                    print("compiled");
                    println("!");
                }
                """.formatted(appPackage));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--build-stdlib", libraryOutput.toString()));
            assertTrue(Files.exists(apiIndex));
            assertTrue(Files.exists(iterableClass));
            assertTrue(Files.exists(iteratorClass));
            assertTrue(Files.exists(arrayIteratorClass));
            assertTrue(Files.exists(rangeClass));

            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli(sourceFile.toString(), "--stdlib", "compiled",
                    "--library", libraryOutput.toString()));
            final var output = new ByteArrayOutputStream();
            final var originalOutput = System.out;
            try (final var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                System.setOut(capture);
                try (final var loader = new URLClassLoader(new java.net.URL[]{
                        Path.of("dist").toUri().toURL(), libraryOutput.toUri().toURL()}, getClass().getClassLoader())) {
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
        final var sourcePath = Path.of("src", "main", "resources", "stdlib", "intrange.zn");
        final var rangeClass = "zeron.ranges.IntRange";
        final var mainClass = "zeron.ranges.IntRangeValidation";
        final var rangeFile = Path.of("dist", "zeron", "ranges", "IntRange.class");
        final var iteratorFile = Path.of("dist", "zeron", "ranges", "IntIterator.class");
        final var mainFile = Path.of("dist", "zeron", "ranges", "IntRangeValidation.class");
        final var sourceUnit = Parser.of(Scanner.from(Files.readString(sourcePath)).scanTokens())
                .parseCompilationUnit(sourcePath.toString());
        final var compiler = Compiler.forCompilationUnits(
                List.of(sourceUnit, StandardLibrary.iterationUnit()), mainClass, "zeron.ranges");

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
        final var hasNext = iterator.getClass().getMethod("hasNext");
        final var next = iterator.getClass().getMethod("next");
        final var values = new ArrayList<Integer>();
        while ((boolean) hasNext.invoke(iterator)) values.add((Integer) next.invoke(iterator));
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
