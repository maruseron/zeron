import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.LockSupport;

import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

/// Standalone ZEP-0119 backend prototype.
///
/// This is deliberately not a Zeron compiler feature. It isolates the runtime cost of
/// the three proposed level-3 strategies on the same pull-generator lifecycle:
///
/// A. Loom delimited continuations (`jdk.internal.vm.Continuation`).
/// B. A compiler-managed resume state machine, represented here by a hand-written
///    state machine. This is the best-case shape a CPS/state-machine transform can emit.
/// C. A virtual-thread body with a park/unpark rendezvous driven by the owning thread.
///
/// The harness measures creation, pull, cancellation, retained heap per suspended
/// generator, and a long tail-resuming run. It does not attempt to model a full effect
/// handler or the `Cont` API; those add a small wrapper around the same backend loop.
public final class Level3BackendPrototype {
    private static final int DONE = Integer.MIN_VALUE;
    private static final long BLACKHOLE_SEED = 0x9E3779B97F4A7C15L;

    private static volatile long blackhole;

    private Level3BackendPrototype() {}

    public static void main(final String[] arguments) throws Exception {
        final var options = Options.parse(arguments);
        if (options.help) {
            Options.printUsage();
            return;
        }

        System.out.println("ZEP-0119 level-3 backend prototype");
        System.out.println("JDK: " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")");
        System.out.println("elements/run: " + options.elements
                + ", measured runs: " + options.runs
                + ", suspended generators: " + options.suspended);
        System.out.println();

        final var backends = new Backend[]{
                new StateMachineBackend(),
                new LoomBackend(),
                new VirtualThreadBackend()
        };

        for (final var backend : backends) {
            backend.verify(options.elements);
        }

        System.out.printf(Locale.ROOT,
                "%-14s %12s %12s %12s %14s %12s%n",
                "backend", "create ns", "pull ns", "cancel ns", "heap B/susp", "virt thrs");
        System.out.println("-".repeat(82));

        for (final var backend : backends) {
            final var result = measure(backend, options);
            System.out.printf(Locale.ROOT,
                    "%-14s %12.1f %12.1f %12.1f %14.0f %12d%n",
                    backend.name(),
                    result.createNanos,
                    result.pullNanos,
                    result.cancelNanos,
                    result.heapBytesPerSuspended,
                    result.virtualThreads);
        }

        System.out.println();
        for (final var backend : backends) {
            final long depthNanos = measureDeepRun(backend, options.deepElements);
            System.out.printf(Locale.ROOT,
                    "%-14s deep run: %d pulls in %d ms, no stack overflow%n",
                    backend.name(), options.deepElements, depthNanos / 1_000_000);
        }
    }

    private static Measurement measure(final Backend backend, final Options options) {
        // Warm up each backend independently. The first Loom/C run pays class loading,
        // virtual-thread setup, and stack-chunk allocation; those costs are reported
        // separately through creation and retained-heap numbers.
        for (int run = 0; run < options.warmupRuns; run++) {
            pullAll(backend, Math.min(options.elements, 20_000));
        }

        long createNanos = 0;
        long pullNanos = 0;
        long checksum = 0;
        for (int run = 0; run < options.runs; run++) {
            final long createStart = System.nanoTime();
            final var generator = backend.create(options.elements);
            final long createEnd = System.nanoTime();
            createNanos += createEnd - createStart;

            final long pullStart = System.nanoTime();
            for (int index = 0; index < options.elements; index++) {
                final int value = generator.next();
                if (value == DONE) {
                    throw new AssertionError(backend.name() + " ended after " + index + " values");
                }
                checksum += value;
            }
            final long pullEnd = System.nanoTime();
            pullNanos += pullEnd - pullStart;

            if (generator.next() != DONE) {
                throw new AssertionError(backend.name() + " did not report DONE");
            }
            generator.cancel();
        }
        blackhole = blackhole * 31 + checksum;

        long cancelNanos = 0;
        for (int run = 0; run < options.cancelRuns; run++) {
            final var generator = backend.create(options.elements);
            if (generator.next() == DONE) {
                throw new AssertionError(backend.name() + " produced no first value");
            }
            final long start = System.nanoTime();
            generator.cancel();
            cancelNanos += System.nanoTime() - start;
        }

        final long suspendedBytes = suspendedHeap(backend, options.suspended);
        return new Measurement(
                (double) createNanos / options.runs,
                (double) pullNanos / Math.max(1L, (long) options.runs * options.elements),
                (double) cancelNanos / options.cancelRuns,
                (double) suspendedBytes / options.suspended,
                backend.virtualThreadsPerGenerator());
    }

    private static void pullAll(final Backend backend, final int elements) {
        final var generator = backend.create(elements);
        long checksum = 0;
        for (int index = 0; index < elements; index++) checksum += generator.next();
        generator.cancel();
        blackhole = blackhole * 31 + checksum;
    }

    private static long measureDeepRun(final Backend backend, final int elements) {
        final var generator = backend.create(elements);
        final long start = System.nanoTime();
        int count = 0;
        while (generator.next() != DONE) count++;
        final long elapsed = System.nanoTime() - start;
        if (count != elements) {
            throw new AssertionError(backend.name() + " deep run produced " + count
                    + " values, expected " + elements);
        }
        generator.cancel();
        return elapsed;
    }

    /// Approximate retained heap for generators suspended at their first yield.
    ///
    /// This intentionally includes the backend object, its captured body, and (for C) the
    /// virtual thread and its stack. A full GC is requested before and after, and the
    /// result is reported as a rough per-generator figure, not an exact allocation count.
    private static long suspendedHeap(final Backend backend, final int count) {
        forceGc();
        final long baseline = usedHeap();

        final var generators = new PullGenerator[count];
        for (int index = 0; index < count; index++) {
            generators[index] = backend.create(1_000_000);
            if (generators[index].next() == DONE) {
                throw new AssertionError(backend.name() + " failed to suspend");
            }
        }

        forceGc();
        final long after = usedHeap();
        // Keep the generator array reachable through the volatile sink while the
        // measurement runs, so the JIT cannot collect it early.
        blackhole = blackhole * 31 + generators.length + baseline + after;

        final long retained = Math.max(0, after - baseline);
        for (final var generator : generators) generator.cancel();
        Arrays.fill(generators, null);
        blackhole = blackhole * 31 + retained;
        forceGc();
        return retained;
    }

    private static void forceGc() {
        for (int attempt = 0; attempt < 3; attempt++) {
            ManagementFactory.getMemoryMXBean().gc();
            try {
                Thread.sleep(25);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static long usedHeap() {
        final var runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private record Measurement(double createNanos,
                               double pullNanos,
                               double cancelNanos,
                               double heapBytesPerSuspended,
                               int virtualThreads) {}

    private interface Backend {
        String name();

        PullGenerator create(int elements);

        default int virtualThreadsPerGenerator() {
            return 0;
        }

        default void verify(final int elements) {
            final var generator = create(elements);
            for (int index = 0; index < elements; index++) {
                final int value = generator.next();
                if (value != index) {
                    throw new AssertionError(name() + " yielded " + value
                            + " at " + index);
                }
            }
            if (generator.next() != DONE) {
                throw new AssertionError(name() + " did not finish");
            }
            generator.cancel();

            final var cancelled = create(elements);
            if (cancelled.next() != 0) {
                throw new AssertionError(name() + " did not produce its first value");
            }
            cancelled.cancel();
            if (cancelled.next() != DONE) {
                throw new AssertionError(name() + " remained live after cancellation");
            }
            if (cancelled.cleanups() != 1) {
                throw new AssertionError(name() + " ran " + cancelled.cleanups()
                        + " cleanup blocks, expected 1");
            }
        }
    }

    private interface PullGenerator {
        int next();

        void cancel();

        int cleanups();
    }

    /// Backend B: the resume state a compiler-managed transform would emit. The body is
    /// a loop, but the suspension point is represented by a switch state and a field.
    private static final class StateMachineBackend implements Backend {
        @Override
        public String name() {
            return "B state-machine";
        }

        @Override
        public PullGenerator create(final int elements) {
            return new StateMachineGenerator(elements);
        }
    }

    private static final class StateMachineGenerator implements PullGenerator {
        private final int elements;
        private int index;
        private int state;
        private int cleanups;
        private boolean done;

        StateMachineGenerator(final int elements) {
            this.elements = elements;
        }

        @Override
        public int next() {
            if (done) return DONE;
            switch (state) {
                case 0:
                    state = 1;
                    // fall through to the suspension point
                case 1:
                    if (index >= elements) {
                        done = true;
                        return DONE;
                    }
                    return index++;
                default:
                    done = true;
                    return DONE;
            }
        }

        @Override
        public void cancel() {
            if (done) return;
            done = true;
            cleanups++;
        }

        @Override
        public int cleanups() {
            return cleanups;
        }
    }

    /// Backend A: one-shot Loom continuation. The body runs on the owning thread; each
    /// `Continuation.yield` returns to `next()` and the next `run()` resumes it.
    private static final class LoomBackend implements Backend {
        @Override
        public String name() {
            return "A loom";
        }

        @Override
        public PullGenerator create(final int elements) {
            return new LoomGenerator(elements);
        }
    }

    private static final class LoomGenerator implements PullGenerator {
        private static final ContinuationScope SCOPE = new ContinuationScope("zep-0119");

        private final Thread owner = Thread.currentThread();
        private final Continuation continuation;
        private int value;
        private int cleanups;
        private boolean done;
        private boolean cancelled;

        LoomGenerator(final int elements) {
            continuation = new Continuation(SCOPE, () -> {
                try {
                    for (int index = 0; index < elements; index++) {
                        if (cancelled) throw new CancellationException();
                        value = index;
                        Continuation.yield(SCOPE);
                        if (cancelled) throw new CancellationException();
                    }
                } catch (final CancellationException expected) {
                    // The explicit cancellation path.
                } finally {
                    cleanups++;
                }
            });
        }

        @Override
        public int next() {
            requireOwner();
            if (done || cancelled || continuation.isDone()) {
                done = true;
                return DONE;
            }
            continuation.run();
            if (continuation.isDone()) {
                done = true;
                return DONE;
            }
            return value;
        }

        @Override
        public void cancel() {
            requireOwner();
            if (done || cancelled) return;
            cancelled = true;
            try {
                continuation.run();
            } catch (final CancellationException expected) {
                // The body unwound normally.
            } finally {
                done = true;
            }
        }

        @Override
        public int cleanups() {
            return cleanups;
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Continuation resumed off its owning thread");
            }
        }
    }

    /// Backend C: the body runs on a virtual thread and parks at every yield. The owning
    /// consumer thread wakes it and receives the next value through a two-way rendezvous.
    private static final class VirtualThreadBackend implements Backend {
        @Override
        public String name() {
            return "C virtual-thread";
        }

        @Override
        public PullGenerator create(final int elements) {
            return new VirtualThreadGenerator(elements);
        }

        @Override
        public int virtualThreadsPerGenerator() {
            return 1;
        }
    }

    private static final class VirtualThreadGenerator implements PullGenerator {
        private final Rendezvous rendezvous = new Rendezvous(Thread.currentThread());
        private final Thread body;
        private int cleanups;

        VirtualThreadGenerator(final int elements) {
            body = Thread.ofVirtual().name("zep-0119-body").unstarted(() -> {
                try {
                    for (int index = 0; index < elements; index++) {
                        if (rendezvous.cancelled()) throw new CancellationException();
                        rendezvous.publish(index);
                    }
                } catch (final CancellationException expected) {
                    // The owner cancelled the body.
                } finally {
                    cleanups++;
                    rendezvous.finish();
                }
            });
            rendezvous.bindBody(body);
            body.start();
        }

        @Override
        public int next() {
            rendezvous.requireOwner();
            return rendezvous.next();
        }

        @Override
        public void cancel() {
            rendezvous.requireOwner();
            rendezvous.cancel();
            try {
                body.join();
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public int cleanups() {
            return cleanups;
        }
    }

    /// Two-way handoff between the owner and the body. `state` is the only mutable
    /// coordination field; `value` is published before `VALUE` and read only after it.
    private static final class Rendezvous {
        private static final int RUNNING = 0;
        private static final int VALUE = 1;
        private static final int CONSUMED = 2;
        private static final int STATE_DONE = 3;
        private static final int STATE_CANCELLED = 4;

        private final Thread owner;
        private volatile Thread body;
        private volatile int value;
        private volatile int state = RUNNING;

        Rendezvous(final Thread owner) {
            this.owner = owner;
        }

        void bindBody(final Thread thread) {
            body = thread;
        }

        void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Generator pulled off its owning thread");
            }
        }

        boolean cancelled() {
            return state == STATE_CANCELLED || Thread.currentThread().isInterrupted();
        }

        int next() {
            for (;;) {
                final int current = state;
                if (current == VALUE) {
                    final int result = value;
                    state = CONSUMED;
                    LockSupport.unpark(bodyThread());
                    return result;
                }
                if (current == STATE_DONE || current == STATE_CANCELLED) return DONE;
                LockSupport.park();
            }
        }

        void publish(final int nextValue) {
            if (cancelled()) throw new CancellationException();
            value = nextValue;
            state = VALUE;
            LockSupport.unpark(owner);
            while (state == VALUE) {
                LockSupport.park();
                if (cancelled()) throw new CancellationException();
            }
        }

        void finish() {
            state = STATE_DONE;
            LockSupport.unpark(owner);
        }

        void cancel() {
            state = STATE_CANCELLED;
            final Thread body = bodyThread();
            if (body != null) {
                LockSupport.unpark(body);
                body.interrupt();
            }
            LockSupport.unpark(owner);
        }

        private Thread bodyThread() {
            return body;
        }
    }

    private static final class Options {
        int elements = 1_000_000;
        int runs = 5;
        int warmupRuns = 3;
        int cancelRuns = 10_000;
        int suspended = 5_000;
        int deepElements = 1_000_000;
        boolean help;

        static Options parse(final String[] arguments) {
            final var options = new Options();
            for (int index = 0; index < arguments.length; index++) {
                final var argument = arguments[index];
                switch (argument) {
                    case "--elements" -> options.elements = Integer.parseInt(arguments[++index]);
                    case "--runs" -> options.runs = Integer.parseInt(arguments[++index]);
                    case "--warmup" -> options.warmupRuns = Integer.parseInt(arguments[++index]);
                    case "--cancel-runs" -> options.cancelRuns = Integer.parseInt(arguments[++index]);
                    case "--suspended" -> options.suspended = Integer.parseInt(arguments[++index]);
                    case "--deep" -> options.deepElements = Integer.parseInt(arguments[++index]);
                    case "--help", "-h" -> options.help = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + argument);
                }
            }
            if (options.elements <= 0 || options.runs <= 0 || options.cancelRuns <= 0
                    || options.suspended <= 0 || options.deepElements <= 0) {
                throw new IllegalArgumentException("All counts must be positive.");
            }
            return options;
        }

        static void printUsage() {
            System.out.println("Usage: Level3BackendPrototype [options]");
            System.out.println("  --elements N      values per measured pull run (default 1000000)");
            System.out.println("  --runs N          measured pull runs (default 5)");
            System.out.println("  --warmup N        warmup pull runs (default 3)");
            System.out.println("  --cancel-runs N   cancellation samples (default 10000)");
            System.out.println("  --suspended N     generators retained for the heap sample (default 5000)");
            System.out.println("  --deep N          values in the long tail-resuming run (default 1000000)");
        }
    }
}
