package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
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

public final class AppliedTypeMatchCompilationTest {
    @Test
    public void exactClassApplicationsDispatchOnReifiedRoots() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "appliedMatch" + suffix;
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-applied-match-");
        final var unit = parse("Main.zn", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn classify<T: reified>(box: Box<T>): Int = match (box) {
                    case Box<Int> as boxed -> boxed.value;
                    case Box<String> as boxed -> boxed.value.length();
                    case _ -> -1;
                };
                fn intResult(): Int = classify<Int>(Box<Int>.new(42));
                fn stringResult(): Int = classify<String>(Box<String>.new("four"));
                fn fallbackResult(): Int = classify<Boolean>(Box<Boolean>.new(true));
                fn classifyValue<T: reified>(value: T): Int = match (value) {
                    case Box<Int> as boxed -> boxed.value;
                    case _ -> -1;
                };
                fn valueFromBox(): Int = classifyValue<Box<Int>>(Box<Int>.new(6));
                fn valueFromInt(): Int = classifyValue<Int>(3);
                fn unbox(box: Box<Int>): Int = match (box) {
                    case Box<Int>(value) -> value;
                };
                fn unboxed(): Int = unbox(Box<Int>.new(12));
                fn unboxGeneric<T: reified>(box: Box<T>): Int = match (box) {
                    case Box<Int>(value) -> value;
                    case _ -> -1;
                };
                fn unboxedGeneric(): Int = unboxGeneric<Int>(Box<Int>.new(13));
                fn guarded<T: reified>(box: Box<T>): Int = match (box) {
                    case Box<Int> as boxed if boxed.value > 10 -> 1;
                    case Box<Int> -> 0;
                    case _ -> -1;
                };
                fn guardedLarge(): Int = guarded<Int>(Box<Int>.new(42));
                fn guardedSmall(): Int = guarded<Int>(Box<Int>.new(5));
                fn guardedOther(): Int = guarded<String>(Box<String>.new("value"));
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            final var resolution = compiler.resolveWithDiagnostics();
            assertTrue(resolution.errors().toString(), resolution.errors().isEmpty());
            final var compilation = compiler.compileWithDiagnostics();
            assertTrue(compilation.diagnostics().toString(), compilation.successful());

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(42, main.getMethod("intResult").invoke(null));
                assertEquals(4, main.getMethod("stringResult").invoke(null));
                assertEquals(-1, main.getMethod("fallbackResult").invoke(null));
                assertEquals(6, main.getMethod("valueFromBox").invoke(null));
                assertEquals(-1, main.getMethod("valueFromInt").invoke(null));
                assertEquals(12, main.getMethod("unboxed").invoke(null));
                assertEquals(13, main.getMethod("unboxedGeneric").invoke(null));
                assertEquals(1, main.getMethod("guardedLarge").invoke(null));
                assertEquals(0, main.getMethod("guardedSmall").invoke(null));
                assertEquals(-1, main.getMethod("guardedOther").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void exactProductApplicationsDestructureAfterTheDescriptorMatch() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "appliedProductMatch" + suffix;
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-applied-product-");
        final var unit = parse("Main.zn", """
                package %s;
                public type Pair<A, B> = (left: A, right: B);
                fn leftOfIntPair<T: reified>(pair: Pair<T, String>): Int = match (pair) {
                    case Pair<Int, String>(left, _) -> left;
                    case _ -> -1;
                };
                fn productInt(): Int = leftOfIntPair<Int>(Pair<Int, String>.new(7, "value"));
                fn productOther(): Int = leftOfIntPair<Boolean>(Pair<Boolean, String>.new(true, "value"));
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            final var resolution = compiler.resolveWithDiagnostics();
            assertTrue(resolution.errors().toString(), resolution.errors().isEmpty());
            final var compilation = compiler.compileWithDiagnostics();
            assertTrue(compilation.diagnostics().toString(), compilation.successful());

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(7, main.getMethod("productInt").invoke(null));
                assertEquals(-1, main.getMethod("productOther").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void reifiedAnonymousExistentialRootsUseTheirHiddenDescriptor() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "appliedExistentialMatch" + suffix;
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-applied-existential-");
        final var unit = parse("Main.zn", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn classify(box: Box<: reified>): Int = match (box) {
                    case Box<Int> as boxed -> boxed.value;
                    case Box<String> as boxed -> boxed.value.length();
                    case _ -> -1;
                };
                fn intResult(): Int = classify(Box<Int>.new(9));
                fn stringResult(): Int = classify(Box<String>.new("abc"));
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            final var resolution = compiler.resolveWithDiagnostics();
            assertTrue(resolution.errors().toString(), resolution.errors().isEmpty());
            final var compilation = compiler.compileWithDiagnostics();
            assertTrue(compilation.diagnostics().toString(), compilation.successful());

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(9, main.getMethod("intResult").invoke(null));
                assertEquals(3, main.getMethod("stringResult").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void staticallyTotalApplicationNeedsNoDescriptorAndImpossibleOnesAreRejected() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "staticAppliedMatch" + suffix;
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-static-applied-");
        final var unit = parse("Main.zn", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn exact(box: Box<Int>): Int = match (box) {
                    case Box<Int> as boxed -> boxed.value;
                };
                fn exactResult(): Int = exact(Box<Int>.new(5));
                fn same<T>(box: Box<T>): Int = match (box) {
                    case Box<T> as boxed -> 1;
                };
                fn sameResult(): Int = same<Int>(Box<Int>.new(8));
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            final var resolution = compiler.resolveWithDiagnostics();
            assertTrue(resolution.errors().toString(), resolution.errors().isEmpty());
            final var compilation = compiler.compileWithDiagnostics();
            assertTrue(compilation.diagnostics().toString(), compilation.successful());
            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals(5, loader.loadClass(packageName + ".Main")
                        .getMethod("exactResult").invoke(null));
                assertEquals(1, loader.loadClass(packageName + ".Main")
                        .getMethod("sameResult").invoke(null));
            }
        } finally {
            deleteTree(output);
        }

        final var impossible = parse("Bad.zn", """
                package badStatic;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn impossible(box: Box<Int>): Int = match (box) {
                    case Box<String> -> 1;
                    case _ -> 0;
                };
                """);
        final var impossibleCompiler = CompilationService.forCompilationUnits(List.of(impossible),
                "badStatic.Main", "badStatic", List.of(), true, List.of(),
                Path.of("target", "zeron-static-applied-invalid"));
        final var impossibleErrors = impossibleCompiler.resolveWithDiagnostics().errors();
        assertTrue(impossibleErrors.toString(), impossibleErrors.stream()
                .anyMatch(error -> error.message().contains("statically impossible")));
    }

    @Test
    public void appliedMatchIdentitySurvivesSeparateCompilation() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "appliedMatchLibrary" + suffix;
        final var clientPackage = "appliedMatchClient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-applied-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-applied-client-");
        final var libraryUnit = parse("Library.zn", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                """.formatted(libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(List.of(libraryUnit),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(),
                    libraryOutput);
            final var libraryResolution = libraryCompiler.resolveWithDiagnostics();
            assertTrue(libraryResolution.errors().toString(), libraryResolution.errors().isEmpty());
            libraryCompiler.compile();

            final var index = com.maruseron.zeron.domain.ZeronLibraryIndex
                    .readFromDirectory(libraryOutput);
            final var clientUnit = parse("Client.zn", """
                    package %s;
                    import %s.Box;
                    fn classify<T: reified>(box: Box<T>): Int = match (box) {
                        case Box<Int>(value) -> value;
                        case _ -> -1;
                    };
                    fn intResult(): Int = classify<Int>(Box<Int>.new(21));
                    fn stringResult(): Int = classify<String>(Box<String>.new("value"));
                    """.formatted(clientPackage, libraryPackage));
            final var clientCompiler = CompilationService.forCompilationUnits(List.of(clientUnit),
                    clientPackage + ".Client", clientPackage, List.of(index), true, List.of(),
                    clientOutput);
            final var clientResolution = clientCompiler.resolveWithDiagnostics();
            assertTrue(clientResolution.errors().toString(), clientResolution.errors().isEmpty());
            clientCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var client = loader.loadClass(clientPackage + ".Client");
                assertEquals(21, client.getMethod("intResult").invoke(null));
                assertEquals(-1, client.getMethod("stringResult").invoke(null));
            }
        } finally {
            deleteTree(libraryOutput);
            deleteTree(clientOutput);
        }
    }

    @Test
    public void rejectsErasedRootsUnclosedTargetsDuplicatesAndOpenEndedMatches() {
        final var boxDecl = """
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                """;
        assertTrue(resolveErrors(boxDecl + """
                fn bad(value: Any): Int = match (value) {
                    case Box<Int> -> 1;
                    case _ -> 0;
                };
                """).stream().anyMatch(error -> error.message().contains("descriptor-backed")));
        assertTrue(resolveErrors(boxDecl + """
                fn bad<T>(box: Box<T>): Int = match (box) {
                    case Box<Int> -> 1;
                    case _ -> 0;
                };
                """).stream().anyMatch(error -> error.message().contains("descriptor-backed")));
        assertTrue(resolveErrors(boxDecl + """
                fn bad(box: Box<Int>): Int = match (box) {
                    case Box<*> -> 1;
                    case _ -> 0;
                };
                """).stream().anyMatch(error -> error.message().contains("wildcard")));
        assertTrue(resolveErrors(boxDecl + """
                fn bad<T: reified>(box: Box<T>): Int = match (box) {
                    case Box<Int> -> 1;
                    case Box<Int> -> 2;
                    case _ -> 0;
                };
                """).stream().anyMatch(error -> error.message().contains("same exact identity")));
        assertTrue(resolveErrors(boxDecl + """
                fn bad<T: reified>(box: Box<T>): Int = match (box) {
                    case Box<Int> -> 1;
                };
                """).stream().anyMatch(error -> error.message().contains("Non-exhaustive match")));
    }

    private static List<com.maruseron.zeron.diagnostic.Diagnostic> resolveErrors(final String source) {
        final var unit = parse("Bad.zn", "package badApplied;\n" + source);
        return CompilationService.forCompilationUnits(List.of(unit),
                "badApplied.Main", "badApplied", List.of(), true, List.of(),
                Path.of("target", "zeron-applied-invalid")).resolveWithDiagnostics().errors();
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
