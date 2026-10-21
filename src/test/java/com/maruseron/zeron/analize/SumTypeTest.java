package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class SumTypeTest {
    @Test
    public void constructsMatchesAndSharesNullarySingletons() throws Exception {
        final var programName = "SumProgram" + UUID.randomUUID().toString().replace("-", "");
        final var someName = "Choice$$sum$Some";
        final var emptyName = "Choice$$sum$Empty";
        final var source = """
                type Choice<T> = Some(value: T) | Empty;
                fn payload(): Int = match (Choice.Some(42)) {
                    case Choice.Some(value) -> value;
                    case Choice.Empty() -> 0;
                };
                fn empty(): Choice<Int> = Choice.Empty();
                fn sameEmpty(): Boolean = Choice<Int>.Empty() === Choice<Int>.Empty();
                """;
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals(42, program.getMethod("payload").invoke(null));
                assertEquals(true, program.getMethod("sameEmpty").invoke(null));
                final var singleton = loader.loadClass(emptyName).getDeclaredField("$zeron$singleton");
                singleton.setAccessible(true);
                assertSame(program.getMethod("empty").invoke(null), singleton.get(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", "Choice.class"));
            Files.deleteIfExists(Path.of("dist", someName + ".class"));
            Files.deleteIfExists(Path.of("dist", emptyName + ".class"));
        }
    }

    @Test
    public void matchesLiteralsAndProductPatternsInsideSumPayloads() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var programName = "NestedPattern" + suffix;
        final var sumName = "MaybePattern" + suffix;
        final var productName = "PointPattern" + suffix;
        final var source = """
                public type %s<T> = Some(value: T) | Empty;
                public type %s = (x: Int, y: Int);
                fn classify(value: %s<Int>): Int = match (value) {
                    case Some(1) -> 1;
                    case Some(_) -> 2;
                    case Empty() -> 0;
                };
                fn classifyPoint(value: %s<%s>): Int = match (value) {
                    case Some(%s(0, _)) -> 10;
                    case Some(%s(_, _)) -> 20;
                    case Empty() -> 0;
                };
                fn one(): %s<Int> = %s.Some(1);
                fn other(): %s<Int> = %s.Some(7);
                fn origin(): %s<%s> = %s.Some(%s.new(0, 5));
                fn point(): %s<%s> = %s.Some(%s.new(3, 5));
                """.formatted(sumName, productName, sumName, sumName, productName, productName,
                productName, sumName, sumName, sumName, sumName, sumName, productName, sumName,
                productName, sumName, productName, sumName, productName);
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                final var maybe = loader.loadClass(sumName);
                assertEquals(1, program.getMethod("classify", maybe).invoke(null,
                        program.getMethod("one").invoke(null)));
                assertEquals(2, program.getMethod("classify", maybe).invoke(null,
                        program.getMethod("other").invoke(null)));
                assertEquals(10, program.getMethod("classifyPoint", maybe).invoke(null,
                        program.getMethod("origin").invoke(null)));
                assertEquals(20, program.getMethod("classifyPoint", maybe).invoke(null,
                        program.getMethod("point").invoke(null)));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", sumName + ".class"));
            Files.deleteIfExists(Path.of("dist", sumName + "$$sum$Some.class"));
            Files.deleteIfExists(Path.of("dist", sumName + "$$sum$Empty.class"));
            Files.deleteIfExists(Path.of("dist", productName + ".class"));
        }
    }

    @Test
    public void comparesGenericRecursiveSumsStructurally() throws Exception {
        final var programName = "SumEquality" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                import zeron.lang.Eq;
                type Tree<T> = Branch(value: T, next: Tree<T>?) | Empty;
                fn same<T: Eq<T>>(left: Tree<T>, right: Tree<T>): Boolean = left == right;
                fn empty(): Tree<Int> = Tree.Empty();
                fn branch(value: Int, next: Tree<Int>?): Tree<Int> = Tree.Branch(value, next);
                fn equalTrees(): Boolean = same(branch(1, empty()), branch(1, empty()));
                fn differentValues(): Boolean = same(branch(1, null), branch(2, null));
                fn differentCases(): Boolean = same(empty(), branch(1, null));
                fn equalNullTails(): Boolean = same(branch(1, null), branch(1, null));
                """;
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertTrue((Boolean) program.getMethod("equalTrees").invoke(null));
                assertFalse((Boolean) program.getMethod("differentValues").invoke(null));
                assertFalse((Boolean) program.getMethod("differentCases").invoke(null));
                assertTrue((Boolean) program.getMethod("equalNullTails").invoke(null));
            }

        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", "Tree.class"));
            Files.deleteIfExists(Path.of("dist", "Tree$$sum$Branch.class"));
            Files.deleteIfExists(Path.of("dist", "Tree$$sum$Empty.class"));
        }
    }

    @Test
    public void comparesDirectRecursiveSumPayloadsStructurally() throws Exception {
        final var programName = "DirectRecursiveSumEquality" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                import zeron.lang.Eq;
                type List<T> = Nil | Cons(head: T, tail: List<T>);
                fn same<T: Eq<T>>(left: List<T>, right: List<T>): Boolean = left == right;
                fn nil(): List<Int> = List.Nil();
                fn cons(head: Int, tail: List<Int>): List<Int> = List.Cons(head, tail);
                fn equalLists(): Boolean = same(cons(1, nil()), cons(1, nil()));
                fn differentTails(): Boolean = same(cons(1, nil()), cons(1, cons(2, nil())));
                """;
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertTrue((Boolean) program.getMethod("equalLists").invoke(null));
                assertFalse((Boolean) program.getMethod("differentTails").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", "List.class"));
            Files.deleteIfExists(Path.of("dist", "List$$sum$Cons.class"));
            Files.deleteIfExists(Path.of("dist", "List$$sum$Nil.class"));
        }
    }

    @Test
    public void retainsSumEqualityAcrossCompiledLibraryBoundaries() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "sumlib" + suffix;
        final var appPackage = "sumapp" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-sum-library-");
        final var appOutput = Files.createTempDirectory(Path.of("target"), "zeron-sum-client-");
        final var libraryUnit = parse("Library.zn", """
                package %s;
                public type Maybe<T> = Value(value: T) | Empty;
                """.formatted(libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(List.of(libraryUnit),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);

            final var appUnit = parse("Main.zn", """
                    package %s;
                    import %s.Maybe;
                    import zeron.lang.Eq;
                    fn same<T: Eq<T>>(left: Maybe<T>, right: Maybe<T>): Boolean = left == right;
                    fn value(value: Int): Maybe<Int> = Maybe.Value(value);
                    fn equal(): Boolean = same(value(7), value(7));
                    """.formatted(appPackage, libraryPackage));
            final var appCompiler = CompilationService.forCompilationUnits(List.of(appUnit),
                    appPackage + ".Main", appPackage, List.of(index), true, List.of(), appOutput);
            appCompiler.resolve();
            appCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    appOutput.toUri().toURL(), libraryOutput.toUri().toURL()}, getClass().getClassLoader())) {
                assertTrue((Boolean) loader.loadClass(appPackage + ".Main")
                        .getMethod("equal").invoke(null));
            }
        } finally {
            deleteTree(appOutput);
            deleteTree(libraryOutput);
        }
    }

    @Test
    public void requiresPayloadEvidenceAndRejectsSumEqualityWitnesses() {
        assertThrows(ResolutionError.class, () -> new CompilationService(parse("""
                type Box<T> = Boxed(value: T);
                fn invalid<T>(left: Box<T>, right: Box<T>): Boolean = left == right;
                """), "MissingSumEq").resolve());
        assertThrows(ResolutionError.class, () -> new CompilationService(parse("""
                import zeron.lang.Eq;
                type Marker = Red | Blue;
                witness Eq<Marker> for Marker {
                    fn eq(other: Marker): Boolean = true;
                }
                """), "OverriddenSumEq").resolve());
    }

    @Test
    public void matchesSumCasesByUnqualifiedName() throws Exception {
        final var programName = "ShortSumMatch" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                type Choice<T> = Some(value: T) | Empty;
                fn payload(choice: Choice<Int>): Int = match (choice) {
                    case Some(value) -> value;
                    case Empty() -> 0;
                };
                fn makeChoice(): Choice<Int> = Choice.Some(42);
                fn makeEmpty(): Choice<Int> = Choice<Int>.Empty();
                """;
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                final var choice = program.getMethod("makeChoice").invoke(null);
                final var empty = program.getMethod("makeEmpty").invoke(null);
                final var choiceType = loader.loadClass("Choice");
                assertEquals(42, program.getMethod("payload", choiceType).invoke(null, choice));
                assertEquals(0, program.getMethod("payload", choiceType).invoke(null, empty));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", "Choice.class"));
            Files.deleteIfExists(Path.of("dist", "Choice$$sum$Some.class"));
            Files.deleteIfExists(Path.of("dist", "Choice$$sum$Empty.class"));
        }
    }

    @Test
    public void resolvesNamespaceFunctionsAlongsideSumConstructors() throws Exception {
        final var programName = "SumNamespace" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                type Choice = Some(value: Int) | Empty;
                namespace Choice {
                    fn make(value: Int): Choice = Choice.Some(value);
                }
                fn result(): Int = match (Choice.make(42)) {
                    case Some(value) -> value;
                    case Empty() -> 0;
                };
                """;
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(programName).getMethod("result").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", "Choice.class"));
            Files.deleteIfExists(Path.of("dist", "Choice$$sum$Some.class"));
            Files.deleteIfExists(Path.of("dist", "Choice$$sum$Empty.class"));
            Files.deleteIfExists(Path.of("dist", "$zeron$Namespace$Choice.class"));
        }
    }

    @Test
    public void rejectsNamespaceFunctionsThatCollideWithSumCases() {
        final var error = assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type Choice = Some(value: Int) | Empty;
                namespace Choice {
                    fn Some(value: Int): Choice = Choice.Empty();
                }
                fn invalid(): Choice = Choice.Some(42);
                """)));

        assertEquals("Ambiguous name: both a sum case constructor and namespace function are declared.",
                error.getMessage());
    }

    @Test
    public void rejectsNonExhaustiveAndMalformedSumPatterns() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type Choice = First | Second;
                fn invalid(): Int = match (Choice.First()) {
                    case Choice.First() -> 1;
                };
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type Choice = Pair(left: Int, right: Int);
                fn invalid(): Int = match (Choice.Pair(1, 2)) {
                    case Choice.Pair(value) -> value;
                };
                """)));
    }

    @Test
    public void refinesSumCasesAndBindsPayloadsInSuccessfulMatches() throws Exception {
        final var programName = "SumRefinement" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                type Choice<T> = Some(value: T) | Empty;
                fn makeChoice(): Choice<Int> = Choice.Some(42);
                fn byType(choice: Choice<Int>): Int =
                    if (choice is Choice.Some) then choice.value else 0;
                fn byMatch(choice: Choice<Int>): Int =
                    if (choice matches Choice.Some(value)) then value else 0;
                fn byStatement(choice: Choice<Int>): Int {
                    if (choice matches Choice.Some(value)) { return value; }
                    return 0;
                }
                fn byWhile(choice: Choice<Int>): Int {
                    while (choice matches Choice.Some(value)) {
                        return value;
                    }
                    return 0;
                }
                """;
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                final var choice = program.getMethod("makeChoice").invoke(null);
                assertEquals(42, program.getMethod("byType",
                        loader.loadClass("Choice")).invoke(null, choice));
                assertEquals(42, program.getMethod("byMatch",
                        loader.loadClass("Choice")).invoke(null, choice));
                assertEquals(42, program.getMethod("byStatement",
                        loader.loadClass("Choice")).invoke(null, choice));
                assertEquals(42, program.getMethod("byWhile",
                        loader.loadClass("Choice")).invoke(null, choice));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", "Choice.class"));
            Files.deleteIfExists(Path.of("dist", "Choice$$sum$Some.class"));
            Files.deleteIfExists(Path.of("dist", "Choice$$sum$Empty.class"));
        }
    }

    @Test
    public void rejectsSumPayloadReadsWithoutCaseRefinement() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type Choice = Some(value: Int) | Empty;
                fn invalid(choice: Choice): Int = choice.value;
                """)));
    }

    @Test
    public void limitsMatchesBindingsToDirectSuccessfulConditions() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type Choice = Some(value: Int) | Empty;
                fn invalid(choice: Choice): Int =
                    if (choice matches Choice.Some(value) and true) then value else 0;
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type Choice = Some(value: Int) | Empty;
                fn invalid(choice: Choice): Int =
                    if (choice matches Choice.Some(value)) then 1 else value;
                """)));
    }

    @Test
    public void supportsExtensionsAndWitnessesTargetingGenericSums() throws Exception {
        final var programName = "SumEvidence" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                type Choice<T> = Just(value: T) | Nothing;
                extension<T> Choice<T> {
                    public fn hasValue(): Boolean = this is Choice.Just;
                    public fn valueOr(fallback: T): T =
                        if (this is Choice.Just) then this.value else fallback;
                }
                contract Label {
                    label(): String;
                }
                witness<T> Label for Choice<T> {
                    fn label(): String = match (this) {
                        case Choice.Just(_) -> "just";
                        case Choice.Nothing() -> "nothing";
                    };
                }
                fn throughBound<T: mut + Label>(value: T): String = value.label();
                fn run(): String =
                    if (Choice.Just(42).hasValue())
                    then throughBound(Choice.Just(42))
                    else "missing";
                fn fallback(): Int = Choice<Int>.Nothing().valueOr(7);
                """;
        final var unit = Parser.of(Scanner.from(source, programName + ".zn").scanTokens())
                .parseCompilationUnit(programName + ".zn");
        final var compiler = CompilationService.forCompilationUnits(List.of(unit), programName, "");
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals("just", program.getMethod("run").invoke(null));
                assertEquals(7, program.getMethod("fallback").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", programName + ".class"));
            Files.deleteIfExists(Path.of("dist", "Choice.class"));
            Files.deleteIfExists(Path.of("dist", "Choice$$sum$Just.class"));
            Files.deleteIfExists(Path.of("dist", "Choice$$sum$Nothing.class"));
            Files.deleteIfExists(Path.of("dist", "Label.class"));
            Files.deleteIfExists(Path.of("dist", "$zeron$Namespace$Choice.class"));
        }
    }

    @Test
    public void reportsUnknownStarImportedPatternTypeWithoutInventingItsPackage() {
        final var importedUnit = Parser.of(Scanner.from("""
                package imported;
                public class Available {
                    public constructor new;
                }
                """).scanTokens()).parseCompilationUnit(null);
        final var unit = Parser.of(Scanner.from("""
                package client;
                import imported.*;
                type Choice = Selected | Empty;
                fn invalid(): String = match (Choice.Selected()) {
                    case Missing.Empty() -> "missing";
                    case Choice.Selected() -> "selected";
                };
                """).scanTokens()).parseCompilationUnit(null);
        final var error = assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolveUnits(List.of(importedUnit, unit)));

        assertEquals("Unknown type 'Missing'.", error.getMessage());
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens()).parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
