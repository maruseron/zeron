package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;
import zeron.runtime.AppliedTypeDescriptor;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class RuntimeReificationTest {
    @Test
    public void passesConstantDescriptorsToReifiedTopLevelFunctions() throws Exception {
        final var packageName = "reifiedTopLevel" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-reified-top-level-");
        final var unit = parse("Main.zn", """
                package %s;
                public fn identity<T: reified>(value: T): T = value;
                fn chained<T: reified>(value: T): T = identity(value);
                fn callInt(): Int = identity(42);
                fn callString(): String = identity("reified");
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(42, main.getMethod("callInt").invoke(null));
                assertEquals("reified", main.getMethod("callString").invoke(null));

                final var identity = main.getMethod("identity", Object.class,
                        AppliedTypeDescriptor.class);
                assertEquals(7, identity.invoke(null, 7,
                        AppliedTypeDescriptor.of("zeron.lang.Int")));
                assertTrue(main.getMethod("chained", Object.class,
                        AppliedTypeDescriptor.class).getParameterTypes()[1]
                        .equals(AppliedTypeDescriptor.class));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void composesDescriptorsForNestedApplications() throws Exception {
        final var packageName = "reifiedNested" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-reified-nested-");
        final var unit = parse("Main.zn", """
                package %s;
                public fn identity<T: reified>(value: T): T = value;
                public fn relay<T: reified>(value: Array<T>): Array<T> = identity(value);
                fn use(): Int = relay([5])[0];
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(5, loader.loadClass(packageName + ".Main")
                        .getMethod("use").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void capturesDescriptorsForReifiedFunctionValues() throws Exception {
        final var packageName = "reifiedValue" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-reified-value-");
        final var unit = parse("Main.zn", """
                package %s;
                public fn identity<T: reified>(value: T): T = value;
                fn callExplicit(): Int {
                    let operation: (Int) -> Int = identity::<Int>;
                    return operation(12);
                }
                fn callInferred(): String {
                    let operation: (String) -> String = identity;
                    return operation("value");
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(12, main.getMethod("callExplicit").invoke(null));
                assertEquals("value", main.getMethod("callInferred").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void passesDescriptorsToReifiedMethods() throws Exception {
        final var packageName = "reifiedMethod" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-reified-method-");
        final var unit = parse("Main.zn", """
                package %s;
                public class Box {
                    public property value: Int;
                    public constructor new;
                    public echo<T: reified>(value: T): T = value;
                }
                fn callMethod(): Int = Box.new(0).echo(9);
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                final var box = loader.loadClass(packageName + ".Box");
                assertEquals(9, main.getMethod("callMethod").invoke(null));
                final var echo = box.getMethod("echo", Object.class, AppliedTypeDescriptor.class);
                final var instance = box.getConstructor(int.class).newInstance(0);
                assertEquals("value", echo.invoke(instance, "value",
                        AppliedTypeDescriptor.of("zeron.lang.String")));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void infersReifiedMarkersForNonExportedFunctions() throws Exception {
        final var packageName = "reifiedInferred" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-reified-inferred-");
        final var unit = parse("Main.zn", """
                package %s;
                public fn identity<T: reified>(value: T): T = value;
                fn outer<T>(value: T): T = inner(value);
                fn inner<T>(value: T): T = identity(value);
                fn use(): Int = outer(21);
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(21, main.getMethod("use").invoke(null));
                assertEquals(4, main.getMethod("outer", Object.class, AppliedTypeDescriptor.class)
                        .invoke(null, 4, AppliedTypeDescriptor.of("zeron.lang.Int")));
                assertEquals("inner", main.getMethod("inner", Object.class, AppliedTypeDescriptor.class)
                        .invoke(null, "inner", AppliedTypeDescriptor.of("zeron.lang.String")));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void preservesReifiedMarkersAcrossSeparateCompilation() throws Exception {
        final var suffix = suffix();
        final var libraryPackage = "reifiedLibrary" + suffix;
        final var clientPackage = "reifiedClient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-reified-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-reified-client-");
        final var library = parse("Library.zn", """
                package %s;
                public fn identity<T: reified>(value: T): T = value;
                """.formatted(libraryPackage));
        final var client = parse("Main.zn", """
                package %s;
                import %s.identity;
                fn use(): Int = identity(5);
                """.formatted(clientPackage, libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(library), libraryPackage + ".Library", libraryPackage,
                    List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();

            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var function = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(libraryPackage + ".identity"))
                    .findFirst().orElseThrow();
            final TypeParameterDescriptor parameter = function.signature().typeParameters().getFirst();
            assertTrue(parameter.isReified());

            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), clientPackage + ".Main", clientPackage,
                    List.of(index), false, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals(5, loader.loadClass(clientPackage + ".Main")
                        .getMethod("use").invoke(null));
            }
        } finally {
            deleteTree(libraryOutput);
            deleteTree(clientOutput);
        }
    }

    @Test
    public void preservesDescriptorsAcrossContractMethods() throws Exception {
        final var packageName = "reifiedContract" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-reified-contract-");
        final var unit = parse("Main.zn", """
                package %s;
                public contract Echo {
                    echo<T: reified>(value: T): T;
                }
                public class Box is Echo {
                    public constructor new;
                    public echo<T: reified>(value: T): T = value;
                }
                fn use(): String = Box.new().echo("contract");
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals("contract", loader.loadClass(packageName + ".Main")
                        .getMethod("use").invoke(null));
                final var box = loader.loadClass(packageName + ".Box");
                final var instance = box.getConstructor().newInstance();
                assertEquals("value", box.getMethod("echo", Object.class, AppliedTypeDescriptor.class)
                        .invoke(instance, "value", AppliedTypeDescriptor.of("zeron.lang.String")));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsContractMarkerMismatch() throws Exception {
        final var packageName = "reifiedContractMismatch" + suffix();
        final var output = Path.of("target", "zeron-reified-contract-mismatch-" + packageName);
        final var unit = parse("Main.zn", """
                package %s;
                public contract Echo {
                    echo<T: reified>(value: T): T;
                }
                public class Box is Echo {
                    public constructor new;
                    public echo<T>(value: T): T = value;
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(unit)),
                packageName + ".Main", packageName, List.of(), false, List.of(), output);
        assertThrows(ResolutionError.class, compiler::resolve);
    }

    @Test
    public void rejectsUnmarkedExportedForwarding() throws Exception {
        final var packageName = "reifiedExported" + suffix();
        final var output = Path.of("target", "zeron-reified-exported-" + packageName);
        final var unit = parse("Main.zn", """
                package %s;
                public fn identity<T: reified>(value: T): T = value;
                public fn relay<T>(value: T): T = identity(value);
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(unit)),
                packageName + ".Main", packageName, List.of(), false, List.of(), output);
        final var error = assertThrows(ResolutionError.class, compiler::resolve);
        assertTrue(error.getMessage().contains("mark it 'reified'"));
    }

    @Test
    public void rejectsReifiedClassTypeParameters() throws Exception {
        final var packageName = "reifiedClass" + suffix();
        final var output = Path.of("target", "zeron-reified-class-" + packageName);
        final var unit = parse("Main.zn", """
                package %s;
                public class Box<T: reified> {
                    public property value: T;
                    public constructor new;
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(unit)),
                packageName + ".Main", packageName, List.of(), false, List.of(), output);
        final var error = assertThrows(ResolutionError.class, compiler::resolve);
        assertTrue(error.getMessage().contains("cannot be reified"));
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
