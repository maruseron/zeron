package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

/// Validates the composed example of ZEP-0081, step 5: a consumer library that combines a model,
/// a code model, a receiver lambda, and a trailing lambda without compiler knowledge of its domain.
public final class DslCompositionTest {
    private static final String SOURCE = """
            package dslcomposition;

            import zeron.compiler.CodeLiteral;
            import zeron.compiler.CodeModel;
            import zeron.compiler.CodeNode;
            import zeron.compiler.CodeShape;
            import zeron.compiler.ComparisonOperator;
            import zeron.compiler.selectedProperty;
            import zeron.lang.Model;
            import zeron.lang.Option;

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

            public model UserLite is SqlModel<User> {
                tableName = "lite";

                name -> "user_name";
            }

            fn comparisonText(comparison: ComparisonOperator): String = match (comparison) {
                case ComparisonOperator.Equal() -> " = ";
                case ComparisonOperator.NotEqual() -> " <> ";
                case ComparisonOperator.LessThan() -> " < ";
                case ComparisonOperator.LessThanOrEqual() -> " <= ";
                case ComparisonOperator.GreaterThan() -> " > ";
                case ComparisonOperator.GreaterThanOrEqual() -> " >= ";
            };

            fn literalText(node: CodeNode): String = match (node.shape) {
                case CodeShape.LiteralNode(literal) -> match (literal) {
                    case CodeLiteral.NullLiteral() -> "null";
                    case CodeLiteral.BooleanLiteral(value) -> if (value) then "true" else "false";
                    case CodeLiteral.StringLiteral(value) -> "'" + value + "'";
                    case CodeLiteral.IntegerLiteral(text) -> text;
                    case CodeLiteral.DecimalLiteral(text) -> text;
                    case CodeLiteral.FloatLiteral(_) -> "?";
                };
                case _ -> "?";
            };

            fn translatePredicate<T: nullable + mut>(
                predicate: CodeModel<(T) -> Boolean>, model: Model<T>
            ): String = match (predicate.body.shape) {
                case CodeShape.ComparisonNode(comparison, left, right) ->
                    match (left.shape) {
                        case CodeShape.PropertyAccessNode(_, propertyId) ->
                            match (model.fieldOf(propertyId)) {
                                case Option.Some(field) ->
                                    field.externalName
                                        + comparisonText(comparison)
                                        + literalText(right);
                                case Option.None() -> "unmapped:" + propertyId.text();
                            };
                        case _ -> "unsupported";
                    };
                case _ -> "unsupported";
            };

            public class QueryScope<T: nullable + mut> {
                public property model: SqlModel<T>;
                public mut property clause: String;
                public mut property ordering: String;

                public constructor new;

                public mut where(predicate: CodeModel<(T) -> Boolean>): Unit {
                    this.clause = translatePredicate<T>(predicate, this.model);
                }

                public mut orderBy<V: nullable + mut>(selector: CodeModel<(T) -> V>): Unit {
                    let selected = selectedProperty(selector);
                    if (selected == null) {
                        this.ordering = "unselected";
                        return;
                    }
                    this.ordering = match (this.model.fieldOf(selected!!)) {
                        case Option.Some(field) -> field.externalName;
                        case Option.None() -> "unmapped:" + selected!!.text();
                    };
                }
            }

            public class QueryResult<T: nullable + mut> {
                public property clause: String;
                public property ordering: String;

                public constructor new;
            }

            public class Database {
                public constructor new;

                public query<T: nullable + mut>(
                    model: SqlModel<T>, body: &QueryScope<T>.() -> Unit
                ): QueryResult<T> {
                    let scope = QueryScope<T>.new(model, "", "");
                    scope.body();
                    return QueryResult<T>.new(scope.clause, scope.ordering);
                }
            }

            public fn composed(): String {
                let database = Database.new();
                let result = database.query(UserSql) {
                    where(user -> user.age >= 18);
                    orderBy(User::name);
                };
                return result.clause + "|" + result.ordering;
            }

            public fn unmappedPropertyIsReported(): String {
                let database = Database.new();
                let result = database.query(UserLite) {
                    where(user -> user.age >= 18);
                };
                return result.clause;
            }

            public fn referenceAndLambdaSelectorsAgree(): Boolean {
                let database = Database.new();
                let fromReference = database.query(UserSql) {
                    orderBy(User::name);
                };
                let fromLambda = database.query(UserSql) {
                    orderBy(user -> user.name);
                };
                return fromReference.ordering == fromLambda.ordering
                    and fromReference.ordering == "user_name";
            }
            """;

    @Test
    public void composesModelsCodeModelsAndReceiverLambdas() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-dsl-composition-");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(parse(SOURCE))),
                    "dslcomposition.Main", "dslcomposition", List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass("dslcomposition.Main");
                assertEquals("user_age >= 18|user_name", main.getMethod("composed").invoke(null));
                assertEquals("unmapped:dslcomposition.User#age",
                        main.getMethod("unmappedPropertyIsReported").invoke(null));
                assertEquals(Boolean.TRUE,
                        main.getMethod("referenceAndLambdaSelectorsAgree").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final String source) {
        final var result = Parser.of(Scanner.from(source, "DslCompositionTest.zn").scanTokens())
                .parseCompilationUnitWithDiagnostics("DslCompositionTest.zn");
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
