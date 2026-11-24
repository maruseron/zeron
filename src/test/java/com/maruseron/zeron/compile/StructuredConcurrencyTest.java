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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class StructuredConcurrencyTest {
    @Test
    public void fansOutJoinsAndStartsVirtualThreads() throws Exception {
        final var packageName = "concurrency" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-concurrency-");
        final var unit = parse("Main.zn", """
                package %s;
                import java.lang.Thread;
                import zeron.concurrent.ConcurrentScope;
                import zeron.resource.DisposeFailure;
                public fn fanOut(): Int performs DisposeFailure {
                    using (let scope = ConcurrentScope.open()) {
                        let first = scope.start(() -> 20);
                        let second = scope.start(() -> 22);
                        scope.join();
                        return first.value() + second.value();
                    }
                }
                public fn virtualThreads(): Boolean performs DisposeFailure {
                    using (let scope = ConcurrentScope.open()) {
                        let task = scope.start(() -> unsafe Thread.currentThread()!!.isVirtual());
                        return task.value();
                    }
                }
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(42, main.getMethod("fanOut").invoke(null));
                assertEquals(true, main.getMethod("virtualThreads").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void reRaisesCapturedEffectsAtTheJoinAndChecksTheCallersRow() throws Exception {
        final var packageName = "concurrencyEffects" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-concurrency-effects-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.concurrent.ConcurrentScope;
                import zeron.resource.DisposeFailure;
                public effect Boom {
                    public property code: Int;
                }
                fn fire(): Int performs Boom = perform Boom.new(7);
                fn captured(): Int performs Boom, DisposeFailure {
                    using (let scope = ConcurrentScope.open()) {
                        let task = scope.start(() -> fire());
                        scope.join();
                        return task.value();
                    }
                }
                public fn handled(): Int performs DisposeFailure =
                    handle (captured()) with {
                        case Boom as boom -> boom.code;
                    };
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                assertEquals(7, loader.loadClass(packageName + ".Main")
                        .getMethod("handled").invoke(null));
            }
        } finally {
            deleteTree(output);
        }

        final var invalid = parse("Invalid.zn", """
                package %s;
                import zeron.concurrent.ConcurrentScope;
                import zeron.resource.DisposeFailure;
                public effect Boom {
                    public property code: Int;
                }
                fn fire(): Int performs Boom = perform Boom.new(7);
                fn unhandled(): Int performs DisposeFailure {
                    using (let scope = ConcurrentScope.open()) {
                        let task = scope.start(() -> fire());
                        return task.value();
                    }
                }
                """.formatted(packageName));
        final var errors = compiler(invalid, packageName, output).resolveWithDiagnostics().errors();
        assertTrue(errors.toString(), errors.stream().anyMatch(error ->
                error.message().contains("Boom") && error.message().contains("performs")));
    }

    @Test
    public void cancellationInterruptsTheTaskAndRunsUsingCleanup() throws Exception {
        final var packageName = "concurrencyCancel" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-concurrency-cancel-");
        final var unit = parse("Main.zn", """
                package %s;
                import java.lang.Thread;
                import zeron.concurrent.ConcurrentScope;
                import zeron.resource.Disposable;
                import zeron.resource.DisposeFailure;
                public effect Boom {}
                class Cleanup is Disposable {
                    state: &Array<Int>;
                    public mut dispose(): Unit performs DisposeFailure {
                        this.state[0] = this.state[0] + 1;
                    }
                }
                fn cancellingTask(state: &Array<Int>): Int {
                    state[0] = 1;
                    while (not unsafe Thread.currentThread()!!.isInterrupted()) {
                        // Wait for scope cancellation.
                    }
                    state[1] = 1;
                    return 0;
                }
                public fn cancelsRunningTask(): Int performs DisposeFailure {
                    let state: &Array<Int> = [0, 0];
                    using (let scope = ConcurrentScope.open()) {
                        let task = scope.start(() -> cancellingTask(state));
                        while (state[0] == 0) {
                            // Wait until the virtual thread has started.
                        }
                        scope.cancel();
                        scope.join();
                        return state[1];
                    }
                }
                fn cleanupTask(state: &Array<Int>): Int performs Boom, DisposeFailure {
                    using (let cleanup: &Cleanup = Cleanup.new(state)) {
                        perform Boom.new();
                    }
                    return 0;
                }
                fn cleanupInTask(state: &Array<Int>): Int performs Boom, DisposeFailure {
                    using (let scope = ConcurrentScope.open()) {
                        let task = scope.start(() -> cleanupTask(state));
                        scope.join();
                        return task.value();
                    }
                }
                public fn cleanupAfterFailure(): Int performs DisposeFailure {
                    let state: &Array<Int> = [0];
                    return handle (cleanupInTask(state)) with {
                        case Boom -> state[0];
                        case DisposeFailure -> -1;
                    };
                }
                class FailingCleanup is Disposable {
                    public mut dispose(): Unit performs DisposeFailure {
                        perform DisposeFailure.new("cleanup");
                    }
                }
                fn failingCleanupTask(): Int performs Boom, DisposeFailure {
                    using (let cleanup: &FailingCleanup = FailingCleanup.new()) {
                        perform Boom.new();
                    }
                    return 0;
                }
                fn failingCleanupInTask(): Int performs Boom, DisposeFailure {
                    using (let scope = ConcurrentScope.open()) {
                        let task = scope.start(() -> failingCleanupTask());
                        scope.join();
                        return task.value();
                    }
                }
                public fn suppressedCleanupFailureCount(): Int performs DisposeFailure {
                    return handle (failingCleanupInTask()) with {
                        case Boom with suppressed as cleanupFailures -> cleanupFailures.length;
                    };
                }
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(1, main.getMethod("cancelsRunningTask").invoke(null));
                assertEquals(1, main.getMethod("cleanupAfterFailure").invoke(null));
                assertEquals(1, main.getMethod("suppressedCleanupFailureCount").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void deadlineCancelsTasksAndInjectedExecutorKeepsOrderingDeterministic() throws Exception {
        final var packageName = "concurrencyDeadline" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-concurrency-deadline-");
        final var unit = parse("Main.zn", """
                package %s;
                import java.lang.Thread;
                import java.util.concurrent.ExecutorService;
                import zeron.concurrent.ConcurrentScope;
                import zeron.resource.DisposeFailure;
                import zeron.time.Duration;
                public fn deadlineCancels(): Boolean performs DisposeFailure {
                    using (let scope = ConcurrentScope.open()) {
                        let task = scope.start(() -> {
                            while (not unsafe Thread.currentThread()!!.isInterrupted()) {
                                // Wait for the deadline.
                            }
                            return 0;
                        });
                        scope.deadline(Duration.fromNanoseconds(1000000L));
                        scope.join();
                        return task.isCancelled();
                    }
                }
                public fn singleThreaded(executor: &ExecutorService): Int performs DisposeFailure {
                    let state: &Array<Int> = [0, 0];
                    using (let scope = ConcurrentScope.openWith(executor)) {
                        let first = scope.start(() -> { state[0] = 1; return 1; });
                        let second = scope.start(() -> { state[1] = 2; return 2; });
                        scope.join();
                        return first.value() + second.value() + state[0] + state[1];
                    }
                }
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            final var executor = Executors.newSingleThreadExecutor();
            try (final var loader = loader(output)) {
                final var main = loader.loadClass(packageName + ".Main");
                assertTrue((Boolean) main.getMethod("deadlineCancels").invoke(null));
                assertEquals(6, main.getMethod("singleThreaded",
                        java.util.concurrent.ExecutorService.class).invoke(null, executor));
            } finally {
                executor.shutdownNow();
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void hostFailuresAndCancelledJoinsAreNotTranslatedIntoZeronEffects() throws Exception {
        final var packageName = "concurrencyHost" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-concurrency-host-");
        final var unit = parse("Main.zn", """
                package %s;
                import java.lang.Thread;
                import zeron.concurrent.ConcurrentScope;
                import zeron.resource.DisposeFailure;
                public fn hostFailure(): Int performs DisposeFailure {
                    using (let scope = ConcurrentScope.open()) {
                        let task = scope.start(() -> {
                            let values: &Array<Int> = [0];
                            return values[9];
                        });
                        return task.value();
                    }
                }
                public fn cancelledValue(): Int performs DisposeFailure {
                    using (let scope = ConcurrentScope.open()) {
                        let task = scope.start(() -> {
                            while (not unsafe Thread.currentThread()!!.isInterrupted()) {
                                // Wait for cancellation.
                            }
                            return 1;
                        });
                        scope.cancel();
                        return task.value();
                    }
                }
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                final var main = loader.loadClass(packageName + ".Main");
                final var hostFailure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> main.getMethod("hostFailure").invoke(null));
                assertEquals(IndexOutOfBoundsException.class, hostFailure.getCause().getClass());

                final var cancelled = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> main.getMethod("cancelledValue").invoke(null));
                assertEquals(java.util.concurrent.CancellationException.class,
                        cancelled.getCause().getClass());
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
                StructuredConcurrencyTest.class.getClassLoader());
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens())
                .parseCompilationUnit(sourceName);
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
