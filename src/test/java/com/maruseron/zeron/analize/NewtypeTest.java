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
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class NewtypeTest {
    @Test
    public void validatedAndUnvalidatedNewtypesUseDistinctConstructionRules() throws Exception {
        final var output = Files.createTempDirectory("zeron-newtype-test");
        final var programName = "NewtypeProgram" + UUID.randomUUID().toString().replace("-", "");
        final var unit = parse("newtypes.zn", """
                public newtype UserId = String where value != "";
                public newtype Meters = Float;
                fn accepted(): Boolean = match (UserId.tryNew("alice")) {
                    case Option.Some(_) -> true;
                    case Option.None() -> false;
                };
                fn rejected(): Boolean = match (UserId.tryNew("")) {
                    case Option.Some(_) -> false;
                    case Option.None() -> true;
                };
                fn extracted(): String = match (UserId.tryNew("alice")) {
                    case Option.Some(userId) -> userId.value;
                    case Option.None() -> "";
                };
                fn unvalidatedValue(): Float = Meters.new(2.5).value;
                """);
        final var compiler = CompilationService.forCompilationUnits(
                List.of(unit), programName, "", List.of(), true, List.of(), output);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                final var userId = loader.loadClass("UserId");
                final var meters = loader.loadClass("Meters");
                assertTrue((Boolean) program.getMethod("accepted").invoke(null));
                assertTrue((Boolean) program.getMethod("rejected").invoke(null));
                assertEquals("alice", program.getMethod("extracted").invoke(null));
                assertEquals(2.5, (Double) program.getMethod("unvalidatedValue").invoke(null), 0.0);
                assertTrue(java.lang.reflect.Modifier.isPrivate(
                        userId.getDeclaredConstructor(String.class).getModifiers()));
                assertTrue(java.lang.reflect.Modifier.isPublic(
                        meters.getDeclaredConstructor(double.class).getModifiers()));
                assertTrue(java.lang.reflect.Modifier.isFinal(
                        userId.getDeclaredField("$zeron$property$value").getModifiers()));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsUncheckedValidatedConstructionIdentityAndNonBooleanValidators() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("bad-new.zn", """
                newtype UserId = String where value != "";
                fn invalid(): UserId = UserId.new("forged");
                """).declarations()));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("identity.zn", """
                newtype UserId = String where value != "";
                fn invalid(first: UserId, second: UserId): Boolean = first === second;
                """).declarations()));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("equality.zn", """
                newtype UserId = String;
                fn invalid(first: UserId, second: UserId): Boolean = first == second;
                """).declarations()));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("validator.zn", """
                newtype UserId = String where value;
                """).declarations()));
        final var invalidRepresentation = Parser.of(Scanner.from("""
                newtype Invalid = Array<Int>;
                """, "invalid-representation.zn").scanTokens())
                .parseCompilationUnitWithDiagnostics("invalid-representation.zn");
        assertFalse(invalidRepresentation.diagnostics().isEmpty());
    }

    @Test
    public void compiledLibraryExportsValidatedNewtypeAndFactoryMetadata() throws Exception {
        final var libraryOutput = Files.createTempDirectory("zeron-newtype-library");
        final var clientOutput = Files.createTempDirectory("zeron-newtype-client");
        final var libraryPackage = "newtypelib" + UUID.randomUUID().toString().replace("-", "");
        final var library = parse("library/UserId.zn", """
                package %s;
                public newtype UserId = String where value != "";
                """.formatted(libraryPackage));
        final var libraryCompiler = CompilationService.forCompilationUnits(
                List.of(library), libraryPackage + ".Library", libraryPackage,
                List.of(), true, List.of(), libraryOutput);
        try {
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFrom(libraryOutput.resolve(
                    Path.of("META-INF", "zeron", "api-v24.bin")));
            final var exportedType = index.declarations().stream()
                    .filter(ZeronLibraryIndex.NewtypeExport.class::isInstance)
                    .map(ZeronLibraryIndex.NewtypeExport.class::cast)
                    .filter(newtype -> newtype.qualifiedName().equals(libraryPackage + ".UserId"))
                    .findFirst().orElseThrow();
            assertTrue(exportedType.validated());
            assertTrue(index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .anyMatch(function -> function.qualifiedName().equals(libraryPackage + ".UserId.tryNew")));

            final var client = parse("client/Main.zn", """
                    package client;
                    import %s.UserId;
                    fn createsValue(): Boolean = match (UserId.tryNew("alice")) {
                        case Option.Some(_) -> true;
                        case Option.None() -> false;
                    };
                    """.formatted(libraryPackage));
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), "client.Main", "client",
                    List.of(index), true, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertTrue((Boolean) loader.loadClass("client.Main")
                        .getMethod("createsValue").invoke(null));
            }
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
        }
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens()).parseCompilationUnit(sourcePath);
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
