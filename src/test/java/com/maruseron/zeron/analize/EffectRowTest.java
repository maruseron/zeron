package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.EffectRows;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class EffectRowTest {
    @Test
    public void handlerResultMayWidenTheProtectedNullableResult() {
        final var result = resolve("""
                effect IoFailure {}
                fn readln(): String? performs IoFailure = perform IoFailure.new();
                handler SwallowToString { case IoFailure -> ""; }
                fn line(): String? = handle (readln()) with SwallowToString;
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void incompatibleHandlerResultsAreStillRejected() {
        final var result = resolve("""
                effect IoFailure {}
                fn readln(): String? performs IoFailure = perform IoFailure.new();
                handler SwallowToInt { case IoFailure -> 0; }
                fn line(): String? = handle (readln()) with SwallowToInt;
                """);

        assertFalse(result.errors().isEmpty());
    }

    @Test
    public void infersEmptyRowsForPureCallbacksAndPropagatesEffectfulCallbacks() {
        final var result = resolve("""
                effect Failure {}
                fn apply<T, R, E admits effect...>(value: T, transform: (T) -> R performs E): R performs E =
                    transform(value);
                fn pure(): Int = apply(1, value -> value + 1);
                fn effectful(): Int performs Failure = apply(1, value -> perform Failure.new());
                fn handled(): Int = handle (apply(1, value -> perform Failure.new())) with {
                    case Failure -> 41;
                };
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void unionsLowerBoundsFromEveryConstrainingArgument() {
        final var result = resolve("""
                effect First {}
                effect Second {}
                fn combine<E admits effect...>(
                    left: () -> (() -> Int performs E),
                    right: () -> Int performs E
                ): Int performs E = right();
                fn makeLeft(): () -> Int performs First = () -> perform First.new();
                fn run(): Int performs First, Second = combine(
                    () -> makeLeft(),
                    () -> perform Second.new()
                );
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void subtractsFixedLabelsWhenComputingLowerBounds() {
        final var result = resolve("""
                effect Fixed {}
                effect Extra {}
                fn both(): Int performs Fixed, Extra {
                    perform Fixed.new();
                    perform Extra.new();
                    return 1;
                }
                fn observe<E admits effect...>(callback: () -> Int performs Fixed, E): Int performs Fixed, E =
                    callback();
                fn run(): Int performs Fixed, Extra = observe(() -> both());
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void acceptsExplicitRowsAndSingletonEffectArguments() {
        final var result = resolve("""
                effect Available {}
                fn run<E admits effect...>(callback: () -> Int performs E): Int performs E = callback();
                fn empty(): Int = run<{}>(() -> 1);
                fn singleton(): Int performs Available = run<{Available}>(() -> perform Available.new());
                fn effectAsRow(): Int performs Available = run<Available>(() -> perform Available.new());
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void rejectsRowsWhereASingleEffectIsExpected() {
        for (final var source : List.of(
                """
                effect Available {}
                fn relay<E admits effect>(value: String): String = value;
                fn invalid(): String = relay<{Available}>("row");
                """,
                """
                effect Available {}
                fn relay<E admits effect>(value: String): String = value;
                fn invalid(): String = relay<{}>("empty");
                """)) {
            final var result = resolve(source);
            assertFalse(source, result.errors().isEmpty());
            assertTrue(result.errors().toString(),
                    result.errors().stream().anyMatch(error ->
                            error.message().contains("effect type argument")));
        }
    }

    @Test
    public void keepsEffectKindedParametersAsSingleEffects() {
        final var result = resolve("""
                effect Available {}
                fn relay<E admits effect>(value: E): E performs E = value;
                fn run(): Available performs Available = relay(Available.new());
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void rejectsCallbacksRaisingOutsideTheExpectedRow() {
        final var result = resolve("""
                effect Permitted {}
                effect Forbidden {}
                fn take(callback: () -> Int performs Permitted): Int performs Permitted = callback();
                fn invalid(): Int performs Permitted = take(() -> perform Forbidden.new());
                """);

        assertFalse(result.errors().isEmpty());
    }

    @Test
    public void expectedRowVariablesCoverFixedLabelsInInclusionChecks() {
        final var row = new TypeParameterDescriptor(1, "E", List.of(), false,
                TypeParameterDescriptor.Variance.INVARIANT, false, false, true, false);

        assertTrue(EffectRows.includedIn(List.of(TypeDescriptor.ofName("Failure")), List.of(row)));
        assertNull(EffectRows.firstUnpermitted(List.of(TypeDescriptor.ofName("Failure")), List.of(row)));
        assertFalse(EffectRows.includedIn(List.of(TypeDescriptor.ofName("Failure")), List.of()));
    }

    @Test
    public void pureFunctionFitsAnExpectedOpenRow() {
        final var result = resolve("""
                fn assign<E admits effect...>(
                    callback: () -> Int performs {}
                ): () -> Int performs E = callback;
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void recursiveRowPolymorphicFunctionsResolve() {
        final var result = resolve("""
                fn recurse<E admits effect...>(
                    count: Int,
                    callback: () -> Int performs E
                ): Int performs E =
                    if (count == 0) then callback() else recurse(count - 1, callback);
                fn pure(): Int = recurse(2, () -> 1);
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void nestedCallbackDepthCollectsAlternatingRowBounds() {
        final var valid = resolve("""
                effect First {}
                fn one<E admits effect...>(consumer: (() -> Int performs E) -> Int): Int = 0;
                fn pureConsumer(callback: () -> Int): Int = 0;
                fn two<E admits effect...>(consumer: ((() -> Int performs E) -> Int) -> Int): Int = 0;
                fn firstConsumer(callback: (() -> Int performs First) -> Int): Int = 0;
                fn three<E admits effect...>(
                    consumer: (((() -> Int performs E) -> Int) -> Int) -> Int
                ): Int = 0;
                fn nestedFirstConsumer(
                    callback: ((() -> Int performs First) -> Int) -> Int
                ): Int = 0;
                fn runOne(): Int = one(pureConsumer);
                fn runTwo(): Int = two(firstConsumer);
                fn runThree(): Int = three(nestedFirstConsumer);
                """);
        assertTrue(valid.errors().toString(), valid.errors().isEmpty());

        final var invalidAtDepthOne = resolve("""
                effect Forbidden {}
                fn nestedCheck<E admits effect...>(
                    producer: (() -> Int performs E),
                    consumer: (() -> Int performs E) -> Int
                ): Int performs E = producer();
                fn pureConsumer(callback: () -> Int): Int = 0;
                fn invalid(): Int performs Forbidden =
                    nestedCheck(() -> perform Forbidden.new(), pureConsumer);
                """);
        assertFalse(invalidAtDepthOne.errors().toString(), invalidAtDepthOne.errors().isEmpty());
        assertTrue(invalidAtDepthOne.errors().toString(),
                invalidAtDepthOne.errors().stream().anyMatch(error ->
                        error.message().contains("Forbidden")));

        final var invalidAtDepthTwo = resolve("""
                effect Forbidden {}
                fn nestedCheck<E admits effect...>(
                    consumer: ((() -> Int performs E) -> Int) -> Int,
                    upper: (() -> Int performs E) -> Int
                ): Int = 0;
                fn firstConsumer(callback: (() -> Int performs Forbidden) -> Int): Int = 0;
                fn pureConsumer(callback: () -> Int): Int = 0;
                fn invalid(): Int = nestedCheck(firstConsumer, pureConsumer);
                """);
        assertFalse(invalidAtDepthTwo.errors().toString(), invalidAtDepthTwo.errors().isEmpty());
        assertTrue(invalidAtDepthTwo.errors().toString(),
                invalidAtDepthTwo.errors().stream().anyMatch(error ->
                        error.message().contains("Forbidden")));

        final var invalidAtDepthThree = resolve("""
                effect Forbidden {}
                fn nestedCheck<E admits effect...>(
                    producer: (() -> Int performs E),
                    consumer: (((() -> Int performs E) -> Int) -> Int) -> Int
                ): Int performs E = producer();
                fn pureConsumer(callback: ((() -> Int) -> Int) -> Int): Int = 0;
                fn invalid(): Int performs Forbidden =
                    nestedCheck(() -> perform Forbidden.new(), pureConsumer);
                """);
        assertFalse(invalidAtDepthThree.errors().toString(), invalidAtDepthThree.errors().isEmpty());
        assertTrue(invalidAtDepthThree.errors().toString(),
                invalidAtDepthThree.errors().stream().anyMatch(error ->
                        error.message().contains("Forbidden")));
    }

    @Test
    public void handlerRemovesOnlyFixedLabelsAndLeavesTheRowVariable() {
        final var valid = resolve("""
                effect Fixed {}
                effect Extra {}
                fn relay<E admits effect...>(callback: () -> Int performs Fixed, E): Int performs E =
                    handle (callback()) with {
                        case Fixed -> 0;
                    };
                """);
        assertTrue(valid.errors().toString(), valid.errors().isEmpty());

        final var invalid = resolve("""
                effect Fixed {}
                effect Extra {}
                fn relay<E admits effect...>(callback: () -> Int performs Fixed, E): Int performs Fixed =
                    handle (callback()) with {
                        case Fixed -> 0;
                    };
                """);
        assertFalse(invalid.errors().isEmpty());
        assertTrue(invalid.errors().toString(),
                invalid.errors().stream().anyMatch(error ->
                        error.message().contains("Undeclared raised effects")));
    }

    @Test
    public void rejectsHandlerCasesThatNameARowVariable() {
        final var result = resolve("""
                fn invalid<E admits effect...>(callback: () -> Int performs E): Int =
                    handle (callback()) with {
                        case E -> 0;
                    };
                """);

        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().toString(),
                result.errors().stream().anyMatch(error ->
                        error.message().contains("concrete effect type")));
    }

    @Test
    public void interfaceRowsAllowSubsetsAndRejectWidening() {
        final var valid = resolve("""
                effect First {}
                effect Second {}
                contract Source<E admits effect...> {
                    read(callback: () -> Int performs E): Int performs E;
                }
                class Narrow is Source<{First, Second}> {
                    public constructor new;
                    public read(callback: () -> Int performs First, Second): Int performs First = 0;
                }
                """);
        assertTrue(valid.errors().toString(), valid.errors().isEmpty());

        final var invalid = resolve("""
                effect First {}
                effect Second {}
                contract Source<E admits effect...> {
                    read(callback: () -> Int performs E): Int performs E;
                }
                class Widened is Source<{First}> {
                    public constructor new;
                    public read(callback: () -> Int performs First): Int performs First, Second =
                        callback();
                }
                """);
        assertFalse(invalid.errors().isEmpty());
    }

    @Test
    public void methodLevelRowsMustBeRepeatedByTheImplementation() {
        final var valid = resolve("""
                contract Runner {
                    run<E admits effect...>(callback: () -> Int performs E): Int performs E;
                }
                class Impl is Runner {
                    public constructor new;
                    public run<E admits effect...>(callback: () -> Int performs E): Int performs E = callback();
                }
                """);
        assertTrue(valid.errors().toString(), valid.errors().isEmpty());

        final var invalid = resolve("""
                contract Runner {
                    run<E admits effect...>(callback: () -> Int performs E): Int performs E;
                }
                class Impl is Runner {
                    public constructor new;
                    public run<E admits effect...>(callback: () -> Int performs E): Int = callback();
                }
                """);
        assertFalse(invalid.errors().isEmpty());
    }

    private static ResolutionResult resolve(final String source) {
        final CompilationUnit unit = Parser.of(Scanner.from(source, "src/main.zn").scanTokens())
                .parseCompilationUnit("src/main.zn");
        return new ResolutionService().resolveUnitsWithDiagnostics(List.of(unit));
    }
}
