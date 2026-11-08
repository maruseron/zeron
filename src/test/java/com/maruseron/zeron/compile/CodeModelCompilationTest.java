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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class CodeModelCompilationTest {
    private static final String SOURCE_PATH = "CodeModelTest.zn";

    private static final String FIXTURE = """
            package codemodelfixture;

            import zeron.compiler.CaptureValue;
            import zeron.compiler.CodeCapture;
            import zeron.compiler.CodeLiteral;
            import zeron.compiler.CodeModel;
            import zeron.compiler.CodeNode;
            import zeron.compiler.CodeShape;
            import zeron.compiler.CodeType;
            import zeron.compiler.ComparisonOperator;
            import zeron.compiler.SourceLocation;
            import zeron.compiler.selectedProperty;
            import zeron.lang.PropertyId;

            public contract Named {
                property name: String;
            }

            public class User is Named deriving Eq {
                public constructor new;
                firstNameValue: String;
                nameValue: String;
                ageValue: Int;
                emailValue: String?;
                activeValue: Boolean;
                ratioValue: Float;
                gradeValue: Char;
                public property firstName: String { get = firstNameValue; }
                public property name: String { get = nameValue; }
                public property age: Int { get = ageValue; }
                public property email: String? { get = emailValue; }
                public property active: Boolean { get = activeValue; }
                public property ratio: Float { get = ratioValue; }
                public property grade: Char { get = gradeValue; }
            }

            public class Box<T: nullable + mut> {
                public constructor new;
                public property value: T;
            }

            fn preserveIdentity(model: CodeModel<(User) -> User>)
                : CodeModel<(User) -> User> = model;

            fn preserveNumber(model: CodeModel<(Int) -> Int>)
                : CodeModel<(Int) -> Int> = model;

            fn preserveInt(model: CodeModel<(Int) -> Boolean>)
                : CodeModel<(Int) -> Boolean> = model;

            fn preserveLong(model: CodeModel<(Long) -> Boolean>)
                : CodeModel<(Long) -> Boolean> = model;

            fn preserveFloat(model: CodeModel<(Float) -> Boolean>)
                : CodeModel<(Float) -> Boolean> = model;

            fn preserveUser(model: CodeModel<(User) -> Boolean>)
                : CodeModel<(User) -> Boolean> = model;

            fn preserveText(model: CodeModel<(User) -> String>)
                : CodeModel<(User) -> String> = model;

            fn isParameter(shape: CodeShape, position: Int): Boolean = match (shape) {
                case CodeShape.ParameterNode(actual) -> actual == position;
                case _ -> false;
            };

            fn isCapture(shape: CodeShape, slot: Int): Boolean = match (shape) {
                case CodeShape.CaptureNode(actual) -> actual == slot;
                case _ -> false;
            };

            fn isPropertyOf(shape: CodeShape, identity: String): Boolean = match (shape) {
                case CodeShape.PropertyAccessNode(receiver, propertyId) ->
                    propertyId.text() == identity and isParameter(receiver.shape, 0);
                case _ -> false;
            };

            fn literalTag(literal: CodeLiteral): String = match (literal) {
                case CodeLiteral.NullLiteral() -> "null";
                case CodeLiteral.BooleanLiteral(_) -> "boolean";
                case CodeLiteral.StringLiteral(_) -> "string";
                case CodeLiteral.IntegerLiteral(_) -> "integer";
                case CodeLiteral.DecimalLiteral(_) -> "decimal";
                case CodeLiteral.FloatLiteral(_) -> "float";
            };

            fn literalText(literal: CodeLiteral): String = match (literal) {
                case CodeLiteral.NullLiteral() -> "null";
                case CodeLiteral.BooleanLiteral(value) -> if (value) then "true" else "false";
                case CodeLiteral.StringLiteral(value) -> value;
                case CodeLiteral.IntegerLiteral(decimalText) -> decimalText;
                case CodeLiteral.DecimalLiteral(decimalText) -> decimalText;
                case CodeLiteral.FloatLiteral(_) -> "float";
            };

            fn rightLiteralTag(node: CodeNode): String = match (node.shape) {
                case CodeShape.LiteralNode(literal) -> literalTag(literal);
                case _ -> "none";
            };

            fn comparisonLiteralTag(node: CodeNode): String = match (node.shape) {
                case CodeShape.ComparisonNode(_, _, right) -> rightLiteralTag(right);
                case _ -> "none";
            };

            fn isComparisonOnProperty(shape: CodeShape, identity: String): Boolean =
                match (shape) {
                    case CodeShape.ComparisonNode(_, left, _) -> isPropertyOf(left.shape, identity);
                    case _ -> false;
                };

            fn captureTag(capture: CodeCapture): String = match (capture.value) {
                case CaptureValue.LiteralCapture(literal) -> "literal:" + literalTag(literal);
                case CaptureValue.OpaqueCapture(_) -> "opaque";
            };

            fn captureText(capture: CodeCapture): String = match (capture.value) {
                case CaptureValue.LiteralCapture(literal) -> literalText(literal);
                case CaptureValue.OpaqueCapture(_) -> "";
            };

            fn describeType(codeType: CodeType): String = match (codeType) {
                case CodeType.NominalType(name, arguments) ->
                    name + typeArgumentsText(arguments);
                case CodeType.NullableType(inner) -> describeType(inner) + "?";
                case CodeType.FunctionType(parameters, _) ->
                    "(" + typeArgumentsText(parameters) + ") -> ...";
            };

            fn typeArgumentsText(types: Array<CodeType>): String {
                if (types.length == 0) return "";
                let mut text = "<";
                let mut index = 0;
                while (index < types.length) {
                    if (index > 0) text = text + ",";
                    text = text + describeType(types[index]);
                    index += 1;
                }
                return text + ">";
            }

            fn selectedText<F>(model: CodeModel<F>): String {
                let selected = selectedProperty(model);
                if (selected == null) return "none";
                return selected!!.text();
            }

            fn thresholdAbove(threshold: Int): CodeModel<(Int) -> Boolean> =
                preserveInt(value -> value > threshold);

            public fn makeUser(): User = User.new("Ada", "Ada", 30, null, true, 0.5, 'A');

            public fn parameterNode(): Boolean = match (preserveIdentity(user -> user).body.shape) {
                case CodeShape.ParameterNode(position) -> position == 0;
                case _ -> false;
            };

            public fn captureNode(captured: User): Boolean =
                match (preserveIdentity(user -> captured).body.shape) {
                    case CodeShape.CaptureNode(slot) -> slot == 0;
                    case _ -> false;
                };

            public fn literalNode(): String =
                rightLiteralTag(preserveNumber(value -> 7).body);

            public fn propertyAccessNode(): Boolean =
                isPropertyOf(preserveText(user -> user.name).body.shape,
                    "codemodelfixture.User#name");

            public fn comparisonNode(threshold: Int): Boolean =
                match (preserveInt(value -> value >= threshold).body.shape) {
                    case CodeShape.ComparisonNode(comparison, left, right) ->
                        operatorTag(comparison) == "greaterThanOrEqual"
                            and isParameter(left.shape, 0)
                            and isCapture(right.shape, 0);
                    case _ -> false;
                };

            public fn comparisonOperatorTags(value: Int): String = match (value) {
                case 0 -> comparisonTag(preserveInt(item -> item == value));
                case 1 -> comparisonTag(preserveInt(item -> item != value));
                case 2 -> comparisonTag(preserveInt(item -> item < value));
                case 3 -> comparisonTag(preserveInt(item -> item <= value));
                case 4 -> comparisonTag(preserveInt(item -> item > value));
                case _ -> comparisonTag(preserveInt(item -> item >= value));
            };

            fn comparisonTag(model: CodeModel<(Int) -> Boolean>): String =
                match (model.body.shape) {
                    case CodeShape.ComparisonNode(comparison, _, _) -> operatorTag(comparison);
                    case _ -> "none";
                };

            fn operatorTag(comparison: ComparisonOperator): String = match (comparison) {
                case ComparisonOperator.Equal() -> "equal";
                case ComparisonOperator.NotEqual() -> "notEqual";
                case ComparisonOperator.LessThan() -> "lessThan";
                case ComparisonOperator.LessThanOrEqual() -> "lessThanOrEqual";
                case ComparisonOperator.GreaterThan() -> "greaterThan";
                case ComparisonOperator.GreaterThanOrEqual() -> "greaterThanOrEqual";
            };

            public fn andNode(): Boolean = match (preserveUser(user -> user.active and user.age > 3).body.shape) {
                case CodeShape.AndNode(left, right) ->
                    isPropertyOf(left.shape, "codemodelfixture.User#active")
                        and isComparisonOnProperty(right.shape, "codemodelfixture.User#age");
                case _ -> false;
            };

            public fn orNode(): Boolean = match (preserveUser(user -> user.active or user.age > 3).body.shape) {
                case CodeShape.OrNode(left, right) ->
                    isPropertyOf(left.shape, "codemodelfixture.User#active")
                        and isComparisonOnProperty(right.shape, "codemodelfixture.User#age");
                case _ -> false;
            };

            public fn notNode(): Boolean = match (preserveUser(user -> not user.active).body.shape) {
                case CodeShape.NotNode(operand) ->
                    isPropertyOf(operand.shape, "codemodelfixture.User#active");
                case _ -> false;
            };

            public fn nullTestNode(): Boolean = match (preserveUser(user -> user.email is null).body.shape) {
                case CodeShape.NullTestNode(operand, isNull) ->
                    isNull and isPropertyOf(operand.shape, "codemodelfixture.User#email");
                case _ -> false;
            };

            public fn childrenOfPredicate(threshold: Int): Int {
                let body = preserveInt(value -> value >= threshold).body;
                let children = body.children();
                if (children.length != 2) return -1;
                if (not isParameter(children[0].shape, 0)) return -1;
                if (not isCapture(children[1].shape, 0)) return -1;
                if (children[0].children().length != 0) return -1;
                return children.length;
            }

            public fn intLiteralTag(): String = comparisonLiteralTag(preserveInt(value -> value > 5).body);

            public fn longLiteralTag(): String = comparisonLiteralTag(preserveLong(value -> value > 5L).body);

            public fn floatLiteralTag(): String = comparisonLiteralTag(preserveFloat(value -> value > 0.5).body);

            public fn stringLiteralTag(): String =
                comparisonLiteralTag(preserveUser(user -> user.name == "Ada").body);

            public fn booleanLiteralTag(): String =
                comparisonLiteralTag(preserveUser(user -> user.active == true).body);

            public fn nullLiteralTag(): String =
                comparisonLiteralTag(preserveUser(user -> user.email == null).body);

            public fn literalNodeType(): String = match (preserveNumber(value -> 7).body.sourceType) {
                case CodeType.NominalType(name, _) -> name;
                case _ -> "other";
            };

            public fn nullTestOperandType(): String =
                match (preserveUser(user -> user.email is null).body.shape) {
                    case CodeShape.NullTestNode(operand, _) -> describeType(operand.sourceType);
                    case _ -> "none";
                };

            public fn captureIntegerTag(): String = captureTag(thresholdModel(42).captures[0]);

            public fn captureIntegerText(): String = captureText(thresholdModel(42).captures[0]);

            fn thresholdModel(threshold: Int): CodeModel<(Int) -> Boolean> =
                preserveInt(value -> value > threshold);

            public fn captureIntegerSlot(): Int = thresholdModel(42).captures[0].slot;

            public fn captureIntegerType(): String =
                describeType(thresholdModel(42).captures[0].sourceType);

            public fn captureLongType(threshold: Long): String =
                describeType(preserveLong(value -> value > threshold).captures[0].sourceType);

            public fn captureNullableTag(value: String?): String =
                captureTag(preserveUser(user -> user.email == value).captures[0]);

            public fn captureNullableType(value: String?): String =
                describeType(preserveUser(user -> user.email == value).captures[0].sourceType);

            public fn captureNullableText(value: String?): String =
                captureText(preserveUser(user -> user.email == value).captures[0]);

            public fn captureOpaqueTag(mark: Char): String =
                captureTag(preserveUser(user -> user.grade == mark).captures[0]);

            public fn captureOpaqueType(mark: Char): String =
                describeType(preserveUser(user -> user.grade == mark).captures[0].sourceType);

            public fn nameInsensitiveEquality(): Boolean {
                let first = preserveUser(alpha -> alpha.age > 18 and alpha.active);
                let second = preserveUser(beta -> beta.age > 18 and beta.active);
                return first == second and first.hash() == second.hash();
            }

            public fn captureValueInsensitiveEquality(): Boolean {
                let low = thresholdAbove(10);
                let high = thresholdAbove(20);
                return low == high and low.hash() == high.hash();
            }

            public fn captureCountIsStructural(threshold: Int, ceiling: Int): Boolean =
                thresholdAbove(threshold)
                    != preserveInt(value -> value > threshold and value < ceiling);

            public fn distinctPredicates(): Boolean =
                preserveUser(user -> user.age > 18) != preserveUser(user -> user.age > 21);

            public fn captureSlotOrder(low: Int, high: Int): String =
                describeCaptureSlots(preserveInt(value -> value > low and value < high));

            fn describeCaptureSlots(model: CodeModel<(Int) -> Boolean>): String {
                let mut text = "";
                for (let capture in model.captures) {
                    if (capture.slot == 0) text = text + "0";
                    if (capture.slot == 1) text = text + "1";
                }
                return text;
            }

            public fn modelLocationFile(): String = preserveUser(user -> user.active).location.file;

            public fn modelLocationLine(): Int = preserveUser(user -> user.active).location.startLine;

            public fn nodeLocationLine(): Int =
                preserveUser(user -> user.active and user.age > 3).body.location.startLine;

            public fn synthesizedLocationFile(): String = preserveText(User::name).location.file;

            public fn synthesizedLocationLine(): Int = preserveText(User::name).location.startLine;

            public fn lambdaLocationLine(): Int =
                preserveText/*marker*/(user -> user.name).location.startLine;

            public fn referenceMatchesLambda(): Boolean {
                let viaReference = preserveText(User::name);
                let viaLambda = preserveText(user -> user.name);
                return viaReference == viaLambda
                    and viaReference.hash() == viaLambda.hash()
                    and selectedText(viaReference) == selectedText(viaLambda);
            }

            public fn referenceMatchesLambdaSignature(): Boolean {
                let viaReference = preserveText(User::name);
                return describeType(viaReference.body.sourceType) == "String";
            }

            public fn selectedPropertyOfAccess(): String = selectedText(preserveUser(user -> user.active));

            public fn selectedPropertyOfPredicate(): String = selectedText(preserveUser(user -> user.age > 3));

            public fn selectedPropertyOfParameter(): String = selectedText(preserveIdentity(user -> user));

            public fn identityText(): String = (User::name).id.text();

            public fn referenceIdentityText(): String = (User::name).identity;

            public fn contractIdentityText(): String = (Named::name).id.text();

            public fn distinctIdentities(): Boolean = (User::name).id != (Named::name).id;

            public fn genericIdentityText(): String = (Box<Int>::value).id.text();

            public fn genericIdentityStable(): Boolean =
                (Box<Int>::value).id == (Box<String>::value).id;

            public fn genericModelIdentity(): String =
                selectedText(preserveValue(Box<Int>::value));

            fn preserveValue(model: CodeModel<(Box<Int>) -> Int>)
                : CodeModel<(Box<Int>) -> Int> = model;
            """;

    @Test
    public void producesEveryNodeVariant() throws Exception {
        withCompiledFixture((main, loader) -> {
            assertEquals(Boolean.TRUE, invoke(main, "parameterNode"));
            assertEquals(Boolean.TRUE, invoke(main, "captureNode", invoke(main, "makeUser")));
            assertEquals("integer", invoke(main, "literalNode"));
            assertEquals(Boolean.TRUE, invoke(main, "propertyAccessNode"));
            assertEquals(Boolean.TRUE, invoke(main, "comparisonNode", 0));
            assertEquals(2, invoke(main, "childrenOfPredicate", 7));
        });
    }

    @Test
    public void tagsComparisonsAndLogicalNodes() throws Exception {
        withCompiledFixture((main, loader) -> {
            assertEquals("equal", invoke(main, "comparisonOperatorTags", 0));
            assertEquals("notEqual", invoke(main, "comparisonOperatorTags", 1));
            assertEquals("lessThan", invoke(main, "comparisonOperatorTags", 2));
            assertEquals("lessThanOrEqual", invoke(main, "comparisonOperatorTags", 3));
            assertEquals("greaterThan", invoke(main, "comparisonOperatorTags", 4));
            assertEquals("greaterThanOrEqual", invoke(main, "comparisonOperatorTags", 5));
            assertEquals(Boolean.TRUE, invoke(main, "andNode"));
            assertEquals(Boolean.TRUE, invoke(main, "orNode"));
            assertEquals(Boolean.TRUE, invoke(main, "notNode"));
            assertEquals(Boolean.TRUE, invoke(main, "nullTestNode"));
        });
    }

    @Test
    public void tagsLiteralsByTheirSourceType() throws Exception {
        withCompiledFixture((main, loader) -> {
            assertEquals("integer", invoke(main, "intLiteralTag"));
            assertEquals("integer", invoke(main, "longLiteralTag"));
            assertEquals("float", invoke(main, "floatLiteralTag"));
            assertEquals("string", invoke(main, "stringLiteralTag"));
            assertEquals("boolean", invoke(main, "booleanLiteralTag"));
            assertEquals("null", invoke(main, "nullLiteralTag"));
            assertEquals("Int", invoke(main, "literalNodeType"));
            assertEquals("String?", invoke(main, "nullTestOperandType"));
        });
    }

    @Test
    public void typesCaptureValuesByKind() throws Exception {
        withCompiledFixture((main, loader) -> {
            assertEquals("literal:integer", invoke(main, "captureIntegerTag"));
            assertEquals("42", invoke(main, "captureIntegerText"));
            assertEquals(0, invoke(main, "captureIntegerSlot"));
            assertEquals("Int", invoke(main, "captureIntegerType"));
            assertEquals("Long", invoke(main, "captureLongType", 42L));

            assertEquals("literal:string", invoke(main, "captureNullableTag", "ada@example.com"));
            assertEquals("String?", invoke(main, "captureNullableType", "ada@example.com"));
            assertEquals("ada@example.com", invoke(main, "captureNullableText", "ada@example.com"));
            assertEquals("literal:null", invoke(main, "captureNullableTag", (Object) null));
            assertEquals("null", invoke(main, "captureNullableText", (Object) null));

            assertEquals("opaque", invoke(main, "captureOpaqueTag", 'A'));
            assertEquals("Char", invoke(main, "captureOpaqueType", 'A'));
        });
    }

    @Test
    public void comparesModelsStructurally() throws Exception {
        withCompiledFixture((main, loader) -> {
            assertEquals(Boolean.TRUE, invoke(main, "nameInsensitiveEquality"));
            assertEquals(Boolean.TRUE, invoke(main, "captureValueInsensitiveEquality"));
            assertEquals(Boolean.TRUE, invoke(main, "captureCountIsStructural", 3, 9));
            assertEquals(Boolean.TRUE, invoke(main, "distinctPredicates"));
            assertEquals("01", invoke(main, "captureSlotOrder", 3, 9));
        });
    }

    @Test
    public void reportsSourceLocations() throws Exception {
        withCompiledFixture((main, loader) -> {
            assertEquals(SOURCE_PATH, invoke(main, "modelLocationFile"));
            assertEquals(lineContaining(FIXTURE, "preserveUser(user -> user.active).location.startLine"),
                    invoke(main, "modelLocationLine"));
            assertEquals(lineContaining(FIXTURE,
                            "preserveUser(user -> user.active and user.age > 3).body.location.startLine"),
                    invoke(main, "nodeLocationLine"));
            assertEquals(SOURCE_PATH, invoke(main, "synthesizedLocationFile"));
            assertEquals(lineContaining(FIXTURE, "preserveText(User::name).location.startLine"),
                    invoke(main, "synthesizedLocationLine"));
            assertEquals(lineContaining(FIXTURE, "preserveText/*marker*/(user -> user.name)"),
                    invoke(main, "lambdaLocationLine"));
        });
    }

    @Test
    public void acceptsPropertyReferenceLiteralsAsCodeModels() throws Exception {
        withCompiledFixture((main, loader) -> {
            assertEquals(Boolean.TRUE, invoke(main, "referenceMatchesLambda"));
            assertEquals(Boolean.TRUE, invoke(main, "referenceMatchesLambdaSignature"));
            assertEquals("codemodelfixture.User#active", invoke(main, "selectedPropertyOfAccess"));
            assertEquals("none", invoke(main, "selectedPropertyOfPredicate"));
            assertEquals("none", invoke(main, "selectedPropertyOfParameter"));
        });
    }

    @Test
    public void unifiesPropertyIdentity() throws Exception {
        withCompiledFixture((main, loader) -> {
            assertEquals("codemodelfixture.User#name", invoke(main, "identityText"));
            assertEquals(invoke(main, "referenceIdentityText"), invoke(main, "identityText"));
            assertEquals("codemodelfixture.Named#name", invoke(main, "contractIdentityText"));
            assertEquals(Boolean.TRUE, invoke(main, "distinctIdentities"));
            assertEquals("codemodelfixture.Box#value", invoke(main, "genericIdentityText"));
            assertEquals(Boolean.TRUE, invoke(main, "genericIdentityStable"));
            assertEquals("codemodelfixture.Box#value", invoke(main, "genericModelIdentity"));
        });
    }

    @Test
    public void rejectsUnsupportedExpressionsAndFunctionValues() {
        assertResolutionFails("codemodelcall", """
                import zeron.compiler.CodeModel;
                fn consume(model: CodeModel<(Int) -> Boolean>): Unit {}
                fn invalid(): Unit = consume(value -> value + 1 > 0);
                """);
        assertResolutionFails("codemodelstored", """
                import zeron.compiler.CodeModel;
                fn consume(model: CodeModel<(Int) -> Boolean>): Unit {}
                fn predicate(value: Int): Boolean = value > 0;
                fn invalid(): Unit = consume(predicate);
                """);
        assertResolutionFails("codemodelmutable", """
                import zeron.compiler.CodeModel;
                fn consume(model: CodeModel<(Int) -> Boolean>): Unit {}
                fn invalid(threshold: Int): Unit {
                    let mut bound = threshold;
                    consume(value -> value > bound);
                }
                """);
        assertResolutionFails("codemodelpropertyrefvalue", """
                import zeron.compiler.CodeModel;
                import zeron.lang.PropertyRef;
                public class User {
                    public property name: String;
                    public constructor new;
                }
                fn consume(model: CodeModel<(User) -> String>): Unit {}
                fn invalid(reference: PropertyRef<User, String>): Unit = consume(reference);
                """);
    }

    private static void assertResolutionFails(final String packageName, final String body) {
        final var compiler = compilerFor(packageName, """
                package %s;
                %s
                """.formatted(packageName, body), Path.of("target", "zeron-" + packageName));
        assertThrows(ResolutionError.class, compiler::resolve);
    }

    private interface FixtureCheck {
        void run(Class<?> main, ClassLoader loader) throws Exception;
    }

    private static void withCompiledFixture(final FixtureCheck check) throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-code-model-");
        final var unit = parse(SOURCE_PATH, FIXTURE);
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    "codemodelfixture.Main", "codemodelfixture", List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, CodeModelCompilationTest.class.getClassLoader())) {
                check.run(loader.loadClass("codemodelfixture.Main"), loader);
            }
        } finally {
            deleteTree(output);
        }
    }

    private static Object invoke(final Class<?> main, final String name, final Object... arguments)
            throws Exception {
        final var types = new Class<?>[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            final var argument = arguments[index];
            types[index] = argument == null ? String.class
                    : argument instanceof Integer ? int.class
                    : argument instanceof Long ? long.class
                    : argument instanceof Character ? char.class
                    : argument instanceof Class<?> type ? type
                    : argument.getClass();
        }
        if (name.equals("captureNullableTag") || name.equals("captureNullableType")
                || name.equals("captureNullableText")) {
            types[0] = String.class;
        }
        for (final var method : main.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            return method.invoke(null, arguments);
        }
        throw new AssertionError("No method '" + name + "' with " + arguments.length + " parameter(s)");
    }

    private static int lineContaining(final String source, final String needle) {
        final var lines = source.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains(needle)) return index + 1;
        }
        throw new AssertionError("Source does not contain '" + needle + "'");
    }

    private static CompilationService compilerFor(final String packageName,
                                                  final String source,
                                                  final Path output) {
        return CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(parse(SOURCE_PATH, source))),
                packageName + ".Main", packageName, List.of(), false, List.of(), output);
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
