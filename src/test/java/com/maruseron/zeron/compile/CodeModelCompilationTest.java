package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class CodeModelCompilationTest {
    @Test
    public void buildsTypedPredicateNodesAndEagerCaptureValues() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-code-model-");
        final var unit = parse("""
                package codemodelfixture;
                import zeron.compiler.CodeModel;

                fn preserve(@CodeModel model: CodeModel<(Int) -> Boolean>)
                    : CodeModel<(Int) -> Boolean> = model;

                public fn create(threshold: Int): CodeModel<(Int) -> Boolean> =
                    preserve(value -> value >= threshold);

                public class User {
                    public constructor new;
                    ageValue: Int;
                    activeValue: Boolean;
                    emailValue: String?;
                    public property age: Int { get = ageValue; }
                    public property active: Boolean { get = activeValue; }
                    public property email: String? { get = emailValue; }
                }

                fn preserveUser(@CodeModel model: CodeModel<(User) -> Boolean>)
                    : CodeModel<(User) -> Boolean> = model;

                public fn createUser(threshold: Int): CodeModel<(User) -> Boolean> =
                    preserveUser(user -> user.age >= threshold and user.active);

                public fn createNullUser(): CodeModel<(User) -> Boolean> =
                    preserveUser(user -> user.email is null);
                """);
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    "codemodelfixture.Main", "codemodelfixture",
                    List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass("codemodelfixture.Main");
                final var model = main.getMethod("create", int.class).invoke(null, 37);
                final var root = model.getClass().getMethod("root").invoke(model);
                assertEquals("LAMBDA", root.getClass().getMethod("kind").invoke(root));
                assertEquals(2, root.getClass().getMethod("childCount").invoke(root));

                final var predicate = root.getClass().getMethod("child", int.class).invoke(root, 1);
                assertEquals("GREATER_THAN_OR_EQUAL",
                        predicate.getClass().getMethod("kind").invoke(predicate));
                final var parameter = predicate.getClass().getMethod("child", int.class).invoke(predicate, 0);
                assertEquals(0, parameter.getClass().getMethod("slot").invoke(parameter));
                assertEquals(1, model.getClass().getMethod("captureCount").invoke(model));

                final var capture = model.getClass().getMethod("capture", int.class).invoke(model, 0);
                assertEquals(0, capture.getClass().getMethod("slot").invoke(capture));
                assertEquals(37, capture.getClass().getMethod("value").invoke(capture));

                final var userModel = main.getMethod("createUser", int.class).invoke(null, 42);
                final var userRoot = userModel.getClass().getMethod("root").invoke(userModel);
                final var userPredicate = userRoot.getClass().getMethod("child", int.class)
                        .invoke(userRoot, 1);
                assertEquals("AND", userPredicate.getClass().getMethod("kind").invoke(userPredicate));
                final var property = child(child(userPredicate, 0), 0);
                assertEquals("PROPERTY", property.getClass().getMethod("kind").invoke(property));
                assertEquals("codemodelfixture.User.age",
                        property.getClass().getMethod("identity").invoke(property));

                final var nullModel = main.getMethod("createNullUser").invoke(null);
                final var nullRoot = nullModel.getClass().getMethod("root").invoke(nullModel);
                final var nullTest = child(nullRoot, 1);
                assertEquals("IS_NULL", nullTest.getClass().getMethod("kind").invoke(nullTest));
                final var nullableProperty = child(nullTest, 0);
                assertEquals("PROPERTY", nullableProperty.getClass().getMethod("kind").invoke(nullableProperty));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsUnsupportedCallsAndNonInlineFunctions() {
        final var unsupported = parse("""
                package codemodelinvalid;
                import zeron.compiler.CodeModel;
                fn consume(@CodeModel model: CodeModel<(Int) -> Boolean>): Unit {}
                fn invalid(): Unit = consume(value -> value + 1 > 0);
                """);
        final var unsupportedCompiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(unsupported)),
                "codemodelinvalid.Main", "codemodelinvalid", List.of(), false, List.of(),
                Path.of("target", "zeron-code-model-invalid"));
        assertThrows(ResolutionError.class, unsupportedCompiler::resolve);

        final var reference = parse("""
                package codemodelfunctionref;
                import zeron.compiler.CodeModel;
                fn consume(@CodeModel model: CodeModel<(Int) -> Boolean>): Unit {}
                fn predicate(value: Int): Boolean = value > 0;
                fn invalid(): Unit = consume(predicate);
                """);
        final var referenceCompiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(reference)),
                "codemodelfunctionref.Main", "codemodelfunctionref", List.of(), false, List.of(),
                Path.of("target", "zeron-code-model-reference"));
        assertThrows(ResolutionError.class, referenceCompiler::resolve);
    }

    private static CompilationUnit parse(final String source) {
        return Parser.of(Scanner.from(source, "CodeModel.zn").scanTokens())
                .parseCompilationUnit("CodeModel.zn");
    }

    private static Object child(final Object node, final int index) throws Exception {
        return node.getClass().getMethod("child", int.class).invoke(node, index);
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
