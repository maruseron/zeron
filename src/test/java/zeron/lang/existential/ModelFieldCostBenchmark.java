package zeron.lang.existential;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/**
 * Standalone ZEP-0106 model-field holder cost benchmark.
 *
 * <p>Run after {@code mvn -o -q test-compile}:
 * <pre>
 * java -cp "target/classes;target/test-classes" zeron.lang.existential.ModelFieldCostBenchmark
 * </pre>
 * This is a process-level microbenchmark, not a JMH replacement. It compares one evidence holder
 * per field with parallel payload/evidence arrays and a direct payload array so the stored-model
 * allocation and traversal costs can be checked separately.
 */
public final class ModelFieldCostBenchmark {
    private static final int SIZE = 4096;
    private static final int WARMUP_ROUNDS = 12;
    private static final int MEASURE_ROUNDS = 31;
    private static final int ACCESS_REPETITIONS = 96;
    private static final int BUILD_REPETITIONS = 64;
    private static final Object EVIDENCE = new Object();
    private static volatile long sink;

    private record Payload(int value) {
        int render() {
            return value;
        }
    }

    public static void main(final String[] args) {
        final var payloads = new Payload[SIZE];
        for (var index = 0; index < SIZE; index++) payloads[index] = new Payload(index);

        final var holder = new Exists1<?, ?>[SIZE];
        final var parallelPayloads = new Payload[SIZE];
        final var parallelEvidence = new Object[SIZE];
        final var directPayloads = new Payload[SIZE];

        Locale.setDefault(Locale.ROOT);
        System.out.printf("java=%s vm=%s size=%d accessReps=%d buildReps=%d measureRounds=%d%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                SIZE, ACCESS_REPETITIONS, BUILD_REPETITIONS, MEASURE_ROUNDS);

        for (var round = 0; round < WARMUP_ROUNDS; round++) {
            buildHolder(holder, payloads);
            buildParallel(parallelPayloads, parallelEvidence, payloads);
            buildDirect(directPayloads, payloads);
            accessHolder(holder);
            accessParallel(parallelPayloads, parallelEvidence);
            accessDirect(directPayloads);
        }

        final var holderBuild = buildMeasurements(holder, payloads, true);
        final var parallelBuild = buildMeasurements(parallelPayloads, parallelEvidence, payloads, false);
        final var directBuild = buildMeasurements(directPayloads, payloads, false);
        final var holderAccess = accessMeasurements(holder, true);
        final var parallelAccess = accessMeasurements(parallelPayloads, parallelEvidence, false);
        final var directAccess = accessMeasurements(directPayloads, false);

        System.out.printf("holder-build    ns/op=%8.2f bytes/op=%6.2f%n",
                median(holderBuild.nanos), median(holderBuild.bytes));
        System.out.printf("parallel-build  ns/op=%8.2f bytes/op=%6.2f%n",
                median(parallelBuild.nanos), median(parallelBuild.bytes));
        System.out.printf("direct-build    ns/op=%8.2f bytes/op=%6.2f%n",
                median(directBuild.nanos), median(directBuild.bytes));
        System.out.printf("holder-access   ns/op=%8.2f bytes/op=%6.2f%n",
                median(holderAccess.nanos), median(holderAccess.bytes));
        System.out.printf("parallel-access ns/op=%8.2f bytes/op=%6.2f%n",
                median(parallelAccess.nanos), median(parallelAccess.bytes));
        System.out.printf("direct-access   ns/op=%8.2f bytes/op=%6.2f%n",
                median(directAccess.nanos), median(directAccess.bytes));
        System.out.printf("holder/parallel access ratio=%.3f; holder/direct access ratio=%.3f%n",
                median(holderAccess.nanos) / median(parallelAccess.nanos),
                median(holderAccess.nanos) / median(directAccess.nanos));
        System.out.println("sink=" + sink);
    }

    private static void buildHolder(final Exists1<?, ?>[] target, final Payload[] payloads) {
        for (var index = 0; index < target.length; index++) {
            target[index] = Exists1.of(payloads[index], EVIDENCE);
        }
    }

    private static void buildParallel(final Payload[] target, final Object[] evidence,
                                      final Payload[] payloads) {
        for (var index = 0; index < target.length; index++) {
            target[index] = payloads[index];
            evidence[index] = EVIDENCE;
        }
    }

    private static void buildDirect(final Payload[] target, final Payload[] payloads) {
        System.arraycopy(payloads, 0, target, 0, target.length);
    }

    private static long accessHolder(final Exists1<?, ?>[] values) {
        var total = 0L;
        for (var repetition = 0; repetition < ACCESS_REPETITIONS; repetition++) {
            for (final var value : values) {
                final var payload = (Payload) value.payload();
                total += payload.render();
                if (value.slot0() != null) total++;
            }
        }
        return total;
    }

    private static long accessParallel(final Payload[] payloads, final Object[] evidence) {
        var total = 0L;
        for (var repetition = 0; repetition < ACCESS_REPETITIONS; repetition++) {
            for (var index = 0; index < payloads.length; index++) {
                total += payloads[index].render();
                if (evidence[index] != null) total++;
            }
        }
        return total;
    }

    private static long accessDirect(final Payload[] payloads) {
        var total = 0L;
        for (var repetition = 0; repetition < ACCESS_REPETITIONS; repetition++) {
            for (final var payload : payloads) total += payload.render();
        }
        return total;
    }

    private static Sample buildMeasurements(final Exists1<?, ?>[] holder, final Payload[] payloads,
                                            final boolean holderBuild) {
        final var nanos = new double[MEASURE_ROUNDS];
        final var bytes = new double[MEASURE_ROUNDS];
        final var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final var threadId = Thread.currentThread().threadId();
        for (var round = 0; round < MEASURE_ROUNDS; round++) {
            final var beforeBytes = bean.getThreadAllocatedBytes(threadId);
            final var before = System.nanoTime();
            for (var repetition = 0; repetition < BUILD_REPETITIONS; repetition++) {
                if (holderBuild) buildHolder(holder, payloads);
            }
            final var elapsed = System.nanoTime() - before;
            final var allocated = bean.getThreadAllocatedBytes(threadId) - beforeBytes;
            final var operations = (double) SIZE * BUILD_REPETITIONS;
            nanos[round] = elapsed / operations;
            bytes[round] = allocated / operations;
            sink += holder.length;
        }
        return new Sample(nanos, bytes);
    }

    private static Sample buildMeasurements(final Payload[] payloads, final Object[] evidence,
                                            final Payload[] source, final boolean ignored) {
        final var nanos = new double[MEASURE_ROUNDS];
        final var bytes = new double[MEASURE_ROUNDS];
        final var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final var threadId = Thread.currentThread().threadId();
        for (var round = 0; round < MEASURE_ROUNDS; round++) {
            final var beforeBytes = bean.getThreadAllocatedBytes(threadId);
            final var before = System.nanoTime();
            for (var repetition = 0; repetition < BUILD_REPETITIONS; repetition++) {
                buildParallel(payloads, evidence, source);
            }
            final var elapsed = System.nanoTime() - before;
            final var allocated = bean.getThreadAllocatedBytes(threadId) - beforeBytes;
            final var operations = (double) SIZE * BUILD_REPETITIONS;
            nanos[round] = elapsed / operations;
            bytes[round] = allocated / operations;
            sink += payloads.length;
        }
        return new Sample(nanos, bytes);
    }

    private static Sample buildMeasurements(final Payload[] target, final Payload[] source,
                                            final boolean directOnly) {
        final var nanos = new double[MEASURE_ROUNDS];
        final var bytes = new double[MEASURE_ROUNDS];
        final var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final var threadId = Thread.currentThread().threadId();
        for (var round = 0; round < MEASURE_ROUNDS; round++) {
            final var beforeBytes = bean.getThreadAllocatedBytes(threadId);
            final var before = System.nanoTime();
            for (var repetition = 0; repetition < BUILD_REPETITIONS; repetition++) {
                buildDirect(target, source);
            }
            final var elapsed = System.nanoTime() - before;
            final var allocated = bean.getThreadAllocatedBytes(threadId) - beforeBytes;
            final var operations = (double) SIZE * BUILD_REPETITIONS;
            nanos[round] = elapsed / operations;
            bytes[round] = allocated / operations;
            sink += target.length;
        }
        return new Sample(nanos, bytes);
    }

    private static Sample accessMeasurements(final Exists1<?, ?>[] values, final boolean holder) {
        final var nanos = new double[MEASURE_ROUNDS];
        final var bytes = new double[MEASURE_ROUNDS];
        final var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final var threadId = Thread.currentThread().threadId();
        for (var round = 0; round < MEASURE_ROUNDS; round++) {
            final var beforeBytes = bean.getThreadAllocatedBytes(threadId);
            final var before = System.nanoTime();
            sink += accessHolder(values);
            final var elapsed = System.nanoTime() - before;
            final var allocated = bean.getThreadAllocatedBytes(threadId) - beforeBytes;
            final var operations = (double) values.length * ACCESS_REPETITIONS;
            nanos[round] = elapsed / operations;
            bytes[round] = allocated / operations;
        }
        return new Sample(nanos, bytes);
    }

    private static Sample accessMeasurements(final Payload[] payloads, final Object[] evidence,
                                             final boolean parallel) {
        final var nanos = new double[MEASURE_ROUNDS];
        final var bytes = new double[MEASURE_ROUNDS];
        final var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final var threadId = Thread.currentThread().threadId();
        for (var round = 0; round < MEASURE_ROUNDS; round++) {
            final var beforeBytes = bean.getThreadAllocatedBytes(threadId);
            final var before = System.nanoTime();
            sink += accessParallel(payloads, evidence);
            final var elapsed = System.nanoTime() - before;
            final var allocated = bean.getThreadAllocatedBytes(threadId) - beforeBytes;
            final var operations = (double) payloads.length * ACCESS_REPETITIONS;
            nanos[round] = elapsed / operations;
            bytes[round] = allocated / operations;
        }
        return new Sample(nanos, bytes);
    }

    private static Sample accessMeasurements(final Payload[] payloads, final boolean direct) {
        final var nanos = new double[MEASURE_ROUNDS];
        final var bytes = new double[MEASURE_ROUNDS];
        final var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final var threadId = Thread.currentThread().threadId();
        for (var round = 0; round < MEASURE_ROUNDS; round++) {
            final var beforeBytes = bean.getThreadAllocatedBytes(threadId);
            final var before = System.nanoTime();
            sink += accessDirect(payloads);
            final var elapsed = System.nanoTime() - before;
            final var allocated = bean.getThreadAllocatedBytes(threadId) - beforeBytes;
            final var operations = (double) payloads.length * ACCESS_REPETITIONS;
            nanos[round] = elapsed / operations;
            bytes[round] = allocated / operations;
        }
        return new Sample(nanos, bytes);
    }

    private static double median(final double[] values) {
        final var sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private record Sample(double[] nanos, double[] bytes) {
    }
}
