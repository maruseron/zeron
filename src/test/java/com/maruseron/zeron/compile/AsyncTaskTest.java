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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class AsyncTaskTest {
    private static final String AWAIT_HANDLER =
            "case Await as awaited, k -> k.resume(awaited.pending.awaitDone());";

    @Test
    public void spawnAndAwaitReturnValuesOnVirtualThreads() throws Exception {
        final var packageName = "asyncValues" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-async-values-");
        final var unit = parse("Main.zn", """
                package %s;
                import java.lang.Thread;
                import zeron.concurrent.Await;
                import zeron.concurrent.Scope;
                import zeron.concurrent.await;
                import zeron.concurrent.blocking;
                import zeron.concurrent.spawn;
                import zeron.resource.DisposeFailure;
                fn totalsBody(): Int performs Await, DisposeFailure {
                    using (let scope = Scope.open()) {
                        let first = spawn(scope, () -> 20);
                        let second = spawn(scope, () -> 22);
                        return await(first) + await(second);
                    }
                }
                public fn totals(): Int performs DisposeFailure =
                    handle (totalsBody()) with { %s };
                fn virtualThreadBody(): Boolean performs Await, DisposeFailure {
                    using (let scope = Scope.open()) {
                        let task = spawn(scope, () -> unsafe Thread.currentThread()!!.isVirtual());
                        return await(task);
                    }
                }
                public fn virtualThread(): Boolean performs DisposeFailure =
                    handle (virtualThreadBody()) with { %s };
                fn primitiveBody(): Int performs Await, DisposeFailure {
                    using (let scope = Scope.open()) {
                        return await(scope.start(() -> 42));
                    }
                }
                public fn directlyFromPrimitive(): Int performs DisposeFailure =
                    handle (primitiveBody()) with { %s };
                public fn viaBlocking(): Int performs DisposeFailure = blocking(primitiveBody);
                """.formatted(packageName, AWAIT_HANDLER, AWAIT_HANDLER, AWAIT_HANDLER));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(42, main.getMethod("totals").invoke(null));
                assertEquals(true, main.getMethod("virtualThread").invoke(null));
                assertEquals(42, main.getMethod("directlyFromPrimitive").invoke(null));
                assertEquals(42, main.getMethod("viaBlocking").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void entryInstallsTheBlockingAwaitHandler() throws Exception {
        final var packageName = "asyncEntry" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-async-entry-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.concurrent.Scope;
                import zeron.concurrent.await;
                import zeron.concurrent.spawn;
                import zeron.resource.DisposeFailure;
                public fn main(): Unit performs DisposeFailure {
                    using (let scope = Scope.open()) {
                        let task = spawn(scope, () -> 42);
                        let value = await(task);
                    }
                }
                """.formatted(packageName));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                final var main = loader.loadClass(packageName + ".Main");
                main.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void awaitReRaisesCapturedEffectsAndChecksTheCallersRow() throws Exception {
        final var packageName = "asyncEffects" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-async-effects-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.concurrent.Await;
                import zeron.concurrent.Scope;
                import zeron.concurrent.await;
                import zeron.concurrent.spawn;
                import zeron.resource.DisposeFailure;
                public effect Boom {
                    public property code: Int;
                }
                fn fire(): Int performs Boom = perform Boom.new(7);
                fn capturedBody(): Int performs Await, Boom, DisposeFailure {
                    using (let scope = Scope.open()) {
                        let task = spawn(scope, () -> fire());
                        return await(task);
                    }
                }
                fn captured(): Int performs Boom, DisposeFailure =
                    handle (capturedBody()) with { %s };
                public fn handled(): Int performs DisposeFailure =
                    handle (captured()) with {
                        case Boom as boom -> boom.code;
                    };
                """.formatted(packageName, AWAIT_HANDLER));

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
                import zeron.concurrent.Scope;
                import zeron.concurrent.await;
                import zeron.concurrent.spawn;
                import zeron.resource.DisposeFailure;
                public effect Boom {
                    public property code: Int;
                }
                fn fire(): Int performs Boom = perform Boom.new(7);
                fn unhandled(): Int performs DisposeFailure {
                    using (let scope = Scope.open()) {
                        return await(spawn(scope, () -> fire()));
                    }
                }
                """.formatted(packageName));
        final var errors = compiler(invalid, packageName, output).resolveWithDiagnostics().errors();
        assertTrue(errors.toString(), errors.stream().anyMatch(error ->
                error.message().contains("Boom") && error.message().contains("performs")));
    }

    @Test
    public void nonEntryAwaitRequiresTheAwaitRow() throws Exception {
        final var packageName = "asyncRow" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-async-row-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.concurrent.Scope;
                import zeron.concurrent.await;
                import zeron.concurrent.spawn;
                import zeron.resource.DisposeFailure;
                public fn missingRow(): Int performs DisposeFailure {
                    using (let scope = Scope.open()) {
                        return await(spawn(scope, () -> 1));
                    }
                }
                """.formatted(packageName));

        try {
            final var errors = compiler(unit, packageName, output).resolveWithDiagnostics().errors();
            assertTrue(errors.toString(), errors.stream().anyMatch(error ->
                    error.message().contains("zeron.concurrent.Await")));
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void cancellationSurfacesAtTheAwaitAndRunsUsingCleanup() throws Exception {
        final var packageName = "asyncCancel" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-async-cancel-");
        final var unit = parse("Main.zn", """
                package %s;
                import java.lang.Thread;
                import zeron.concurrent.Await;
                import zeron.concurrent.Scope;
                import zeron.concurrent.await;
                import zeron.concurrent.spawn;
                import zeron.resource.Disposable;
                import zeron.resource.DisposeFailure;
                class Cleanup is Disposable {
                    state: &Array<Int>;
                    public mut dispose(): Unit performs DisposeFailure {
                        this.state[0] = this.state[0] + 1;
                    }
                }
                fn cancelledBody(): Int performs Await, DisposeFailure {
                    using (let scope = Scope.open()) {
                        let state: &Array<Int> = [0];
                        let task = spawn(scope, () -> {
                            using (let cleanup = Cleanup.new(state)) {
                                while (not unsafe Thread.currentThread()!!.isInterrupted()) {
                                    // Wait for cancellation.
                                }
                            }
                            return 1;
                        });
                        task.cancel();
                        return await(task);
                    }
                }
                public fn cancelled(): Int performs DisposeFailure =
                    handle (cancelledBody()) with { %s };
                public fn cleanupRan(): Int performs DisposeFailure {
                    let state: &Array<Int> = [0, 0];
                    using (let scope = Scope.open()) {
                        spawn(scope, () -> {
                            using (let cleanup = Cleanup.new(state)) {
                                state[1] = 1;
                                while (not unsafe Thread.currentThread()!!.isInterrupted()) {
                                    // Wait for scope cancellation.
                                }
                            }
                            return 1;
                        });
                        while (state[1] == 0) {
                            // Wait for the task to start before cancelling it.
                        }
                        scope.cancel();
                    }
                    return state[0];
                }
                """.formatted(packageName, AWAIT_HANDLER));

        try {
            final var compiler = compiler(unit, packageName, output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = loader(output)) {
                final var main = loader.loadClass(packageName + ".Main");
                final var cancelled = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> main.getMethod("cancelled").invoke(null));
                assertEquals(java.util.concurrent.CancellationException.class,
                        cancelled.getCause().getClass());
                assertEquals(1, main.getMethod("cleanupRan").invoke(null));
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
                AsyncTaskTest.class.getClassLoader());
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
