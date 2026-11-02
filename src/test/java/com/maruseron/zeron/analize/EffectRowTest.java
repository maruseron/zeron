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
    public void infersEmptyRowsForPureCallbacksAndPropagatesEffectfulCallbacks() {
        final var result = resolve("""
                effect Failure {}
                fn apply<T, R, E: effect...>(value: T, transform: (T) -> R raises E): R raises E =
                    transform(value);
                fn pure(): Int = apply(1, value -> value + 1);
                fn effectful(): Int raises Failure = apply(1, value -> raise Failure.new());
                fn handled(): Int = handle (apply(1, value -> raise Failure.new())) with {
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
                fn combine<E: effect...>(
                    left: () -> (() -> Int raises E),
                    right: () -> Int raises E
                ): Int raises E = right();
                fn makeLeft(): () -> Int raises First = () -> raise First.new();
                fn run(): Int raises First, Second = combine(
                    () -> makeLeft(),
                    () -> raise Second.new()
                );
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void subtractsFixedLabelsWhenComputingLowerBounds() {
        final var result = resolve("""
                effect Fixed {}
                effect Extra {}
                fn both(): Int raises Fixed, Extra {
                    raise Fixed.new();
                    raise Extra.new();
                    return 1;
                }
                fn observe<E: effect...>(callback: () -> Int raises Fixed, E): Int raises Fixed, E =
                    callback();
                fn run(): Int raises Fixed, Extra = observe(() -> both());
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void acceptsExplicitRowsAndSingletonEffectArguments() {
        final var result = resolve("""
                effect Available {}
                fn run<E: effect...>(callback: () -> Int raises E): Int raises E = callback();
                fn empty(): Int = run<{}>(() -> 1);
                fn singleton(): Int raises Available = run<{Available}>(() -> raise Available.new());
                fn effectAsRow(): Int raises Available = run<Available>(() -> raise Available.new());
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void rejectsRowsWhereASingleEffectIsExpected() {
        for (final var source : List.of(
                """
                effect Available {}
                fn relay<E: effect>(value: String): String = value;
                fn invalid(): String = relay<{Available}>("row");
                """,
                """
                effect Available {}
                fn relay<E: effect>(value: String): String = value;
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
                fn relay<E: effect>(value: E): E raises E = value;
                fn run(): Available raises Available = relay(Available.new());
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void rejectsCallbacksRaisingOutsideTheExpectedRow() {
        final var result = resolve("""
                effect Permitted {}
                effect Forbidden {}
                fn take(callback: () -> Int raises Permitted): Int raises Permitted = callback();
                fn invalid(): Int raises Permitted = take(() -> raise Forbidden.new());
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
                fn assign<E: effect...>(
                    callback: () -> Int raises {}
                ): () -> Int raises E = callback;
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void recursiveRowPolymorphicFunctionsResolve() {
        final var result = resolve("""
                fn recurse<E: effect...>(
                    count: Int,
                    callback: () -> Int raises E
                ): Int raises E =
                    if (count == 0) then callback() else recurse(count - 1, callback);
                fn pure(): Int = recurse(2, () -> 1);
                """);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void nestedCallbackDepthCollectsAlternatingRowBounds() {
        final var valid = resolve("""
                effect First {}
                fn one<E: effect...>(consumer: (() -> Int raises E) -> Int): Int = 0;
                fn pureConsumer(callback: () -> Int): Int = 0;
                fn two<E: effect...>(consumer: ((() -> Int raises E) -> Int) -> Int): Int = 0;
                fn firstConsumer(callback: (() -> Int raises First) -> Int): Int = 0;
                fn three<E: effect...>(
                    consumer: (((() -> Int raises E) -> Int) -> Int) -> Int
                ): Int = 0;
                fn nestedFirstConsumer(
                    callback: ((() -> Int raises First) -> Int) -> Int
                ): Int = 0;
                fn runOne(): Int = one(pureConsumer);
                fn runTwo(): Int = two(firstConsumer);
                fn runThree(): Int = three(nestedFirstConsumer);
                """);
        assertTrue(valid.errors().toString(), valid.errors().isEmpty());

        final var invalidAtDepthOne = resolve("""
                effect Forbidden {}
                fn nestedCheck<E: effect...>(
                    producer: (() -> Int raises E),
                    consumer: (() -> Int raises E) -> Int
                ): Int raises E = producer();
                fn pureConsumer(callback: () -> Int): Int = 0;
                fn invalid(): Int raises Forbidden =
                    nestedCheck(() -> raise Forbidden.new(), pureConsumer);
                """);
        assertFalse(invalidAtDepthOne.errors().toString(), invalidAtDepthOne.errors().isEmpty());
        assertTrue(invalidAtDepthOne.errors().toString(),
                invalidAtDepthOne.errors().stream().anyMatch(error ->
                        error.message().contains("Forbidden")));

        final var invalidAtDepthTwo = resolve("""
                effect Forbidden {}
                fn nestedCheck<E: effect...>(
                    consumer: ((() -> Int raises E) -> Int) -> Int,
                    upper: (() -> Int raises E) -> Int
                ): Int = 0;
                fn firstConsumer(callback: (() -> Int raises Forbidden) -> Int): Int = 0;
                fn pureConsumer(callback: () -> Int): Int = 0;
                fn invalid(): Int = nestedCheck(firstConsumer, pureConsumer);
                """);
        assertFalse(invalidAtDepthTwo.errors().toString(), invalidAtDepthTwo.errors().isEmpty());
        assertTrue(invalidAtDepthTwo.errors().toString(),
                invalidAtDepthTwo.errors().stream().anyMatch(error ->
                        error.message().contains("Forbidden")));

        final var invalidAtDepthThree = resolve("""
                effect Forbidden {}
                fn nestedCheck<E: effect...>(
                    producer: (() -> Int raises E),
                    consumer: (((() -> Int raises E) -> Int) -> Int) -> Int
                ): Int raises E = producer();
                fn pureConsumer(callback: ((() -> Int) -> Int) -> Int): Int = 0;
                fn invalid(): Int raises Forbidden =
                    nestedCheck(() -> raise Forbidden.new(), pureConsumer);
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
                fn relay<E: effect...>(callback: () -> Int raises Fixed, E): Int raises E =
                    handle (callback()) with {
                        case Fixed -> 0;
                    };
                """);
        assertTrue(valid.errors().toString(), valid.errors().isEmpty());

        final var invalid = resolve("""
                effect Fixed {}
                effect Extra {}
                fn relay<E: effect...>(callback: () -> Int raises Fixed, E): Int raises Fixed =
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
                fn invalid<E: effect...>(callback: () -> Int raises E): Int =
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
                contract Source<E: effect...> {
                    read(callback: () -> Int raises E): Int raises E;
                }
                class Narrow is Source<{First, Second}> {
                    public constructor new;
                    public read(callback: () -> Int raises First, Second): Int raises First = 0;
                }
                """);
        assertTrue(valid.errors().toString(), valid.errors().isEmpty());

        final var invalid = resolve("""
                effect First {}
                effect Second {}
                contract Source<E: effect...> {
                    read(callback: () -> Int raises E): Int raises E;
                }
                class Widened is Source<{First}> {
                    public constructor new;
                    public read(callback: () -> Int raises First): Int raises First, Second =
                        callback();
                }
                """);
        assertFalse(invalid.errors().isEmpty());
    }

    @Test
    public void methodLevelRowsMustBeRepeatedByTheImplementation() {
        final var valid = resolve("""
                contract Runner {
                    run<E: effect...>(callback: () -> Int raises E): Int raises E;
                }
                class Impl is Runner {
                    public constructor new;
                    public run<E: effect...>(callback: () -> Int raises E): Int raises E = callback();
                }
                """);
        assertTrue(valid.errors().toString(), valid.errors().isEmpty());

        final var invalid = resolve("""
                contract Runner {
                    run<E: effect...>(callback: () -> Int raises E): Int raises E;
                }
                class Impl is Runner {
                    public constructor new;
                    public run<E: effect...>(callback: () -> Int raises E): Int = callback();
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
