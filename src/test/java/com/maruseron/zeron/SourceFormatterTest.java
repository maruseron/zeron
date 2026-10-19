package com.maruseron.zeron;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SourceFormatterTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void formatsDeclarationsAndPreservesCommentsAndSumCaseAlignment() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = """
                import zeta.value.Value;
                import alpha.value.Flag;
                // retained
                public type Choice=Text(value:String)|Flag(value:Boolean);
                public fn main(){let value=1;}
                """;

        final var result = SourceFormatter.format(source, "FormatterTest.zn", style);

        assertTrue(result.diagnostics().toString(), result.formattedSource().isPresent());
        final var formatted = result.formattedSource().orElseThrow();
        assertTrue(formatted.indexOf("import alpha.value.Flag;") < formatted.indexOf("import zeta.value.Value;"));
        assertTrue(formatted.contains("import alpha.value.Flag;\n\nimport zeta.value.Value;"));
        assertTrue(formatted.contains("// retained"));
        assertTrue(formatted.contains("public type Choice =\n      Text(value: String)\n    | Flag(value: Boolean);"));
        assertTrue(formatted.contains("public fn main() {\n    let value = 1;\n}"));
        final var repeated = SourceFormatter.format(formatted, "FormatterTest.zn", style);
        assertEquals(formatted, repeated.formattedSource().orElseThrow());
    }

    @Test
    public void preservesDetectedLineEndingsAndDoesNotFormatInvalidSource() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = "fn main(){\r\nlet value=1;\r\n}\r\n";
        final var formatted = SourceFormatter.format(source, "LineEndings.zn", style)
                .formattedSource().orElseThrow();
        assertTrue(formatted.contains("\r\n"));
        assertFalse(formatted.replace("\r\n", "").contains("\n"));

        final var invalid = SourceFormatter.format("fn main( {", "Invalid.zn", style);
        assertTrue(invalid.formattedSource().isEmpty());
        assertFalse(invalid.diagnostics().isEmpty());
    }

    @Test
    public void normalizesMultilineCommentLineEndingsToTheFileConvention() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = "fn main(){/* first\r\nsecond */let value=1;}\n";

        final var formatted = SourceFormatter.format(source, "CommentLines.zn", style)
                .formattedSource().orElseThrow();

        assertFalse(formatted.contains("\r\r\n"));
        assertFalse(formatted.replace("\r\n", "").contains("\n"));
        assertTrue(formatted.contains("/* first\r\nsecond */"));
    }

    @Test
    public void keepsTrailingCommentsAttachedAndDoesNotReorderCommentedImports() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = """
                import zeta.value.Value;
                // keep import order around comments
                import alpha.value.Flag;
                fn main(){let value=1;} // trailing
                fn next(){}
                """;

        final var formatted = SourceFormatter.format(source, "CommentOrder.zn", style)
                .formattedSource().orElseThrow();

        assertTrue(formatted.indexOf("import zeta.value.Value;")
                < formatted.indexOf("// keep import order around comments"));
        assertTrue(formatted.indexOf("// keep import order around comments")
                < formatted.indexOf("import alpha.value.Flag;"));
        assertTrue(formatted.contains("} // trailing\n\nfn next()"));
    }

    @Test
    public void doesNotReorderImportsWhenLocalNamesCollide() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = """
                import zeta.value.Value as Shared;
                import alpha.value.Flag as Shared;
                fn main(){}
                """;

        final var formatted = SourceFormatter.format(source, "AliasedImports.zn", style)
                .formattedSource().orElseThrow();

        assertTrue(formatted.indexOf("import zeta.value.Value as Shared;")
                < formatted.indexOf("import alpha.value.Flag as Shared;"));
    }

    @Test
    public void appliesFunctionBreakPriorityAndPreservesOperatorSpacing() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = """
                fn longName(firstParameter: String, secondParameter: String, thirdParameter: String): Boolean = firstParameter < secondParameter and secondParameter != thirdParameter and thirdParameter == firstParameter;
                """;

        final var formatted = SourceFormatter.format(source, "Wrapped.zn", style)
                .formattedSource().orElseThrow();

        assertTrue(formatted.contains("): Boolean =\n"));
        assertTrue(formatted.contains("firstParameter"));
        assertTrue(formatted.contains("< secondParameter"));
        assertFalse(formatted.contains("firstParameter<secondParameter"));
        final var longLines = formatted.lines().filter(line -> line.length() > style.maximumLineLength()).toList();
        assertTrue(longLines.stream().map(String::length).toList().toString(), longLines.isEmpty());
        assertEquals(formatted, SourceFormatter.format(formatted, "Wrapped.zn", style)
                .formattedSource().orElseThrow());
    }

    @Test
    public void wrapsFunctionParametersOnlyWhenTheHeaderExceedsWidth() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = """
                fn longName(firstParameterName: VeryLongTypeName, secondParameterName: AnotherVeryLongTypeName, thirdParameterName: YetAnotherLongTypeName): Boolean = true;
                """;

        final var formatted = SourceFormatter.format(source, "Parameters.zn", style)
                .formattedSource().orElseThrow();

        assertTrue(formatted.contains("fn longName(\n"));
        assertTrue(formatted.contains("    firstParameterName: VeryLongTypeName,"));
        assertTrue(formatted.contains("): Boolean = true;"));
        assertEquals(formatted, SourceFormatter.format(formatted, "Parameters.zn", style)
                .formattedSource().orElseThrow());
    }

    @Test
    public void formatsThrowsClausesAndDoesNotWrapShortMethodCalls() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = """
                public external class BufferedWriter = "java.io.BufferedWriter" {
                    public mut write(value: String, offset: Int, length: Int): Unit throws java.io.IOException -> IoFailure::fromIOException;
                }
                class Calls {
                    public value(): Int = answer(1, 2);
                }
                """;

        final var formatted = SourceFormatter.format(source, "Declarations.zn", style)
                .formattedSource().orElseThrow();

        assertTrue(formatted, formatted.contains("throws java.io.IOException -> IoFailure::fromIOException;"));
        assertTrue(formatted.contains("= answer(1, 2);"));
        assertTrue(formatted.lines().allMatch(line -> line.length() <= style.maximumLineLength()));
        assertEquals(formatted, SourceFormatter.format(formatted, "Declarations.zn", style)
                .formattedSource().orElseThrow());
    }

    @Test
    public void preservesKeywordParenthesisSpacingAndAlignsMatchArms() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = """
                effect Trouble {}
                fn negate(value: Boolean): Boolean = not(value);
                fn handled(): Int = handle(1) with {
                    case Trouble -> 1;
                };
                fn choose(value: Boolean): Int =
                    match(value) {
                        case true -> handle(1) with {
                            case Trouble -> not(false);
                        };
                        case false -> 0;
                    };
                """;

        final var result = SourceFormatter.format(source, "MatchLayout.zn", style);
        assertTrue(result.diagnostics().toString(), result.formattedSource().isPresent());
        final var formatted = result.formattedSource().orElseThrow();

        assertTrue(formatted, formatted.contains("= not (value);"));
        assertTrue(formatted, formatted.contains("= handle (1) with {"));
        assertTrue(formatted, formatted.contains("case true -> handle (1) with {"));
        assertTrue(formatted, formatted.contains(
                "case true -> handle (1) with {\n            case Trouble -> not (false);\n        };\n        case false -> 0;"));
        assertEquals(formatted, SourceFormatter.format(formatted, "MatchLayout.zn", style)
                .formattedSource().orElseThrow());
    }

    @Test
    public void keepsUnaryMinusAttachedToItsOperand() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = """
                fn negative(): Int = -1;
                fn returnNegative(): Int {
                    return -1;
                }
                fn subtract(value: Int): Int = value - 1;
                fn compareNegative(value: Int): Boolean = value == -1;
                fn multiplyNegative(): Int = 2 * -1;
                """;

        final var result = SourceFormatter.format(source, "UnaryMinus.zn", style);
        assertTrue(result.diagnostics().toString(), result.formattedSource().isPresent());
        final var formatted = result.formattedSource().orElseThrow();

        assertTrue(formatted, formatted.contains("= -1;"));
        assertTrue(formatted, formatted.contains("return -1;"));
        assertTrue(formatted, formatted.contains("value - 1;"));
        assertTrue(formatted, formatted.contains("value == -1;"));
        assertTrue(formatted, formatted.contains("2 * -1;"));
        assertFalse(formatted, formatted.contains("= - 1"));
        assertFalse(formatted, formatted.contains("== - 1"));
        assertFalse(formatted, formatted.contains("* - 1"));
        assertFalse(formatted, formatted.contains("return - 1"));
        assertEquals(formatted, SourceFormatter.format(formatted, "UnaryMinus.zn", style)
                .formattedSource().orElseThrow());
    }

    @Test
    public void keepsExternalDeclarationsCompactAndBreaksBeforeEffectsBeforeParameters() throws Exception {
        final var style = new CanonicalStyle(4, 56, true);
        final var source = """
                external fn invoke(first: Int, second: Int): Unit;
                external fn reset(): Unit;
                public external class Native = "Native" {
                    public first(): Unit;
                    public second(): Unit;
                    public invoke(first: Int, second: Int): Unit throws java.io.IOException -> IoFailure::fromIOException;
                }
                """;

        final var result = SourceFormatter.format(source, "ExternalDeclarations.zn", style);
        assertTrue(result.diagnostics().toString(), result.formattedSource().isPresent());
        final var formatted = result.formattedSource().orElseThrow();

        assertTrue(formatted,
                formatted.contains("external fn invoke(first: Int, second: Int): Unit;\nexternal fn reset(): Unit;"));
        assertTrue(formatted, formatted.contains("external fn reset(): Unit;\n\npublic external class Native"));
        assertTrue(formatted, formatted.contains("public first(): Unit;\n    public second(): Unit;"));
        assertTrue(formatted, formatted.contains(
                "public invoke(first: Int, second: Int): Unit\n        throws java.io.IOException -> IoFailure::fromIOException;"));
        assertEquals(formatted, SourceFormatter.format(formatted, "ExternalDeclarations.zn", style)
                .formattedSource().orElseThrow());
    }

    @Test
    public void formatsLongReturnTypesAndGenericClauseFollowers() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = """
                public contract Hashable<T> requires Eq<T> {
                    hash(): Int;
                }
                public contract Monad<F<_>> requires Applicative<F> {
                    flatMap<A, B>(this: F<A>, transform: (A) -> F<B>): F<B>;
                }
                class Derived is Comparable<Derived> deriving Eq {
                    value: Pair<Int, String>?;
                    public substringByCodePointsStrict(start: Int, end: Int): Result<Option<String>, UnicodeError> {}
                }
                public contract Reader {
                    read(buffer: &Pair<Int, String>...): Option<Int> raises IoFailure;
                }
                """;

        final var result = SourceFormatter.format(source, "GenericFollowers.zn", style);
        assertTrue(result.diagnostics().toString(), result.formattedSource().isPresent());
        final var formatted = result.formattedSource().orElseThrow();

        assertTrue(formatted, formatted.contains("contract Hashable<T> requires Eq<T> {"));
        assertTrue(formatted, formatted.contains("contract Monad<F<_>> requires Applicative<F> {"));
        assertTrue(formatted, formatted.contains("class Derived is Comparable<Derived> deriving Eq {"));
        assertTrue(formatted, formatted.contains("Pair<Int, String>?;"));
        assertTrue(formatted, formatted.contains("&Pair<Int, String>..."));
        assertTrue(formatted, formatted.contains("Option<Int> raises IoFailure;"));
        assertTrue(formatted.lines().allMatch(line -> line.length() <= style.maximumLineLength()));
        assertEquals(formatted, SourceFormatter.format(formatted, "GenericFollowers.zn", style)
                .formattedSource().orElseThrow());
    }

    @Test
    public void formatsGenericDeclarationBlocksAndSeparatesTheirMembers() throws Exception {
        final var style = CanonicalStyle.load();
        final var source = """
                class ArrayBox<T: Hashable<T>> is Foldable<ArrayBox<_>> {
                    value: String?;
                    count: Int;
                    implementation Foldable<ArrayBox<_>> {
                        fn foldLeft<A, B>(this: ArrayBox<A>, initial: B, combine: (B, A) -> B): B = this.combine(initial, combine);
                        fn fold<A, B>(this: ArrayBox<A>, initial: B, combine: (B, A) -> B): B = this.combine(initial, combine);
                    }
                }
                public extension<T> Array<T> {
                    public fn first(): T = this[0];
                }
                witness Eq<Int> for Int {
                    fn eq(other: Int): Boolean = true;
                }
                """;

        final var formatted = SourceFormatter.format(source, "GenericBlocks.zn", style)
                .formattedSource().orElseThrow();

        assertTrue(formatted,
                formatted.contains("class ArrayBox<T: Hashable<T>> is Foldable<ArrayBox<_>> {"));
        assertTrue(formatted, formatted.contains("value: String?;"));
        assertTrue(formatted, formatted.contains("value: String?;\n    count: Int;"));
        assertTrue(formatted,
                formatted.contains("count: Int;\n\n    implementation Foldable<ArrayBox<_>> {"));
        assertTrue(formatted, formatted.contains("implementation Foldable<ArrayBox<_>> {"));
        assertTrue(formatted, formatted.contains("public extension<T> Array<T> {"));
        assertTrue(formatted, formatted.contains("witness Eq<Int> for Int {"));
        assertTrue(formatted.contains("this.combine(initial, combine)"));
        assertFalse(formatted.contains("Hashable <"));
        assertFalse(formatted.contains("ArrayBox <"));
        assertEquals(formatted, SourceFormatter.format(formatted, "GenericBlocks.zn", style)
                .formattedSource().orElseThrow());
    }

    @Test
    public void doesNotBreakGenericTypeArgumentsInsideWrappedCalls() throws Exception {
        final var style = new CanonicalStyle(4, 50, true);
        final var source = "fn process(): Unit = outer(inner<Pair<Int, String>>(value), fallback);";

        final var formatted = SourceFormatter.format(source, "GenericCall.zn", style)
                .formattedSource().orElseThrow();

        assertTrue(formatted.contains("Pair<Int, String>"));
        assertFalse(formatted.contains("Pair<Int,\n"));
        assertEquals(formatted, SourceFormatter.format(formatted, "GenericCall.zn", style)
                .formattedSource().orElseThrow());
    }

    @Test
    public void formatsRepresentativeSourcesAsParseableIdempotentSource() throws Exception {
        final var style = CanonicalStyle.load();
        final var paths = List.of(
                java.nio.file.Path.of("src", "main", "resources", "stdlib", "java", "io", "BufferedWriter.zn"),
                java.nio.file.Path.of("src", "main", "resources", "stdlib", "zeron", "math", "math.zn"),
                java.nio.file.Path.of("src", "main", "resources", "stdlib", "zeron", "collections", "array.zn"),
                java.nio.file.Path.of("src", "main", "resources", "stdlib", "zeron", "collections",
                        "arrayiterator.zn"),
                java.nio.file.Path.of("src", "main", "resources", "stdlib", "zeron", "collections",
                        "iterable.zn"),
                java.nio.file.Path.of("src", "main", "resources", "stdlib", "zeron", "collections",
                        "list.zn"),
                java.nio.file.Path.of("src", "main", "resources", "stdlib", "zeron", "collections", "map.zn"),
                java.nio.file.Path.of("src", "main", "resources", "stdlib", "zeron", "lang", "option.zn"),
                java.nio.file.Path.of("src", "main", "resources", "stdlib", "zeron", "lang", "result.zn"),
                java.nio.file.Path.of("sample_programs", "query_engine", "src", "query", "value", "value.zn"));
        for (final var path : paths) {
            final var source = Files.readString(path, StandardCharsets.UTF_8);
            final var formatted = SourceFormatter.format(source, path.toString(), style);
            assertTrue(path + ": " + formatted.diagnostics(), formatted.formattedSource().isPresent());
            final var output = formatted.formattedSource().orElseThrow();
            assertTrue(path + ": source line exceeds the canonical width",
                    output.lines().allMatch(line -> line.length() <= style.maximumLineLength()));
            if (path.getFileName().toString().equals("iterable.zn")) {
                assertTrue(output.contains("public contract Iterable<T> {"));
                assertTrue(output.contains("iterator(): &Iterator<T>;"));
                assertTrue(output.contains("default stream(): Stream<T> = Stream.from(this);"));
                assertTrue(output.contains("for (let element in this) consumer(element);"));
            }
            if (path.getFileName().toString().equals("result.zn")) {
                assertTrue(path + ": wrapped an expression that fits the canonical width",
                        output.contains("case Result.Ok(value) -> Result<B, E>.Ok("
                                + "applyResultFunction(transform, value));"));
            }
            final var repeated = SourceFormatter.format(output, path.toString(), style);
            assertTrue(path + ": " + repeated.diagnostics(), repeated.formattedSource().isPresent());
            assertEquals(path.toString(), output, repeated.formattedSource().orElseThrow());
        }
    }

    @Test
    public void checkModeIsNonMutatingAndFormattingIsIdempotent() throws Exception {
        final var path = temporaryFolder.getRoot().toPath().resolve("Main.zn");
        final var source = "fn main(){let value=1;}";
        Files.writeString(path, source, StandardCharsets.UTF_8);

        final var previousOut = System.out;
        final var output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertEquals(1, Zeron.runCli("fmt", "--check", path.toString()));
            assertEquals(source, Files.readString(path, StandardCharsets.UTF_8));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("would reformat:"));
        } finally {
            System.setOut(previousOut);
        }

        assertEquals(0, Zeron.runCli("fmt", path.toString()));
        final var formatted = Files.readString(path, StandardCharsets.UTF_8);
        assertFalse(source.equals(formatted));
        assertEquals(0, Zeron.runCli("fmt", "--check", path.toString()));
    }

    @Test
    public void continuesFormattingAfterAnInvalidSelectedFile() throws Exception {
        final var root = temporaryFolder.getRoot().toPath();
        final var valid = root.resolve("A.zn");
        final var invalid = root.resolve("B.zn");
        final var invalidSource = "fn broken( {";
        Files.writeString(valid, "fn main(){let value=1;}", StandardCharsets.UTF_8);
        Files.writeString(invalid, invalidSource, StandardCharsets.UTF_8);

        assertEquals(65, Zeron.runCli("fmt", root.toString()));

        assertTrue(Files.readString(valid, StandardCharsets.UTF_8).contains("fn main() {"));
        assertEquals(invalidSource, Files.readString(invalid, StandardCharsets.UTF_8));
    }

}
