package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.assertTrue;

public final class HashCollectionsTest {
    @Test
    public void mapsAndSetsUseHashableKeysAndSupportIteration() throws Exception {
        final var className = "HashCollections" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var source = """
                import zeron.collections.Map;
                import zeron.collections.Set;
                import zeron.lang.Hashable;
                import zeron.lang.Pair;

                fn emptyMapFor<T: Hashable<T>>(): &Map<T, Int> = Map.empty<T, Int>();
                fn emptySetFor<T: Hashable<T>>(): &Set<T> = Set.empty<T>();

                fn mapBehavior(): Boolean {
                    let mut values = Map.empty<Int, String>();
                    let firstInsert = values.put(1, "one");
                    if (firstInsert.isSome()) return false;
                    values.put(9, "nine");
                    values.put(2, "two");
                    values.put(3, "three");
                    values.put(4, "four");
                    values.put(5, "five");
                    values.put(6, "six");
                    if (values.size != 7) return false;
                    let replaced = values.put(1, "updated");
                    if (replaced.getOrElse("missing") != "one") return false;
                    let fetched = values.lookup(1);
                    if (fetched.getOrElse("missing") != "updated") return false;
                    let removed = values.remove(9);
                    if (removed.getOrElse("missing") != "nine") return false;
                    if (values.containsKey(9)) return false;
                    let missing = values.lookup(100);
                    if (missing.isSome()) return false;
                    let mut keySum = 0;
                    let mut pairCount = 0;
                    for (let entry in values) {
                        keySum = keySum + entry.fst;
                        pairCount = pairCount + 1;
                    }
                    if (values.size != 6 or keySum != 21 or pairCount != 6) return false;

                    let mut initialized = Map.of(
                            Pair<Int, String>.new(12, "twelve"),
                            Pair<Int, String>.new(13, "thirteen"));
                    if (initialized.size != 2 or initialized.lookup(13).getOrElse("") != "thirteen") {
                        return false;
                    }
                    initialized.clear();
                    if (not initialized.isEmpty()) return false;

                    let mut nullable = Map.empty<Int?, String?>();
                    nullable.put(null, null);
                    let nullableValue = nullable.lookup(null);
                    return nullable.containsKey(null) and nullableValue.isSome();
                }

                fn hashableBoundsEntailEquality(): Boolean {
                    let values = emptyMapFor<Int>();
                    let elements = emptySetFor<Int>();
                    return values.isEmpty() and elements.isEmpty();
                }

                fn setBehavior(): Boolean {
                    let mut values = Set.empty<Int>();
                    if (not values.add(1)) return false;
                    if (not values.add(9)) return false;
                    if (values.add(1)) return false;
                    values.add(2);
                    values.add(3);
                    values.add(4);
                    values.add(5);
                    values.add(6);
                    if (values.size != 7 or not values.contains(9)) return false;
                    if (not values.remove(9) or values.contains(9)) return false;
                    let mut count = 0;
                    for (let value in values) count = count + 1;
                    if (count != 6 or values.size != 6) return false;

                    let mut initialized = Set.of(4, 4, 5);
                    if (initialized.size != 2) return false;
                    initialized.clear();
                    return initialized.isEmpty();
                }

                fn collectedSetBehavior(): Boolean {
                    let collected = for [1, 1, 2] collect Set.empty<Int>();
                    return collected.size == 2 and collected.contains(1) and collected.contains(2);
                }
                """;

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    java.util.List.of(parse(source)), className, "");
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertTrue((Boolean) generated.getMethod("mapBehavior").invoke(null));
                assertTrue((Boolean) generated.getMethod("hashableBoundsEntailEquality").invoke(null));
                assertTrue((Boolean) generated.getMethod("setBehavior").invoke(null));
                assertTrue((Boolean) generated.getMethod("collectedSetBehavior").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static CompilationUnit parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(null);
    }
}
