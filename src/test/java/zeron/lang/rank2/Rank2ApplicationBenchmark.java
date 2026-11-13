package zeron.lang.rank2;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

/**
 * Standalone ZEP-0109 rank-2 application cost benchmark.
 *
 * <p>Run after {@code mvn -o -q test-compile}:
 * <pre>
 * java -cp "target/classes;target/test-classes" zeron.lang.rank2.Rank2ApplicationBenchmark
 * </pre>
 * This is a process-level microbenchmark, not a JMH replacement. It compares a bounded
 * polymorphic callback applied to each array element with a direct monomorphic call over the same
 * elements. The callback path exercises the generated hidden-evidence adapter ABI; the direct path
 * is the monomorphic baseline used by the ZEP-0106 model-field comparison.
 */
public final class Rank2ApplicationBenchmark {
    private static final int SIZE = 4096;
    private static final int ROUNDS = 512;
    private static final int WARMUP_ROUNDS = 12;
    private static final int MEASURE_ROUNDS = 31;
    private static volatile long sink;

    private Rank2ApplicationBenchmark() {}

    public static void main(final String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        final var packageName = "zep0109bench" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-rank2-bench-");
        try {
            final var unit = Parser.of(Scanner.from(source(packageName), "Rank2.zn").scanTokens())
                    .parseCompilationUnit("Rank2.zn");
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(java.util.List.of(unit)),
                    packageName + ".Main", packageName, java.util.List.of(), false,
                    java.util.List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()},
                    Rank2ApplicationBenchmark.class.getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                final var number = loader.loadClass(packageName + ".Number");
                final var constructor = number.getConstructor(int.class);
                final var values = Array.newInstance(number, SIZE);
                for (var index = 0; index < SIZE; index++) {
                    Array.set(values, index, constructor.newInstance(index));
                }

                final var polymorphic = method(main, "runPolymorphic");
                final var polymorphicUnit = method(main, "runPolymorphicUnit");
                final var monomorphic = method(main, "runMonomorphic");

                for (var round = 0; round < WARMUP_ROUNDS; round++) {
                    sink += (int) polymorphic.invoke(null, values, ROUNDS);
                    sink += (int) polymorphicUnit.invoke(null, values, ROUNDS);
                    sink += (int) monomorphic.invoke(null, values, ROUNDS);
                }

                final var callback = measure(polymorphic, values);
                final var callbackUnit = measure(polymorphicUnit, values);
                final var direct = measure(monomorphic, values);

                System.out.printf("java=%s vm=%s size=%d rounds=%d measureRounds=%d%n",
                        System.getProperty("java.version"), System.getProperty("java.vm.name"),
                        SIZE, ROUNDS, MEASURE_ROUNDS);
                System.out.printf("rank-2-callback      ns/field=%8.3f bytes/op=%8.3f%n",
                        median(callback.nanos), median(callback.bytes));
                System.out.printf("rank-2-callback-unit ns/field=%8.3f bytes/op=%8.3f%n",
                        median(callbackUnit.nanos), median(callbackUnit.bytes));
                System.out.printf("monomorphic           ns/field=%8.3f bytes/op=%8.3f%n",
                        median(direct.nanos), median(direct.bytes));
                System.out.printf("callback/monomorphic ratio=%.3f; unit ratio=%.3f%n",
                        median(callback.nanos) / median(direct.nanos),
                        median(callbackUnit.nanos) / median(direct.nanos));
                System.out.println("sink=" + sink);
            }
        } finally {
            deleteTree(output);
        }
    }

    private static java.lang.reflect.Method method(final Class<?> type, final String name) {
        return Arrays.stream(type.getMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Sample measure(final java.lang.reflect.Method method, final Object values)
            throws Exception {
        final var nanos = new double[MEASURE_ROUNDS];
        final var bytes = new double[MEASURE_ROUNDS];
        final var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final var threadId = Thread.currentThread().threadId();
        for (var round = 0; round < MEASURE_ROUNDS; round++) {
            final var beforeBytes = bean.getThreadAllocatedBytes(threadId);
            final var before = System.nanoTime();
            sink += (int) method.invoke(null, values, ROUNDS);
            final var elapsed = System.nanoTime() - before;
            final var allocated = bean.getThreadAllocatedBytes(threadId) - beforeBytes;
            final var operations = (double) SIZE * ROUNDS;
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

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static String source(final String packageName) {
        return """
                package %s;
                public contract Encoder {
                    encode(): Int;
                }
                public class Number is Encoder {
                    public property value: Int;
                    public constructor new;
                    public encode(): Int = this.value;
                }
                public fn forEachEncodable<T: Encoder>(values: Array<T>,
                        f: <U: Encoder> (U) -> Int): Int {
                    let mut total = 0;
                    let mut index = 0;
                    while (index < values.length) {
                        total = total + f(values[index]);
                        index = index + 1;
                    }
                    return total;
                }
                public fn runPolymorphic(values: Array<Number>, rounds: Int): Int {
                    let callback: <U: Encoder> (U) -> Int =
                            <U: Encoder> (item: U) -> item.encode();
                    let mut total = 0;
                    let mut round = 0;
                    while (round < rounds) {
                        total = total + forEachEncodable(values, callback);
                        round = round + 1;
                    }
                    return total;
                }
                public fn forEachEncodableUnit<T: Encoder>(values: Array<T>,
                        f: <U: Encoder> (U) -> Unit): Int {
                    let mut total = 0;
                    let mut index = 0;
                    while (index < values.length) {
                        f(values[index]);
                        total = total + 1;
                        index = index + 1;
                    }
                    return total;
                }
                public fn runPolymorphicUnit(values: Array<Number>, rounds: Int): Int {
                    let callback: <U: Encoder> (U) -> Unit =
                            <U: Encoder> (item: U) -> { item.encode(); };
                    let mut total = 0;
                    let mut round = 0;
                    while (round < rounds) {
                        total = total + forEachEncodableUnit(values, callback);
                        round = round + 1;
                    }
                    return total;
                }
                public fn runMonomorphic(values: Array<Number>, rounds: Int): Int {
                    let mut total = 0;
                    let mut round = 0;
                    while (round < rounds) {
                        let mut index = 0;
                        while (index < values.length) {
                            total = total + values[index].encode();
                            index = index + 1;
                        }
                        round = round + 1;
                    }
                    return total;
                }
                """.formatted(packageName);
    }

    private record Sample(double[] nanos, double[] bytes) {
    }
}
