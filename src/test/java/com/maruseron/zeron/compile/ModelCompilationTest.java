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

            import zeron.compiler.CodeModel;
            import zeron.compiler.selectedProperty;
            import zeron.lang.Field;
            import zeron.lang.Model;
            import zeron.lang.Option;

            public class User {
                public property name: String;
                public mut property age: Int;
                public constructor new;
            }

            public contract Named {
                property name: String;
            }

            public class Hidden {
                property secret: String;
                public constructor new;
            }

            public class Box<T: nullable + mut> {
                public property value: T;
                public constructor new;
            }

            public class Counter {
                valueBacking: Int;
                public constructor new;
                public mut property value: Int {
                    get = this.valueBacking;
                    set(next) { this.valueBacking = next; }
                }
            }

            public contract SqlModel<T: nullable + mut> requires Model<T> {
                property tableName: String;
            }

            public model UserSql is SqlModel<User> {
                tableName = "users";

                name -> "user_name";
                age -> "user_age";
            }

            public contract DocumentModel<T: nullable + mut> requires Model<T> {
                property collection: String;
            }

            public model UserDoc is DocumentModel<User> {
                collection = "people";

                name -> "displayName";
            }

            public model EmptySql is SqlModel<User> {
                tableName = "empty";
            }

            public model BoxIntSql is SqlModel<Box<Int>> {
                tableName = "boxes";

                value -> "value";
            }

            public model CounterSql is SqlModel<Counter> {
                tableName = "counters";

                value -> "value";
            }

            fn selectName(model: CodeModel<(User) -> String>)
                : CodeModel<(User) -> String> = model;

            public fn codeModelPropertyLookup(): String {
                let model = selectName(user -> user.name);
                let selected = selectedProperty(model);
                if (selected == null) return "none";
                return match (UserSql.fieldOf(selected!!)) {
                    case Option.Some(field) -> field.externalName;
                    case Option.None() -> "missing";
                };
            }

            public fn contractPropertyDoesNotFindClassMapping(): Boolean =
                match (UserSql.fieldOf((Named::name).id)) {
                    case Option.Some(_) -> false;
                    case Option.None() -> true;
                };

            public fn contractTypedLookup(): String {
                let model: SqlModel<User> = UserSql;
                return match (model.field(User::name)) {
                    case Option.Some(field) -> field.get(User.new("Ada", 36));
                    case Option.None() -> "missing";
                };
            }

            public fn emptyModelHasNoFields(): Int = EmptySql.fields.length;

            public fn fieldOrder(): String {
                let fields = UserSql.fields;
                if (fields.length != 2) return "wrong";
                return fields[0].externalName + "," + fields[1].externalName;
            }

            public fn genericTargetReads(): Int {
                let box = Box<Int>.new(7);
                return match (BoxIntSql.field(Box<Int>::value)) {
                    case Option.Some(field) -> field.get(box);
                    case Option.None() -> -1;
                };
            }

            public fn sourceIdMatchesReference(): Boolean =
                match (UserSql.field(User::name)) {
                    case Option.Some(field) -> field.sourceId == (User::name).id;
                    case Option.None() -> false;
                };

            public fn unmappedTypedLookupFindsNothing(): Boolean =
                match (UserDoc.field(User::age)) {
                    case Option.Some(_) -> false;
                    case Option.None() -> true;
                };

            public fn writesThroughCustomSetter(): Int {
                let counter = Counter.new(0);
                return match (CounterSql.field(Counter::value)) {
                    case Option.Some(field) -> match (field.writer) {
                        case Option.Some(write) -> applyCounterWriter(counter, field, write);
                        case Option.None() -> -1;
                    };
                    case Option.None() -> -2;
                };
            }

            fn applyCounterWriter(counter: &Counter, field: Field<Counter, Int>,
                                  write: (&Counter, Int) -> Unit): Int {
                write(counter, 9);
                return field.get(counter);
            }

            public fn documentExternalName(): String {
                return match (UserDoc.fieldOf((User::name).id)) {
                    case Option.Some(field) -> field.externalName;
                    case Option.None() -> "missing";
                };
            }

            public fn documentCollection(): String = UserDoc.collection;

            public fn writesAge(): Int {
                let user = User.new("Ada", 36);
                return match (UserSql.field(User::age)) {
                    case Option.Some(field) -> match (field.writer) {
                        case Option.Some(write) -> applyWriter(user, field, write);
                        case Option.None() -> -1;
                    };
                    case Option.None() -> -2;
                };
            }

            fn applyWriter(user: &User, field: Field<User, Int>,
                           write: (&User, Int) -> Unit): Int {
                write(user, 41);
                return field.get(user);
            }

            public fn readOnlyNameHasNoWriter(): Boolean {
                return match (UserSql.field(User::name)) {
                    case Option.Some(field) -> match (field.writer) {
                        case Option.Some(_) -> false;
                        case Option.None() -> true;
                    };
                    case Option.None() -> false;
                };
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

            public fn acceptsAnyModel<T: nullable + mut>(model: Model<T>): Int {
                return model.fields.length;
            }

            public fn genericModelFieldCount(): Int = acceptsAnyModel(UserSql);

            public fn genericDocumentFieldCount(): Int = acceptsAnyModel(UserDoc);

            public fn bindsModelToCoreContract(): Int {
                let model: Model<User> = UserDoc;
                return model.fields.length;
            }
            """;

    private static final String LIBRARY_SOURCE = """
            package modellib;

            import zeron.lang.Model;

            public class User {
                public property name: String;
                public mut property age: Int;
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
            import zeron.lang.Field;
            import zeron.lang.Model;
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

            public fn writesAge(): Int {
                let user = User.new("Ada", 36);
                return match (UserSql.field(User::age)) {
                    case Option.Some(field) -> match (field.writer) {
                        case Option.Some(write) -> applyWriter(user, field, write);
                        case Option.None() -> -1;
                    };
                    case Option.None() -> -2;
                };
            }

            fn applyWriter(user: &User, field: Field<User, Int>,
                           write: (&User, Int) -> Unit): Int {
                write(user, 44);
                return field.get(user);
            }

            public fn tableNameOf(model: SqlModel<User>): String = model.tableName;

            public fn passesModelAsContractValue(): String = tableNameOf(UserSql);

            public fn acceptsAnyModel<T: nullable + mut>(model: Model<T>): Int {
                return model.fields.length;
            }

            public fn genericModelFieldCount(): Int = acceptsAnyModel(UserSql);
            """;

    private static final String FOREIGN_TARGET_SOURCE = """
            package forgeintarget;

            public class Person {
                public property name: String;
                public constructor new;
            }
            """;

    private static final String FOREIGN_MODEL_SOURCE = """
            package foreignmodel;

            import forgeintarget.Person;
            import zeron.lang.Model;

            public contract PersonModel<T: nullable + mut> requires Model<T> {
                property tableName: String;
            }

            public model PersonSql is PersonModel<Person> {
                tableName = "people";

                name -> "person_name";
            }
            """;

    private static final String FOREIGN_CLIENT_SOURCE = """
            package foreignclient;

            import foreignmodel.PersonSql;
            import forgeintarget.Person;
            import zeron.lang.Option;

            public fn externalName(): String =
                match (PersonSql.field(Person::name)) {
                    case Option.Some(field) -> field.externalName;
                    case Option.None() -> "missing";
                };

            public fn reads(): String {
                let person = Person.new("Ada");
                return match (PersonSql.field(Person::name)) {
                    case Option.Some(field) -> field.get(person);
                    case Option.None() -> "missing";
                };
            }
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
                assertEquals(41, main.getMethod("writesAge").invoke(null));
                assertTrue((Boolean) main.getMethod("readOnlyNameHasNoWriter").invoke(null));
                assertEquals("displayName", main.getMethod("documentExternalName").invoke(null));
                assertEquals("people", main.getMethod("documentCollection").invoke(null));
                assertTrue((Boolean) main.getMethod("mapsAge").invoke(null));
                assertTrue((Boolean) main.getMethod("ignoresUnmappedProperty").invoke(null));
                assertEquals("users", main.getMethod("passesModelAsContractValue").invoke(null));
                assertEquals("users", main.getMethod("bindsModelToContract").invoke(null));
                assertEquals(2, main.getMethod("genericModelFieldCount").invoke(null));
                assertEquals(1, main.getMethod("genericDocumentFieldCount").invoke(null));
                assertEquals(1, main.getMethod("bindsModelToCoreContract").invoke(null));
                assertTrue((Boolean) main.getMethod("contractPropertyDoesNotFindClassMapping")
                        .invoke(null));
                assertEquals("Ada", main.getMethod("contractTypedLookup").invoke(null));
                assertEquals(0, main.getMethod("emptyModelHasNoFields").invoke(null));
                assertEquals("user_name,user_age", main.getMethod("fieldOrder").invoke(null));
                assertEquals("user_name", main.getMethod("codeModelPropertyLookup").invoke(null));
                assertEquals(7, main.getMethod("genericTargetReads").invoke(null));
                assertTrue((Boolean) main.getMethod("sourceIdMatchesReference").invoke(null));
                assertTrue((Boolean) main.getMethod("unmappedTypedLookupFindsNothing").invoke(null));
                assertEquals(9, main.getMethod("writesThroughCustomSetter").invoke(null));
                final var modelClass = loader.loadClass("modeltest.UserSql$model");
                assertTrue(java.util.Arrays.stream(modelClass.getDeclaredConstructors())
                        .allMatch(constructor -> java.lang.reflect.Modifier.isPublic(
                                constructor.getModifiers())));
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
                assertEquals(44, main.getMethod("writesAge").invoke(null));
                assertEquals("users", main.getMethod("passesModelAsContractValue").invoke(null));
                assertEquals(2, main.getMethod("genericModelFieldCount").invoke(null));
            }
        } finally {
            deleteTree(libraryOutput);
            deleteTree(clientOutput);
        }
    }

    @Test
    public void modelsCanTargetTypesFromAnotherLibrary() throws Exception {
        final var targetOutput = Files.createTempDirectory(Path.of("target"), "zeron-foreign-target-");
        final var modelOutput = Files.createTempDirectory(Path.of("target"), "zeron-foreign-model-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-foreign-client-");
        try {
            final var target = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(parse("Target.zn", FOREIGN_TARGET_SOURCE))),
                    "forgeintarget.Target", "forgeintarget",
                    List.of(), true, List.of(), targetOutput);
            target.resolve();
            target.compile();
            final var targetIndex = ZeronLibraryIndex.readFromDirectory(targetOutput);

            final var model = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(parse("Model.zn", FOREIGN_MODEL_SOURCE))),
                    "foreignmodel.Model", "foreignmodel",
                    List.of(targetIndex), true, List.of(), modelOutput);
            model.resolve();
            model.compile();
            final var modelIndex = ZeronLibraryIndex.readFromDirectory(modelOutput);
            final var export = modelIndex.declarations().stream()
                    .filter(ZeronLibraryIndex.ModelExport.class::isInstance)
                    .map(ZeronLibraryIndex.ModelExport.class::cast)
                    .findFirst().orElseThrow();
            assertEquals("foreignmodel.PersonSql", export.qualifiedName());
            assertEquals("forgeintarget.Person", export.targetType().name());

            final var client = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(parse("Client.zn", FOREIGN_CLIENT_SOURCE))),
                    "foreignclient.Main", "foreignclient",
                    List.of(targetIndex, modelIndex), false, List.of(), clientOutput);
            client.resolve();
            client.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), modelOutput.toUri().toURL(),
                    targetOutput.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass("foreignclient.Main");
                assertEquals("person_name", main.getMethod("externalName").invoke(null));
                assertEquals("Ada", main.getMethod("reads").invoke(null));
            }
        } finally {
            deleteTree(targetOutput);
            deleteTree(modelOutput);
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
    public void rejectsInaccessiblePropertyMappings() throws Exception {
        assertRejected(SOURCE.replace("public model UserDoc", """
                public model HiddenSql is SqlModel<Hidden> {
                    tableName = "hidden";

                    secret -> "secret";
                }

                public model UserDoc"""));
    }

    @Test
    public void rejectsDuplicateContractPropertyAssignments() throws Exception {
        assertRejected(SOURCE.replace("tableName = \"users\";",
                "tableName = \"users\";\n    tableName = \"users_again\";"));
    }

    @Test
    public void rejectsUnknownContractPropertyAssignments() throws Exception {
        assertRejected(SOURCE.replace("tableName = \"users\";", "missing = \"users\";"));
    }

    @Test
    public void rejectsTypeMismatchedContractPropertyAssignments() throws Exception {
        assertRejected(SOURCE.replace("tableName = \"users\";", "tableName = 42;"));
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
        return parse("ModelTest.zn", source);
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        final var result = Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnitWithDiagnostics(sourcePath);
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
