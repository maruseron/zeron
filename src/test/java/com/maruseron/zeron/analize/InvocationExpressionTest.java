package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class InvocationExpressionTest {
    @Test
    public void invokesCallsGroupingsConditionalsAndCurriedResults() throws Exception {
        final var className = "InvokeExpressions" + UUID.randomUUID().toString().replace("-", "");
        final var source = parse("""
                fn makeAdder(): Int -> Int -> Int = (left) -> (right) -> left + right;
                fn makeMaker(): () -> () -> Int = () -> () -> 42;
                fn threeLevels(): Int = makeMaker()()();
                fn curried(): Int = makeAdder()(20)(22);
                fn partial(): Int -> Int = makeAdder()(40);
                fn grouped(): Int = (makeAdder())(1)(41);
                fn conditional(flag: Boolean): Int =
                    (if (flag) then (value -> value + 1) else (value -> value - 1))(41);
                fn configure(callback: (Int) -> Int): (Int) -> Int = callback;
                fn trailing(): Int = configure { value -> value + 1 }(41);
                fn partialResult(): Int = partial()(41);
                fn readCallback(): Int {
                    let holder: CallbackHolder = CallbackHolder.new(() -> 42);
                    return holder.callback() + (holder.callback)();
                }
                fn indexed(): Int {
                    let callbacks: Array<() -> Int> = [() -> 42];
                    return callbacks[0]();
                }
                fn immediate(): Int = (() -> 42)();
                class CallbackHolder {
                    public property callback: () -> Int;
                    public constructor new;
                }
                """);
        final var compiler = CompilationService.forCompilationUnits(List.of(source), className, "");
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(42, generated.getMethod("threeLevels").invoke(null));
                assertEquals(42, generated.getMethod("curried").invoke(null));
                assertEquals(81, generated.getMethod("partialResult").invoke(null));
                assertEquals(42, generated.getMethod("grouped").invoke(null));
                assertEquals(42, generated.getMethod("conditional", boolean.class).invoke(null, true));
                assertEquals(40, generated.getMethod("conditional", boolean.class).invoke(null, false));
                assertEquals(42, generated.getMethod("trailing").invoke(null));
                assertEquals(84, generated.getMethod("readCallback").invoke(null));
                assertEquals(42, generated.getMethod("indexed").invoke(null));
                assertEquals(42, generated.getMethod("immediate").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", className + ".class"));
        }
    }

    @Test
    public void invokesGenericAndRankTwoFunctionValues() throws Exception {
        final var className = "InvokeGenericValues" + UUID.randomUUID().toString().replace("-", "");
        final var source = parse("""
                fn makeIdentity(): <T> (T) -> T = <T> (value: T) -> value;
                let selfIdentity: <T> (T) -> T = <T> (value: T) -> value;

                fn genericResult(): Int = makeIdentity()(42);
                fn rankTwoResult(): String = (selfIdentity)("value");
                """);
        final var compiler = CompilationService.forCompilationUnits(List.of(source), className, "");
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(42, generated.getMethod("genericResult").invoke(null));
                assertEquals("value", generated.getMethod("rankTwoResult").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", className + ".class"));
        }
    }

    @Test
    public void evaluatesCalleeOnceAndPreservesEffects() throws Exception {
        final var className = "InvokeEvaluation" + UUID.randomUUID().toString().replace("-", "");
        final var source = parse("""
                public effect Failure {}
                let mut factoryCalls = 0;

                fn make(): () -> Int {
                    factoryCalls += 1;
                    return () -> factoryCalls;
                }

                fn invoked(): Int = make()();
                fn count(): Int = factoryCalls;

                fn failing(): Int raises Failure = raise Failure.new();
                fn calleeEffect(): Int raises Failure = (if (true) then failing else () -> 0)();
                fn invocationEffect(): Int raises Failure = (failing)();
                """);
        final var compiler = CompilationService.forCompilationUnits(List.of(source), className, "");
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(1, generated.getMethod("invoked").invoke(null));
                assertEquals(1, generated.getMethod("count").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", className + ".class"));
        }
    }

    @Test
    public void rejectsNonCallableNullableAndReceiverFunctionExpressions() {
        for (final var source : List.of(
                "fn invalid(): Int = (42)();",
                "fn maybe(flag: Boolean): (() -> Int)? = if (flag) then (() -> 1) else null;"
                        + " fn invalid(flag: Boolean): Int = maybe(flag)();",
                "class Runner { public run(): Int = 1; }"
                        + " fn makeReceiver(): Runner.() -> Int = () -> 1;"
                        + " fn invalid(): Int = makeReceiver()();")) {
            final var error = assertThrows(ResolutionError.class,
                    () -> new ResolutionService().resolve(parse(source).declarations()));
            assertTrue(error.getMessage(), error.getMessage().contains("function value")
                    || error.getMessage().contains("null")
                    || error.getMessage().contains("receiver"));
        }
    }

    private static CompilationUnit parse(final String source) {
        return Parser.of(Scanner.from(source, "InvokeExpressions.zn").scanTokens())
                .parseCompilationUnit("InvokeExpressions.zn");
    }
}
