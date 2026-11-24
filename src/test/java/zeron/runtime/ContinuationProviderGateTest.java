package zeron.runtime;

import com.maruseron.zeron.TestPaths;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ContinuationProviderGateTest {
    @Test
    public void launcherArgumentsCarryTheLoomExportWhenThePackageExists() {
        if (!ContinuationProviderLoader.loomPackagePresent()) return;
        assertTrue(ContinuationProviderLoader.launcherArguments().contains(
                ContinuationProviderLoader.LOOM_EXPORT_ARGUMENT));
        assertTrue(ContinuationProviderLoader.launcherArguments().contains("--add-exports"));
    }

    @Test
    public void distributionLaunchersCarryTheLoomExport() throws IOException {
        for (final var name : List.of("zeron", "zeron.cmd")) {
            final var script = Files.readString(TestPaths.projectFile("distribution", "bin", name));
            assertTrue(name, script.contains("--add-exports"));
            assertTrue(name, script.contains(ContinuationProviderLoader.LOOM_EXPORT_ARGUMENT));
        }
    }

    @Test
    public void automaticSelectionFallsBackWithoutTheExport() throws Exception {
        final var result = runProbe(null);
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("b|true"));
    }

    @Test
    public void explicitLoomSelectionRunsWhenTheExportIsPresent() throws Exception {
        final var result = runProbe("a", true, "session");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("a|false"));
        assertTrue(result.output, result.output.contains("suspended=true"));
        assertTrue(result.output, result.output.contains("done=true"));
    }

    @Test
    public void loomPerformConstructsAContForTheHandler() throws Exception {
        final var result = runProbe("a", true, "level3");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("first=reply-one"));
        assertTrue(result.output, result.output.contains("second=reply-two"));
        assertTrue(result.output, result.output.contains("seen=[one, two]"));
    }

    @Test
    public void suspendedLoomContinuationExposesItsStack() throws Exception {
        final var result = runProbe("a", true, "stack");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("stack-depth="));
        assertTrue(result.output, result.output.contains("probe=true"));
    }

    @Test
    public void jfrRecordingRunsAcrossASuspension() throws Exception {
        final var result = runProbe("a", true, "jfr");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("jfr=true"));
    }

    @Test
    public void automaticSelectionWithoutExportFailsWithTheLauncherHint() throws Exception {
        final var result = runProbe("auto", false, "session");
        assertEquals(7, result.exitCode);
        assertTrue(result.output, result.output.contains("Backend B is selected"));
        assertTrue(result.output, result.output.contains("--add-exports"));
    }

    @Test
    public void explicitLoomSelectionBacksACont() throws Exception {
        final var result = runProbe("a", true, "cont");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("cont=payload"));
    }

    @Test
    public void explicitLoomSelectionDeliversTheResponse() throws Exception {
        final var result = runProbe("a", true, "response");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("response=second"));
        assertTrue(result.output, result.output.contains("done=true"));
    }

    @Test
    public void tailResumeTransferKeepsTheCaseStackBounded() throws Exception {
        final var result = runProbe("a", true, "tail");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("tail=done,pulls=100000"));
    }

    @Test
    public void escapedContFailsAfterItsHandlerExits() throws Exception {
        final var result = runProbe("a", true, "generation");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains(
                "generation=Cannot use a continuation after its handler exited."));
    }

    @Test
    public void suspensionAcrossAHostFrameIsRejectedAtRuntime() throws Exception {
        final var result = runProbe("a", true, "boundary");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("boundary=BoundaryException"));
    }

    @Test
    public void handlerInstalledInsideAHostFrameStillSuspends() throws Exception {
        final var result = runProbe("a", true, "boundary-inside");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("boundary-inside=inside"));
    }

    @Test
    public void cancellingATaskUnwindsASuspendedContinuation() throws Exception {
        final var result = runProbe("a", true, "cancel");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("cancel=observed,cleanup=true"));
    }

    @Test
    public void explicitLoomSelectionFailsWithTheLauncherHintWithoutTheExport() throws Exception {
        final var result = runProbe("a");
        assertEquals(7, result.exitCode);
        assertTrue(result.output, result.output.contains("Continuation backend A is unavailable"));
        assertTrue(result.output, result.output.contains("--add-exports"));
    }

    private static ProbeResult runProbe(final String backend)
            throws IOException, InterruptedException {
        return runProbe(backend, false, null);
    }

    private static ProbeResult runProbe(final String backend, final boolean export, final String mode)
            throws IOException, InterruptedException {
        final var java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        final var command = new ArrayList<String>();
        command.add(java.toString());
        if (export) {
            command.add("--add-exports");
            command.add(ContinuationProviderLoader.LOOM_EXPORT_ARGUMENT);
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Probe.class.getName());
        if (backend != null) command.add(backend);
        if (mode != null) command.add(mode);

        final var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        final var completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("Continuation provider probe timed out");
        }
        final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProbeResult(process.exitValue(), output);
    }

    private record ProbeResult(int exitCode, String output) {}

    public interface Block {
        Object invoke() throws Throwable;
    }

    public interface Receiver {
        Object invoke(Object value) throws Throwable;
    }

    /// Separate process so the test can vary the module export, which is fixed at JVM launch.
    public static final class Probe {
        public static void main(final String[] arguments) {
            final var backend = arguments.length == 0 ? ContinuationProviderLoader.AUTO : arguments[0];
            final var mode = arguments.length > 1 ? arguments[1] : null;
            try {
                final var selection = ContinuationProviderLoader.select(backend);
                System.out.println(selection.id() + "|" + selection.fallback());
                if ("session".equals(mode)) {
                    final var running = selection.provider().start(suspension -> {
                        suspension.suspend();
                    });
                    running.resume(null);
                    System.out.println("suspended=" + !running.isDone());
                    running.resume(null);
                    System.out.println("done=" + running.isDone());
                } else if ("response".equals(mode)) {
                    final var received = new Object[1];
                    final var running = selection.provider().start(suspension ->
                            received[0] = suspension.suspend());
                    running.resume("first");
                    System.out.println("suspended=" + !running.isDone());
                    running.resume("second");
                    System.out.println("done=" + running.isDone() + ",response=" + received[0]);
                } else if ("level3".equals(mode)) {
                    final var seen = new ArrayList<String>();
                    Level3Support.run(selection.provider(),
                            () -> {
                                final var first = EffectSupport.perform("one");
                                System.out.println("first=" + first);
                                final var second = EffectSupport.perform("two");
                                System.out.println("second=" + second);
                                return null;
                            },
                            (payload, continuation) -> {
                                seen.add((String) payload);
                                return continuation.resume("reply-" + payload);
                            });
                    System.out.println("seen=" + seen);
                } else if ("tail".equals(mode)) {
                    final var pulls = new int[1];
                    final var result = Level3Support.run(selection.provider(),
                            () -> {
                                for (var index = 0; index < 100_000; index++) {
                                    EffectSupport.perform("tick");
                                }
                                return "done";
                            },
                            (payload, continuation) -> {
                                pulls[0]++;
                                return Level3Support.transfer(continuation, "ok");
                            });
                    System.out.println("tail=" + result + ",pulls=" + pulls[0]);
                } else if ("generation".equals(mode)) {
                    @SuppressWarnings("unchecked")
                    final var escaped = (Cont<Object, Object>[]) new Cont<?, ?>[1];
                    try {
                        Level3Support.run(selection.provider(),
                                () -> EffectSupport.perform("x"),
                                (payload, continuation) -> {
                                    escaped[0] = continuation;
                                    throw new IllegalStateException("case escaped");
                                });
                    } catch (final Level3Support.HandlerEscape expected) {
                        try {
                            escaped[0].resume("late");
                            System.out.println("generation=missing");
                        } catch (final IllegalStateException error) {
                            System.out.println("generation=" + error.getMessage());
                        }
                    }
                } else if ("boundary".equals(mode)) {
                    try {
                        Level3Support.run(selection.provider(),
                                () -> {
                                    SuspensionGuard.enter(SuspensionGuard.HOST_CALLBACK);
                                    try {
                                        return EffectSupport.perform("cross");
                                    } finally {
                                        SuspensionGuard.exit();
                                    }
                                },
                                (payload, continuation) -> continuation.resume("reply"));
                        System.out.println("boundary=missing");
                    } catch (final SuspensionGuard.BoundaryException expected) {
                        System.out.println("boundary="
                                + expected.getClass().getSimpleName());
                    }
                } else if ("boundary-inside".equals(mode)) {
                    final var result = Level3Support.run(selection.provider(),
                            () -> {
                                SuspensionGuard.enter(SuspensionGuard.HOST_CALLBACK);
                                try {
                                    return Level3Support.run(selection.provider(),
                                            () -> EffectSupport.perform("inner"),
                                            (payload, continuation) ->
                                                    continuation.resume("inside"));
                                } finally {
                                    SuspensionGuard.exit();
                                }
                            },
                            (payload, continuation) -> continuation.resume("outer"));
                    System.out.println("boundary-inside=" + result);
                } else if ("cancel".equals(mode)) {
                    final var cleaned = new boolean[1];
                    final var suspended = new java.util.concurrent.CountDownLatch(1);
                    final var scope = com.maruseron.zeron.runtime.ScopeSupport.open();
                    try {
                        final var task = com.maruseron.zeron.runtime.ScopeSupport.start(scope,
                                (Block) () -> Level3Support.run(selection.provider(),
                                        () -> {
                                            try {
                                                EffectSupport.perform("wait");
                                                return null;
                                            } finally {
                                                cleaned[0] = true;
                                            }
                                        },
                                        (payload, continuation) -> {
                                            suspended.countDown();
                                            new java.util.concurrent.CountDownLatch(1).await();
                                            return null;
                                        }),
                                (Receiver) value -> null);
                        if (!suspended.await(10, TimeUnit.SECONDS)) {
                            System.out.println("cancel=not-suspended");
                            return;
                        }
                        task.cancel();
                        try {
                            com.maruseron.zeron.runtime.TaskSupport.await(task);
                            System.out.println("cancel=missing");
                        } catch (final java.util.concurrent.CancellationException expected) {
                            System.out.println("cancel=observed,cleanup=" + cleaned[0]);
                        }
                    } finally {
                        scope.close();
                    }
                } else if ("stack".equals(mode)) {
                    final var running = selection.provider().start(suspension -> suspension.suspend());
                    running.resume(null);
                    final var frames = running.stackTrace();
                    final var probe = java.util.Arrays.stream(frames)
                            .anyMatch(frame -> frame.getClassName().contains("ContinuationProviderGateTest"));
                    System.out.println("stack-depth=" + frames.length + ",probe=" + probe);
                    running.resume(null);
                } else if ("jfr".equals(mode)) {
                    try (final var recording = new jdk.jfr.Recording()) {
                        recording.enable("jdk.ExecutionSample")
                                .withPeriod(java.time.Duration.ofMillis(10));
                        recording.start();
                        final var running = selection.provider().start(suspension -> suspension.suspend());
                        running.resume(null);
                        running.resume(null);
                        recording.stop();
                    }
                    System.out.println("jfr=true");
                } else if ("cont".equals(mode)) {
                    final var received = new Object[1];
                    final var session = selection.provider().start(suspension ->
                            received[0] = suspension.suspend());
                    session.resume(null);
                    final var cont = new Cont<String, String>(new Cont.Delegate<>() {
                        @Override
                        public String resume(final String response) throws Throwable {
                            session.resume(response);
                            return (String) received[0];
                        }

                        @Override
                        public void cancel() throws Throwable {
                            session.cancel();
                        }

                        @Override
                        public boolean isDone() {
                            return session.isDone();
                        }

                        @Override
                        public StackTraceElement[] stackTrace() {
                            return session.stackTrace();
                        }
                    });
                    System.out.println("cont=" + cont.resume("payload"));
                }
            } catch (final Throwable failure) {
                System.out.println("error:" + failure.getMessage());
                System.exit(7);
            }
        }
    }
}
