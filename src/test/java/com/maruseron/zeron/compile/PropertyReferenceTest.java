package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class PropertyReferenceTest {
    @Test
    public void resolvesReferencesIdentityGetterAndConversion() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "propref" + suffix;
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-property-reference-");
        final var unit = parse("""
                package %s;

                public class User {
                    public property name: String;
                    public property nickname: String;
                    public property age: Int;
                    public constructor new;
                }

                public fn sameReference(): Boolean = User::name == User::name;

                public fn differentProperties(): Boolean = User::name == User::nickname;

                public fn nameOf(): String = (User::name).name;

                public fn identityOf(): String = (User::name).identity;

                public fn readName(): String {
                    let user = User.new("Ada", "A", 36);
                    return (User::name).get(user);
                }

                public fn converted(): String {
                    let user = User.new("Grace", "G", 45);
                    let read: User -> String = User::name;
                    return read(user);
                }

                public fn annotated(): String {
                    let byName: PropertyRef<User, String> = User::name;
                    return byName.get(User.new("Katherine", "K", 51));
                }

                public fn label(user: User, describe: (User) -> String): String = describe(user);

                public fn labelled(): String = label(User.new("Dorothy", "D", 40), User::name);
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
                assertTrue((Boolean) main.getMethod("sameReference").invoke(null));
                assertFalse((Boolean) main.getMethod("differentProperties").invoke(null));
                assertEquals("name", main.getMethod("nameOf").invoke(null));
                assertEquals(packageName + ".User#name", main.getMethod("identityOf").invoke(null));
                assertEquals("Katherine", main.getMethod("annotated").invoke(null));
                assertEquals("Dorothy", main.getMethod("labelled").invoke(null));
                assertEquals("Ada", main.getMethod("readName").invoke(null));
                assertEquals("Grace", main.getMethod("converted").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsUnknownPropertiesAndNonTypeOperands() {
        final var packageName = "proprefinvalid" + UUID.randomUUID().toString().replace("-", "");
        assertResolutionFails(packageName, """
                public class User {
                    public property name: String;
                    public constructor new;
                }
                fn broken(): String = (User::missing).name;
                """);
        assertResolutionFails(packageName, """
                public class User {
                    public property name: String;
                    public constructor new;
                }
                fn broken(user: User): String = (user::name).name;
                """);
    }

    @Test
    public void resolvesContractAndGenericOwnerReferences() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "proprefgeneric" + suffix;
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-property-reference-generic-");
        final var unit = parse("""
                package %s;

                public contract Named {
                    property name: String;
                }

                public class User is Named {
                    public property name: String;
                    public constructor new;
                }

                public class Box<T: nullable + mut> {
                    public property value: T;
                    public constructor new;
                }

                public fn classIdentity(): String = (User::name).identity;

                public fn contractIdentity(): String = (Named::name).identity;

                public fn distinctIdentities(): Boolean =
                    (User::name).identity != (Named::name).identity;

                public fn readThroughContract(): String {
                    let user: Named = User.new("Ada");
                    return (Named::name).get(user);
                }

                public fn readGeneric(): Int = (Box<Int>::value).get(Box.new(7));

                public fn genericReader(): Int {
                    let read: Box<Int> -> Int = Box<Int>::value;
                    return read(Box.new(9));
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
                assertEquals(packageName + ".User#name", main.getMethod("classIdentity").invoke(null));
                assertEquals(packageName + ".Named#name",
                        main.getMethod("contractIdentity").invoke(null));
                assertEquals(Boolean.TRUE, main.getMethod("distinctIdentities").invoke(null));
                assertEquals("Ada", main.getMethod("readThroughContract").invoke(null));
                assertEquals(7, main.getMethod("readGeneric").invoke(null));
                assertEquals(9, main.getMethod("genericReader").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsInaccessibleGetters() {
        final var packageName = "proprefprivate" + UUID.randomUUID().toString().replace("-", "");
        assertResolutionFails(packageName, """
                public class User {
                    private property secret: String;
                    public constructor new;
                }
                fn broken(): String = (User::secret).name;
                """);
    }


    @Test
    public void preservesIdentityAcrossSeparateCompilation() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "propreflibrary" + suffix;
        final var clientPackage = "proprefclient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-property-reference-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-property-reference-client-");
        final var library = parse("Library.zn", """
                package %s;

                public class User {
                    public property name: String;
                    public constructor new;
                }

                public fn libraryReference(): PropertyRef<User, String> = User::name;

                public fn libraryIdentity(): String = (User::name).identity;
                """.formatted(libraryPackage));
        final var client = parse("Main.zn", """
                package %s;

                import %s.User;

                public fn matchesLibraryReference(reference: PropertyRef<User, String>): Boolean =
                    User::name == reference;

                public fn readsThroughLibraryReference(reference: PropertyRef<User, String>): String =
                    reference.get(User.new("Ada"));

                public fn clientIdentity(): String = (User::name).identity;
                """.formatted(clientPackage, libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(library), libraryPackage + ".Library", libraryPackage,
                    List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();

            final var index = com.maruseron.zeron.domain.ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), clientPackage + ".Main", clientPackage,
                    List.of(index), true, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var libraryClass = loader.loadClass(libraryPackage + ".Library");
                final var main = loader.loadClass(clientPackage + ".Main");
                final var propertyRefClass = loader.loadClass("zeron.lang.PropertyRef");
                final var reference = libraryClass.getMethod("libraryReference").invoke(null);
                assertEquals(libraryPackage + ".User#name",
                        main.getMethod("clientIdentity").invoke(null));
                assertEquals(Boolean.TRUE, main.getMethod("matchesLibraryReference",
                        propertyRefClass).invoke(null, reference));
                assertEquals("Ada", main.getMethod("readsThroughLibraryReference",
                        propertyRefClass).invoke(null, reference));
            }
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
        }
    }

    @Test
    public void passesReferencesWhereASelectorFunctionIsExpected() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "proprefselector" + suffix;
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-property-reference-selector-");
        final var unit = parse("""
                package %s;

                public class User {
                    public property name: String;
                    public constructor new;
                }

                public fn optionalName(): String {
                    let maybeUser: Option<User> = Option.Some(User.new("Ada"));
                    return maybeUser.map(User::name).getOrElse("none");
                }

                public fn emptyOptionalName(): String {
                    let maybeUser: Option<User> = Option<User>.None();
                    return maybeUser.map(User::name).getOrElse("none");
                }

                public fn pick<T: nullable + mut, R: nullable + mut>(value: T, select: (T) -> R): R =
                    select(value);

                public fn picked(): String = pick(User.new("Grace"), User::name);
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
                assertEquals("Ada", main.getMethod("optionalName").invoke(null));
                assertEquals("none", main.getMethod("emptyOptionalName").invoke(null));
                assertEquals("Grace", main.getMethod("picked").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static void assertResolutionFails(final String packageName, final String body) {
        final var output = Path.of("target", "zeron-property-reference-invalid-" + packageName);
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(parse(
                        "package " + packageName + ";\n" + body))),
                packageName + ".Main", packageName, List.of(), false, List.of(), output);
        assertThrows(ResolutionError.class, compiler::resolve);
    }

    private static CompilationUnit parse(final String source) {
        return parse("PropertyReferenceTest.zn", source);
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        final var result = Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnitWithDiagnostics(sourcePath);
        if (!result.diagnostics().isEmpty()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.compilationUnit();
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
