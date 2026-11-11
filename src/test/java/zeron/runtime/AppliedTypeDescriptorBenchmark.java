package zeron.runtime;

import java.lang.management.ManagementFactory;

/**
 * Standalone prototype benchmark for ZEP-0015.
 *
 * <p>Run after {@code mvn -o -q test-compile}:
 * <pre>
 * java -cp target/classes;target/test-classes zeron.runtime.AppliedTypeDescriptorBenchmark
 * </pre>
 * This is a process-level microbenchmark, not a JMH replacement. It reports the constant
 * descriptor path (equality, hashing, forwarding) and the construction path separately so the
 * zero-allocation claim can be checked where the compiler is expected to emit constants.
 */
public final class AppliedTypeDescriptorBenchmark {
    private static final AppliedTypeDescriptor INT = AppliedTypeDescriptor.of("zeron.lang.Int");
    private static final AppliedTypeDescriptor STRING = AppliedTypeDescriptor.of("zeron.lang.String");
    private static final AppliedTypeDescriptor BOX_INT =
            AppliedTypeDescriptor.of("zeron.lang.Box", INT);
    private static final AppliedTypeDescriptor BOX_INT_COPY =
            AppliedTypeDescriptor.of("zeron.lang.Box", INT);
    private static final AppliedTypeDescriptor NESTED =
            AppliedTypeDescriptor.of("zeron.lang.Map", STRING, BOX_INT);
    private static final AppliedTypeDescriptor NESTED_COPY =
            AppliedTypeDescriptor.of("zeron.lang.Map", STRING, BOX_INT);
    private static final String NESTED_CANONICAL =
            "zeron.lang.Map<zeron.lang.String,zeron.lang.Box<zeron.lang.Int>>";
    private static final int WARMUP_ITERATIONS = 2_000_000;
    private static final int MEASURE_ITERATIONS = 10_000_000;

    private static volatile Object sink;
    private static volatile boolean booleanSink;

    private AppliedTypeDescriptorBenchmark() {
    }

    public static void main(final String[] args) {
        System.out.println("ZEP-0015 applied-type descriptor prototype benchmark");
        System.out.println("iterations: " + MEASURE_ITERATIONS);
        System.out.println();

        benchmark("constant descriptor equality (flat)", MEASURE_ITERATIONS,
                () -> observe(BOX_INT.equals(BOX_INT_COPY)));
        benchmark("constant descriptor equality (nested)", MEASURE_ITERATIONS,
                () -> observe(NESTED.equals(NESTED_COPY)));
        benchmark("constant descriptor cached hash", MEASURE_ITERATIONS,
                () -> observe(NESTED.hashCode() == NESTED.hashCode()));
        benchmark("forward reified descriptor (identity)", MEASURE_ITERATIONS,
                () -> observe(forward(NESTED) == NESTED));
        benchmark("canonical lookup (cached nested)", MEASURE_ITERATIONS,
                () -> observe(AppliedTypeDescriptors.lookup(NESTED_CANONICAL) == NESTED));
        benchmark("construct flat descriptor", 1_000_000,
                () -> observe(AppliedTypeDescriptor.of("zeron.lang.Box", INT)));
        benchmark("construct nested descriptor", 1_000_000,
                () -> observe(AppliedTypeDescriptor.of("zeron.lang.Map", STRING, BOX_INT)));

        allocation("constant equality", MEASURE_ITERATIONS,
                () -> observe(BOX_INT.equals(BOX_INT_COPY)));
        allocation("cached hash", MEASURE_ITERATIONS,
                () -> observe(NESTED.hashCode() == NESTED.hashCode()));
        allocation("forward reified descriptor", MEASURE_ITERATIONS,
                () -> observe(forward(NESTED) == NESTED));
        allocation("canonical lookup (cached nested)", MEASURE_ITERATIONS,
                () -> observe(AppliedTypeDescriptors.lookup(NESTED_CANONICAL) == NESTED));
        allocation("construct flat descriptor", 1_000_000,
                () -> observe(AppliedTypeDescriptor.of("zeron.lang.Box", INT)));
        allocation("construct nested descriptor", 1_000_000,
                () -> observe(AppliedTypeDescriptor.of("zeron.lang.Map", STRING, BOX_INT)));
    }

    private static AppliedTypeDescriptor forward(final AppliedTypeDescriptor descriptor) {
        return descriptor;
    }

    private static boolean observe(final boolean value) {
        booleanSink = value;
        return true;
    }

    private static boolean observe(final Object value) {
        sink = value;
        return true;
    }

    private static void benchmark(final String label, final int iterations,
                                  final java.util.function.BooleanSupplier operation) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            sink = operation.getAsBoolean();
        }
        final var start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            sink = operation.getAsBoolean();
        }
        final var elapsed = System.nanoTime() - start;
        System.out.printf("%-42s %8.2f ns/op%n", label, (double) elapsed / iterations);
    }

    private static void allocation(final String label, final int iterations,
                                   final java.util.function.BooleanSupplier operation) {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            System.out.printf("%-42s allocation measurement unavailable%n", label);
            return;
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            sink = operation.getAsBoolean();
        }
        final var before = bean.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            sink = operation.getAsBoolean();
        }
        final var bytes = bean.getCurrentThreadAllocatedBytes() - before;
        System.out.printf("%-42s %8.3f bytes/op%n", label, (double) bytes / iterations);
    }
}
