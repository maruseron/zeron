package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ModelCompilationTest {
    private static final String SOURCE = """
            package modeltest;

            import zeron.lang.Model;
            import zeron.lang.Option;

            public class User {
                public property name: String;
                public property age: Int;
                public constructor new;
            }

            public contract SqlModel<T: nullable + mut> requires Model<T> {
                property tableName: String;
            }

            public model UserSql is SqlModel<User> {
                tableName = "users";

                name -> "user_name";
                age -> "user_age";
            }

            public fn externalNameOfName(): String {
                return match (UserSql.fieldOf((User::name).id)) {
                    case Option.Some(field) -> field.externalName;
                    case Option.None() -> "missing";
                };
            }

            public fn readsName(): String {
                let user = User.new("Ada", 36);
                return match (UserSql.field(User::name)) {
                    case Option.Some(field) -> field.get(user);
                    case Option.None() -> "missing";
                };
            }

            public fn mapsAge(): Boolean {
                return match (UserSql.fieldOf((User::age).id)) {
                    case Option.Some(_) -> true;
                    case Option.None() -> false;
                };
            }

            public fn ignoresUnmappedProperty(): Boolean {
                let user = User.new("Ada", 36);
                let value: String = "Ada";
                return match (UserSql.field(User::name)) {
                    case Option.Some(field) -> field.get(user) == value;
                    case Option.None() -> false;
                };
            }

            public fn tableNameOf(model: SqlModel<User>): String = model.tableName;

            public fn passesModelAsContractValue(): String = tableNameOf(UserSql);

            public fn bindsModelToContract(): String {
                let model: SqlModel<User> = UserSql;
                return model.tableName;
            }
            """;

    private static final String LIBRARY_SOURCE = """
            package modellib;

            import zeron.lang.Model;

            public class User {
                public property name: String;
                public property age: Int;
                public constructor new;
            }

            public contract SqlModel<T: nullable + mut> requires Model<T> {
                property tableName: String;
            }

            public model UserSql is SqlModel<User> {
                tableName = "users";

                name -> "user_name";
                age -> "user_age";
            }
            """;

    private static final String CLIENT_SOURCE = """
            package modelapp;

            import modellib.SqlModel;
            import modellib.User;
            import modellib.UserSql;
            import zeron.lang.Option;

            public fn externalNameOfName(): String {
                return match (UserSql.fieldOf((User::name).id)) {
                    case Option.Some(field) -> field.externalName;
                    case Option.None() -> "missing";
                };
            }

            public fn readsName(): String {
                let user = User.new("Ada", 36);
                return match (UserSql.field(User::name)) {
                    case Option.Some(field) -> field.get(user);
                    case Option.None() -> "missing";
                };
            }

            public fn tableNameOf(model: SqlModel<User>): String = model.tableName;

            public fn passesModelAsContractValue(): String = tableNameOf(UserSql);
            """;

    @Test
    public void modelsExposeMappingsAndContractValues() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-model-test-");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(parse(SOURCE))),
                    "modeltest.Main", "modeltest", List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass("modeltest.Main");
                assertEquals("user_name", main.getMethod("externalNameOfName").invoke(null));
                assertEquals("Ada", main.getMethod("readsName").invoke(null));
                assertTrue((Boolean) main.getMethod("mapsAge").invoke(null));
                assertTrue((Boolean) main.getMethod("ignoresUnmappedProperty").invoke(null));
                assertEquals("users", main.getMethod("passesModelAsContractValue").invoke(null));
                assertEquals("users", main.getMethod("bindsModelToContract").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void modelsWorkAcrossSeparateCompilation() throws Exception {
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-model-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-model-client-");
        try {
            final var library = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(parse(LIBRARY_SOURCE))),
                    "modellib.Library", "modellib",
                    List.of(), false, List.of(), libraryOutput);
            library.resolve();
            library.compile();

            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var model = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ModelExport.class::isInstance)
                    .map(ZeronLibraryIndex.ModelExport.class::cast)
                    .findFirst().orElseThrow();
            assertEquals("modellib.UserSql", model.qualifiedName());
            assertEquals("modellib.SqlModel", model.contract().qualifiedName());
            assertEquals(List.of("modellib.User#name", "modellib.User#age"),
                    model.fields().stream()
                            .map(ZeronLibraryIndex.ModelFieldExport::propertyId).toList());
            assertEquals(List.of("user_name", "user_age"), model.fields().stream()
                    .map(ZeronLibraryIndex.ModelFieldExport::externalName).toList());
            assertTrue(index.declarations().stream().anyMatch(export ->
                    export instanceof ZeronLibraryIndex.ClassExport classExport
                            && classExport.qualifiedName().equals("modellib.UserSql$model")));

            final var client = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(parse(CLIENT_SOURCE))),
                    "modelapp.Main", "modelapp",
                    List.of(index), false, List.of(), clientOutput);
            client.resolve();
            client.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass("modelapp.Main");
                assertEquals("user_name", main.getMethod("externalNameOfName").invoke(null));
                assertEquals("Ada", main.getMethod("readsName").invoke(null));
                assertEquals("users", main.getMethod("passesModelAsContractValue").invoke(null));
            }
        } finally {
            deleteTree(libraryOutput);
            deleteTree(clientOutput);
        }
    }

    @Test
    public void rejectsDuplicateMappings() throws Exception {
        assertRejected(SOURCE.replace("    name -> \"user_name\";",
                "    name -> \"user_name\";\n    name -> \"name_again\";"));
    }

    @Test
    public void rejectsUnknownPropertyMappings() throws Exception {
        assertRejected(SOURCE.replace("name -> \"user_name\";", "missing -> \"user_name\";"));
    }

    @Test
    public void rejectsMissingContractPropertyAssignments() throws Exception {
        assertRejected(SOURCE.replace("    tableName = \"users\";\n", ""));
    }

    @Test
    public void rejectsNonConstantContractPropertyAssignments() throws Exception {
        assertRejected(SOURCE.replace("tableName = \"users\";",
                "tableName = \"users\" + \"_v2\";"));
    }

    @Test
    public void rejectsContractsThatDoNotRequireModel() throws Exception {
        assertRejected(SOURCE.replace(" requires Model<T>", ""));
    }

    private static void assertRejected(final String source) throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-model-rejected-");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(parse(source))),
                    "modeltest.Main", "modeltest", List.of(), false, List.of(), output);
            assertThrows(ResolutionError.class, compiler::resolve);
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final String source) {
        final var result = Parser.of(Scanner.from(source, "ModelTest.zn").scanTokens())
                .parseCompilationUnitWithDiagnostics("ModelTest.zn");
        if (!result.diagnostics().isEmpty()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.compilationUnit();
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
