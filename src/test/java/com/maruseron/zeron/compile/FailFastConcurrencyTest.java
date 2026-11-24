package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class FailFastConcurrencyTest {
    @Test
    public void concurrentFanOutHandlesAwaitAndNeedsNoDisposeFailure() throws Exception {
        final var packageName = "failFastFanOut" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-fail-fast-fan-out-");
        final var unit = parse("""
                package %s;
                import zeron.concurrent.await;
                import zeron.concurrent.concurrent;
                public fn fanOut(): Int =
                    concurrent {
                        let first = start(() -> 20);
                        let second = start(() -> 22);
                        return await(first) + await(second);
                    };
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                assertEquals(42, loader.loadClass(packageName + ".Main")
                        .getMethod("fanOut").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void firstFailureCancelsSiblingsAndSiblingAwaitReportsPrimary() throws Exception {
        final var packageName = "failFastPrimary" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-fail-fast-primary-");
        final var unit = parse("""
                package %s;
                import java.lang.Thread;
                import zeron.concurrent.await;
                import zeron.concurrent.concurrent;
                public effect Boom {
                    public property code: Int;
                }
                fn failAfterSiblingStarts(state: &Array<Int>): Int performs Boom {
                    state[0] = 1;
                    while (state[1] == 0) {
                        // Wait until the sibling is running.
                    }
                    perform Boom.new(9);
                }
                fn sibling(state: &Array<Int>): Int {
                    state[1] = 1;
                    while (not unsafe Thread.currentThread()!!.isInterrupted()) {
                        // Wait for the primary failure to cancel this task.
                    }
                    return 2;
                }
                public fn siblingReportsPrimary(): Int {
                    let state: &Array<Int> = [0, 0];
                    return handle (concurrent {
                        let failed = start(() -> failAfterSiblingStarts(state));
                        let cancelled = start(() -> sibling(state));
                        await(failed);
                        return await(cancelled);
                    }) with {
                        case Boom as boom -> boom.code;
                    };
                }
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                assertEquals(9, loader.loadClass(packageName + ".Main")
                        .getMethod("siblingReportsPrimary").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void unobservedFailureIsReportedAtCloseAndManualModeDoesNotEscalate() throws Exception {
        final var packageName = "failFastClose" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-fail-fast-close-");
        final var unit = parse("""
                package %s;
                import zeron.concurrent.ConcurrentScope;
                import zeron.concurrent.concurrent;
                import zeron.resource.DisposeFailure;
                public effect Boom {
                    public property code: Int;
                }
                fn fire(): Int performs Boom = perform Boom.new(11);
                public fn unobserved(): Unit =
                    concurrent {
                        start(() -> fire());
                        join();
                    };
                public fn manual(): Int performs DisposeFailure {
                    using (let scope = ConcurrentScope.openManual()) {
                        let task = scope.start(() -> fire());
                        scope.join();
                        return 0;
                    }
                }
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                final var main = loader.loadClass(packageName + ".Main");
                final var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> main.getMethod("unobserved").invoke(null));
                assertEquals("zeron.runtime.RaisedEffect",
                        thrown.getCause().getClass().getName());
                assertEquals(0, main.getMethod("manual").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void injectedExecutorRunsConcurrentBodyDeterministically() throws Exception {
        final var packageName = "failFastExecutor" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-fail-fast-executor-");
        final var unit = parse("""
                package %s;
                import java.util.concurrent.ExecutorService;
                import zeron.concurrent.await;
                import zeron.concurrent.concurrentWith;
                public fn fanOut(executor: &ExecutorService): Int =
                    concurrentWith(executor) {
                        let first = start(() -> 20);
                        let second = start(() -> 22);
                        return await(first) + await(second);
                    };
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            final var executor = Executors.newSingleThreadExecutor();
            try (final var loader = loader(output)) {
                assertEquals(42, loader.loadClass(packageName + ".Main")
                        .getMethod("fanOut", java.util.concurrent.ExecutorService.class)
                        .invoke(null, executor));
            } finally {
                executor.shutdownNow();
            }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationService compiler(final CompilationUnit unit, final String packageName,
                                               final Path output) {
        return CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(unit)),
                packageName + ".Main", packageName, List.of(), true, List.of(), output);
    }

    private static URLClassLoader loader(final Path output) throws Exception {
        return new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                FailFastConcurrencyTest.class.getClassLoader());
    }

    private static CompilationUnit parse(final String source) {
        return Parser.of(Scanner.from(source, "Main.zn").scanTokens())
                .parseCompilationUnit("Main.zn");
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
