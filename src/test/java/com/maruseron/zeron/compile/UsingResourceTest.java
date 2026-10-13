package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public final class UsingResourceTest {
    @Test
    public void usingDisposesInReverseOrderOnReturnAndPreservesSuppressedEffects() throws Exception {
        final var packageName = "resourceScopes" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-using-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.resource.Disposable;
                import zeron.resource.DisposeFailure;
                public effect BodyFailure {}
                public effect AcquireFailure {}
                class Recorder is Disposable {
                    state: &Array<Int>;
                    id: Int;
                    fail: Boolean;
                    public readId(): Int = this.id;
                    public mut dispose(): Unit raises DisposeFailure {
                        this.state[this.state[0] + 1] = this.id;
                        this.state[0] = this.state[0] + 1;
                        if (this.fail) {
                            raise DisposeFailure.new(this.id);
                        }
                    }
                }
                fn reverseOrder(): Int raises DisposeFailure {
                    let state: &Array<Int> = [0, 0, 0];
                    using (
                        let first: &Recorder = Recorder.new(state, 1, false),
                        let second: &Recorder = Recorder.new(state, 2, false)
                    ) {}
                    return state[0] * 100 + state[1] * 10 + state[2];
                }
                fn returnFromUsing(state: &Array<Int>): Int raises DisposeFailure {
                    using (let resource: &Recorder = Recorder.new(state, 7, false)) {
                        return 42;
                    }
                }
                fn breakFromUsing(): Int raises DisposeFailure {
                    let state: &Array<Int> = [0, 0];
                    while (true) {
                        using (let resource: &Recorder = Recorder.new(state, 9, false)) {
                            break;
                        }
                    }
                    return state[0] * 10 + state[1];
                }
                fn continueFromUsing(): Int raises DisposeFailure {
                    let state: &Array<Int> = [0, 0, 0];
                    while (state[0] < 2) {
                        using (let resource: &Recorder = Recorder.new(state, state[0] + 1, false)) {
                            continue;
                        }
                    }
                    return state[0] * 100 + state[1] * 10 + state[2];
                }
                fn outerUsingAroundBreak(): Int raises DisposeFailure {
                    let state: &Array<Int> = [0, 0, 0, 0];
                    using (let outer: &Recorder = Recorder.new(state, 1, false)) {
                        while (true) {
                            using (let inner: &Recorder = Recorder.new(state, 2, false)) {
                                break;
                            }
                        }
                        state[3] = state[0];
                    }
                    return state[3] * 1000 + state[1] * 100 + state[2] * 10 + state[0];
                }
                fn lambdaCapturesResource(): Int raises DisposeFailure {
                    let state: &Array<Int> = [0, 0];
                    using (let resource: &Recorder = Recorder.new(state, 4, false)) {
                        let read: () -> Int = () -> resource.readId();
                        return read();
                    }
                }
                fn uncheckedBody(state: &Array<Int>): Unit raises DisposeFailure {
                    using (let resource: &Recorder = Recorder.new(state, 5, true)) {
                        state[99];
                    }
                }
                fn failAcquire(): &Recorder raises AcquireFailure =
                    raise AcquireFailure.new();
                fn partialAcquire(): Int raises AcquireFailure, DisposeFailure {
                    let state: &Array<Int> = [0, 0];
                    using (
                        let acquired: &Recorder = Recorder.new(state, 3, true),
                        let missing: &Recorder = failAcquire()
                    ) {}
                    return state[0];
                }
                fn bodyFails(state: &Array<Int>): Int raises BodyFailure, DisposeFailure {
                    using (
                        let first: &Recorder = Recorder.new(state, 1, true),
                        let second: &Recorder = Recorder.new(state, 2, true)
                    ) {
                        raise BodyFailure.new();
                    }
                    return 0;
                }
                fn cleanupFails(state: &Array<Int>): Int raises DisposeFailure {
                    using (
                        let first: &Recorder = Recorder.new(state, 1, true),
                        let second: &Recorder = Recorder.new(state, 2, true)
                    ) {}
                    return 0;
                }
                fn cleanupFailureCount(state: &Array<Int>): Int =
                    handle (cleanupFails(state)) with {
                        case DisposeFailure with suppressed as failures -> failures.length;
                    };
                fn suppressedCount(state: &Array<Int>): Int =
                    handle (bodyFails(state)) with {
                        case BodyFailure with suppressed as failures -> failures.length;
                        case DisposeFailure with suppressed as failures -> failures.length;
                    };
                """.formatted(packageName));

        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(221, main.getMethod("reverseOrder").invoke(null));

                final var state = new Integer[]{0, 0};
                assertEquals(Integer.valueOf(42),
                        main.getMethod("returnFromUsing", Object[].class).invoke(null, (Object) state));
                assertEquals(Integer.valueOf(1), state[0]);
                assertEquals(Integer.valueOf(7), state[1]);
                assertEquals(Integer.valueOf(19), main.getMethod("breakFromUsing").invoke(null));
                assertEquals(Integer.valueOf(212), main.getMethod("continueFromUsing").invoke(null));
                assertEquals(Integer.valueOf(1212), main.getMethod("outerUsingAroundBreak").invoke(null));
                assertEquals(Integer.valueOf(4), main.getMethod("lambdaCapturesResource").invoke(null));

                try {
                    main.getMethod("uncheckedBody", Object[].class).invoke(null, (Object) new Integer[]{0, 0});
                    throw new AssertionError("The unchecked array access should propagate unchanged.");
                } catch (final InvocationTargetException exception) {
                    assertEquals(IndexOutOfBoundsException.class, exception.getCause().getClass());
                    assertEquals(1, exception.getCause().getSuppressed().length);
                }

                try {
                    main.getMethod("partialAcquire").invoke(null);
                    throw new AssertionError("The second resource initializer should raise an effect.");
                } catch (final InvocationTargetException exception) {
                    final var carrier = exception.getCause();
                    assertEquals("zeron.runtime.RaisedEffect", carrier.getClass().getName());
                    final var suppressed = (Object[]) carrier.getClass().getMethod("suppressedOutcomes")
                            .invoke(carrier);
                    assertEquals(1, suppressed.length);
                    assertEquals(Integer.valueOf(3), suppressed[0].getClass()
                            .getMethod("$zeron$get$cause").invoke(suppressed[0]));
                }

                final var failingState = new Integer[]{0, 0, 0};
                assertEquals(Integer.valueOf(2), main.getMethod("suppressedCount", Object[].class)
                        .invoke(null, (Object) failingState));
                assertEquals(Integer.valueOf(2), failingState[0]);
                assertEquals(Integer.valueOf(2), failingState[1]);
                assertEquals(Integer.valueOf(1), failingState[2]);

                final var cleanupState = new Integer[]{0, 0, 0};
                assertEquals(Integer.valueOf(1), main.getMethod("cleanupFailureCount", Object[].class)
                        .invoke(null, (Object) cleanupState));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
