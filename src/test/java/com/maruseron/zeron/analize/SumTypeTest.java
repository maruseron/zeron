package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

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
                fn throughBound<T: Label>(value: T): String = value.label();
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
}
