package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class StandardLibraryTest {
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
                    public hasNext(): Boolean = this.ready;
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
            Zeron.main(new String[]{appSource.toString(), geometrySource.toString()});
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
            Zeron.main(new String[]{"--root", projectRoot.toString(), "--entry", entryRelativePath.toString()});
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

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(sourcePath);
    }
}
