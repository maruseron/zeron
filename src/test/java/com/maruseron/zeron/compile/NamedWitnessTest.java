package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NamedWitnessTest {
    @Test
    public void namedPublicWitnessIsActivatedByExplicitWitnessImport() throws Exception {
        final var suffix = suffix();
        final var lib = "lib" + suffix;
        final var app = "app" + suffix;
        final var library = parse("Label.zn", """
                package %s;

                public contract Label<T> {
                    label(): String;
                }

                public witness IntLabel: Label<Int> for Int {
                    fn label(): String = "integer";
                }
                """.formatted(lib));
        final var client = parse("Main.zn", """
                package %s;

                import %s.Label;
                import witness %s.IntLabel;

                fn labelOf<T: Label<T>>(value: T): String = value.label();
                fn run(): String = labelOf(42);
                """.formatted(app, lib, lib));
        compileSucceeds(List.of(library, client), app + ".Main");
    }

    @Test
    public void witnessStarImportActivatesNamedPublicWitnessesOnly() throws Exception {
        final var suffix = suffix();
        final var lib = "lib" + suffix;
        final var app = "app" + suffix;
        final var library = parse("Label.zn", """
                package %s;

                public contract Label<T> {
                    label(): String;
                }

                public witness IntLabel: Label<Int> for Int {
                    fn label(): String = "integer";
                }

                witness HiddenLabel: Label<String> for String {
                    fn label(): String = "hidden";
                }
                """.formatted(lib));
        final var client = parse("Main.zn", """
                package %s;

                import %s.Label;
                import witness %s.*;

                fn labelOf<T: Label<T>>(value: T): String = value.label();
                fn run(): String = labelOf(7);
                """.formatted(app, lib, lib));
        compileSucceeds(List.of(library, client), app + ".Main");
    }

    @Test
    public void namedWitnessDispatchesItsImplementation() throws Exception {
        final var packageName = "named" + suffix();
        final var unit = parse("Main.zn", """
                package %s;

                public contract Label<T> {
                    label(): String;
                }

                public witness IntLabel: Label<Int> for Int {
                    fn label(): String = "integer";
                }

                fn labelOf<T: Label<T>>(value: T): String = value.label();
                fn run(): String = labelOf(42);
                """.formatted(packageName));
        try (final var loader = compile(List.of(unit), packageName + ".Main")) {
            assertEquals("integer", loader.loadClass(packageName + ".Main")
                    .getMethod("run").invoke(null));
        }
    }

    @Test
    public void packageLocalWitnessCannotBeImported() {
        final var suffix = suffix();
        final var lib = "lib" + suffix;
        final var app = "app" + suffix;
        final var library = parse("Label.zn", """
                package %s;

                public contract Label<T> {
                    label(): String;
                }

                witness HiddenLabel: Label<Int> for Int {
                    fn label(): String = "hidden";
                }
                """.formatted(lib));
        final var client = parse("Main.zn", """
                package %s;

                import %s.Label;
                import witness %s.HiddenLabel;
                """.formatted(app, lib, lib));
        final var errors = errors(List.of(library, client));
        assertTrue(errors.toString(),
                errors.stream().anyMatch(message -> message.contains("is not public")));
    }

    @Test
    public void unknownWitnessImportIsReported() {
        final var suffix = suffix();
        final var lib = "lib" + suffix;
        final var app = "app" + suffix;
        final var library = parse("Label.zn", """
                package %s;

                public contract Label<T> {
                    label(): String;
                }

                public witness IntLabel: Label<Int> for Int {
                    fn label(): String = "integer";
                }
                """.formatted(lib));
        final var client = parse("Main.zn", """
                package %s;

                import %s.Label;
                import witness %s.Missing;
                """.formatted(app, lib, lib));
        final var errors = errors(List.of(library, client));
        assertTrue(errors.toString(),
                errors.stream().anyMatch(message -> message.contains("Unknown witness")));
    }

    @Test
    public void defaultWitnessIsActivatedByTheTargetTypeImport() throws Exception {
        final var suffix = suffix();
        final var lib = "lib" + suffix;
        final var app = "app" + suffix;
        final var library = parse("Box.zn", """
                package %s;

                public contract Label<T> {
                    label(): String;
                }

                public class Box {
                    public constructor new;
                }

                default witness Label<Box> for Box {
                    fn label(): String = "box";
                }
                """.formatted(lib));
        final var client = parse("Main.zn", """
                package %s;

                import %s.Box;
                import %s.Label;

                fn labelOf<T: Label<T>>(value: T): String = value.label();
                fn run(): String = labelOf(Box.new());
                """.formatted(app, lib, lib));
        compileSucceeds(List.of(library, client), app + ".Main");
    }

    @Test
    public void fullyQualifiedTypeReferenceDoesNotActivateDefaultWitness() {
        final var suffix = suffix();
        final var lib = "lib" + suffix;
        final var app = "app" + suffix;
        final var library = parse("Box.zn", """
                package %s;

                public contract Label<T> {
                    label(): String;
                }

                public class Box {
                    public constructor new;
                }

                default witness Label<Box> for Box {
                    fn label(): String = "box";
                }
                """.formatted(lib));
        final var client = parse("Main.zn", """
                package %s;

                import %s.Label;

                fn labelOf<T: Label<T>>(value: T): String = value.label();
                fn call(value: %s.Box): String = labelOf(value);
                """.formatted(app, lib, lib));
        final var errors = errors(List.of(library, client));
        assertFalse(errors.toString(), errors.isEmpty());
        assertTrue(errors.toString(),
                errors.stream().anyMatch(message -> message.contains("Label")));
    }

    @Test
    public void witnessImportCannotReachADefault() {
        final var suffix = suffix();
        final var lib = "lib" + suffix;
        final var app = "app" + suffix;
        final var library = parse("Box.zn", """
                package %s;

                public contract Label<T> {
                    label(): String;
                }

                public class Box {
                    public constructor new;
                }

                default witness Label<Box> for Box {
                    fn label(): String = "box";
                }
                """.formatted(lib));
        final var client = parse("Main.zn", """
                package %s;

                import %s.Box;
                import %s.Label;
                import witness %s.BoxLabel;
                """.formatted(app, lib, lib, lib));
        final var errors = errors(List.of(library, client));
        assertTrue(errors.toString(),
                errors.stream().anyMatch(message -> message.contains("Unknown witness")));
    }

    @Test
    public void defaultWitnessMustShareTheTargetTypeFile() {
        final var parsed = Parser.of(Scanner.from("""
                package misplaced;

                public contract Label<T> {
                    label(): String;
                }

                default witness Label<Box> for Box {
                    fn label(): String = "box";
                }
                """, "Misplaced.zn").scanTokens()).parseCompilationUnitWithDiagnostics("Misplaced.zn");
        final var messages = new ArrayList<String>();
        parsed.diagnostics().forEach(diagnostic -> messages.add(diagnostic.message()));
        assertTrue(messages.toString(),
                messages.stream().anyMatch(message -> message.contains("same file")));
    }

    @Test
    public void defaultWitnessForPublicTargetRequiresPublicContract() {
        final var unit = parse("HiddenContract.zn", """
                package hiddencontract;

                contract Hidden {
                    label(): String;
                }

                public class Box {
                    public constructor new;
                }

                default witness Hidden for Box {
                    fn label(): String = "box";
                }
                """);
        final var errors = errors(List.of(unit));
        assertTrue(errors.toString(),
                errors.stream().anyMatch(message -> message.contains("public contract")));
    }

    @Test
    public void explicitlyNamedDefaultWitnessIsRejected() {
        final var parsed = Parser.of(Scanner.from("""
                package nameddefault;

                public contract Label<T> {
                    label(): String;
                }

                public class Box {
                    public constructor new;
                }

                default witness BoxLabel: Label<Box> for Box {
                    fn label(): String = "box";
                }
                """, "NamedDefault.zn").scanTokens()).parseCompilationUnitWithDiagnostics("NamedDefault.zn");
        final var messages = new ArrayList<String>();
        parsed.diagnostics().forEach(diagnostic -> messages.add(diagnostic.message()));
        assertTrue(messages.toString(),
                messages.stream().anyMatch(message -> message.contains("cannot declare a name")));
    }

    @Test
    public void intrinsicDefaultWitnessInCanonicalPackageCompiles() throws Exception {
        final var suffix = suffix();
        final var app = "intrinsicdefaults" + suffix;
        final var library = parse("Marker.zn", """
                package zeron.lang;

                public contract Marker<T> {
                    mark(): String;
                }

                default witness Marker<Int> for Int {
                    fn mark(): String = "int";
                }
                """);
        final var client = parse("Main.zn", """
                package %s;

                import zeron.lang.Marker;

                fn markOf<T: Marker<T>>(value: T): String = value.mark();
                fn run(): String = markOf(42);
                """.formatted(app));
        compileSucceeds(List.of(library, client), app + ".Main");
    }

    @Test
    public void intrinsicDefaultWitnessOutsideCanonicalPackageIsRejected() {
        final var parsed = Parser.of(Scanner.from("""
                package misplaced.intrinsic;

                public contract Marker<T> {
                    mark(): String;
                }

                default witness Marker<Int> for Int {
                    fn mark(): String = "int";
                }
                """, "IntrinsicDefault.zn").scanTokens()).parseCompilationUnitWithDiagnostics("IntrinsicDefault.zn");
        final var messages = new ArrayList<String>();
        parsed.diagnostics().forEach(diagnostic -> messages.add(diagnostic.message()));
        assertTrue(messages.toString(),
                messages.stream().anyMatch(message -> message.contains("canonical package")));
    }

    @Test
    public void arrayIntrinsicDefaultIsCanonicalInZeronLang() {
        final var parsed = Parser.of(Scanner.from("""
                package zeron.lang;

                public contract Marker<T> {
                    mark(): String;
                }

                default witness Marker<Array<_>> for Array<_> {
                    fn mark(): String = "array";
                }
                """, "ArrayDefault.zn").scanTokens()).parseCompilationUnitWithDiagnostics("ArrayDefault.zn");
        final var messages = new ArrayList<String>();
        parsed.diagnostics().forEach(diagnostic -> messages.add(diagnostic.message()));
        assertFalse(messages.toString(),
                messages.stream().anyMatch(message -> message.contains("canonical package")));
    }

    @Test
    public void duplicateWitnessNamesInOnePackageAreRejected() {
        final var parsed = Parser.of(Scanner.from("""
                public contract Label<T> {
                    label(): String;
                }

                public witness Same: Label<Int> for Int {
                    fn label(): String = "integer";
                }

                public witness Same: Label<String> for String {
                    fn label(): String = "text";
                }
                """, "Duplicate.zn").scanTokens()).parseCompilationUnitWithDiagnostics("Duplicate.zn");
        final var messages = new ArrayList<String>();
        parsed.diagnostics().forEach(diagnostic -> messages.add(diagnostic.message()));
        if (messages.isEmpty()) {
            final var output = newTempDirectory();
            try {
                final var compiler = CompilationService.forCompilationUnits(
                        List.of(parsed.compilationUnit()), "Main", "", List.of(), true, List.of(), output);
                compiler.resolveWithDiagnostics().errors()
                        .forEach(error -> messages.add(error.message()));
            } finally {
                deleteTree(output);
            }
        }
        assertTrue(messages.toString(),
                messages.stream().anyMatch(message -> message.contains("already declared in this package")));
    }

    @Test
    public void indexPreservesWitnessNamesAndTheDefaultMarker() throws Exception {
        final var packageName = "witnessindex" + suffix();
        final var unit = parse("Library.zn", """
                package %s;

                public contract Label<T> {
                    label(): String;
                }

                public class Box {
                    public constructor new;
                }

                default witness Label<Box> for Box {
                    fn label(): String = "box";
                }

                public witness IntLabel: Label<Int> for Int {
                    fn label(): String = "integer";
                }

                witness HiddenLabel: Label<String> for String {
                    fn label(): String = "hidden";
                }
                """.formatted(packageName));
        final var output = newTempDirectory();
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), packageName + ".Library", packageName,
                    List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            final var index = ZeronLibraryIndex.readFromDirectory(output);
            final var witnesses = index.declarations().stream()
                    .filter(ZeronLibraryIndex.WitnessExport.class::isInstance)
                    .map(ZeronLibraryIndex.WitnessExport.class::cast)
                    .toList();
            final var named = witnesses.stream()
                    .filter(witness -> witness.qualifiedName().equals(packageName + ".IntLabel"))
                    .findFirst().orElseThrow();
            assertFalse(named.defaultWitness());
            final var canonical = witnesses.stream()
                    .filter(ZeronLibraryIndex.WitnessExport::defaultWitness)
                    .filter(witness -> witness.qualifiedName().contains("$zeron$default$"))
                    .findFirst().orElseThrow();
            assertTrue(canonical.defaultWitness());
            assertEquals(packageName + ".Label", canonical.contractType().name());
            assertEquals(packageName + ".Box", canonical.targetType().name());
            assertTrue(witnesses.stream().noneMatch(witness ->
                    witness.qualifiedName().equals(packageName + ".HiddenLabel")));
        } finally {
            deleteTree(output);
        }
    }

    private static List<String> errors(final List<CompilationUnit> units) {
        final var output = newTempDirectory();
        try {
            final var messages = new ArrayList<String>();
            final var compiler = CompilationService.forCompilationUnits(units, "Main", "",
                    List.of(), true, List.of(), output);
            compiler.resolveWithDiagnostics().errors()
                    .forEach(error -> messages.add(error.message()));
            return messages;
        } finally {
            deleteTree(output);
        }
    }

    private static void compileSucceeds(final List<CompilationUnit> units,
                                        final String mainClassName) throws Exception {
        final var output = newTempDirectory();
        final var packageName = mainClassName.contains(".")
                ? mainClassName.substring(0, mainClassName.lastIndexOf('.')) : "";
        try {
            final var compiler = CompilationService.forCompilationUnits(units, mainClassName, packageName,
                    List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
        } finally {
            deleteTree(output);
        }
    }

    private static URLClassLoader compile(final List<CompilationUnit> units,
                                          final String mainClassName) throws Exception {
        final var output = newTempDirectory();
        final var packageName = mainClassName.contains(".")
                ? mainClassName.substring(0, mainClassName.lastIndexOf('.')) : "";
        try {
            final var compiler = CompilationService.forCompilationUnits(units, mainClassName, packageName,
                    List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
        } catch (final RuntimeException exception) {
            deleteTree(output);
            throw exception;
        }
        return new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                NamedWitnessTest.class.getClassLoader()) {
            @Override
            public void close() throws java.io.IOException {
                super.close();
                deleteTree(output);
            }
        };
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnit(sourcePath);
    }

    private static Path newTempDirectory() {
        try {
            return Files.createTempDirectory(Path.of("target"), "zeron-named-witness-");
        } catch (final java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void deleteTree(final Path path) {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (final Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
