package com.maruseron.zeron;

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

public final class CollectorApiTest {
    @Test
    public void concreteCollectorsBuildCollectionsAndSummaries() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "collectorClient" + suffix;
        final var className = packageName + ".CollectorTest" + suffix;
        final var source = parse("CollectorTest.zn", """
                package %s;
                import zeron.collections.ArrayList;
                import zeron.collections.Collector;
                import zeron.collections.ConsList;
                import zeron.collections.HashMap;
                import zeron.collections.HashSet;
                import zeron.collections.LinkedList;
                import zeron.collections.Stream;

                fn arrayListShape(): Int {
                    let values = Stream<Int>.fromArray([1, 2, 3])
                        .collectWith(Collector.toArrayList<Int>());
                    return values.count() * 100 + values.at(0).getOrElse(0) * 10
                        + values.at(2).getOrElse(0);
                }
                fn linkedListShape(): Int {
                    let values = Stream<Int>.fromArray([1, 2, 3])
                        .collectWith(Collector.toLinkedList<Int>());
                    return values.count() * 100 + values.at(0).getOrElse(0) * 10
                        + values.at(2).getOrElse(0);
                }
                fn consListShape(): Int {
                    let values = Stream<Int>.fromArray([1, 2, 3])
                        .collectWith(Collector.toConsList<Int>());
                    return values.count() * 100 + values.first().getOrElse(0) * 10
                        + values.last().getOrElse(0);
                }
                fn emptyCollectorOutput(): Int =
                    Stream<Int>.fromArray([]).collectWith(Collector.toArrayList<Int>()).count();
                fn hashMapRoundTrip(): Int {
                    let mut source = HashMap.empty<String, Int>();
                    source.put("a", 1);
                    source.put("b", 2);
                    let copied = source.stream().collectWith(Collector.toHashMap<String, Int>());
                    return copied.get("a").getOrElse(0) * 10 + copied.get("b").getOrElse(0)
                        + copied.count();
                }
                fn hashSetShape(): Int {
                    let values = Stream<Int>.fromArray([1, 2, 2, 3])
                        .collectWith(Collector.toHashSet<Int>());
                    if (values.contains(2) and not values.contains(9)) return values.count();
                    return -1;
                }
                fn sumInts(): Int =
                    Stream<Int>.fromArray([1, 2, 3, 4]).collectWith(Collector.summing<Int>());
                fn sumLongs(): Long =
                    Stream<Long>.fromArray([1L, 2L, 3L]).collectWith(Collector.summing<Long>());
                fn sumFloats(): Float =
                    Stream<Float>.fromArray([0.5, 1.25]).collectWith(Collector.summing<Float>());
                fn sumOfNothing(): Int =
                    Stream<Int>.fromArray([]).collectWith(Collector.summing<Int>());
                fn operationsInsteadOfCollector(): Int =
                    Stream<Int>.fromArray([1, 2, 3]).collect(0,
                        (total, value) -> total + value, (total) -> total * 2);
                fn reusedCollector(): String {
                    let collector = Collector.toConsList<Int>();
                    let first = Stream<Int>.fromArray([1, 2]).collectWith(collector);
                    let second = Stream<Int>.fromArray([3]).collectWith(collector);
                    return first.count().toString() + "-" + second.count().toString();
                }
                """.formatted(packageName));
        final var compiler = CompilationService.forCompilationUnits(List.of(source), className,
                packageName);

        try {
            deleteTree(Path.of("dist"));
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()},
                    getClass().getClassLoader())) {
                final var test = loader.loadClass(className);
                assertEquals(313, test.getMethod("arrayListShape").invoke(null));
                assertEquals(313, test.getMethod("linkedListShape").invoke(null));
                assertEquals(313, test.getMethod("consListShape").invoke(null));
                assertEquals(0, test.getMethod("emptyCollectorOutput").invoke(null));
                assertEquals(14, test.getMethod("hashMapRoundTrip").invoke(null));
                assertEquals(3, test.getMethod("hashSetShape").invoke(null));
                assertEquals(10, test.getMethod("sumInts").invoke(null));
                assertEquals(6L, test.getMethod("sumLongs").invoke(null));
                assertEquals(1.75, test.getMethod("sumFloats").invoke(null));
                assertEquals(0, test.getMethod("sumOfNothing").invoke(null));
                assertEquals(12, test.getMethod("operationsInsteadOfCollector").invoke(null));
                assertEquals("2-3", test.getMethod("reusedCollector").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(sourcePath);
    }
}
