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

public final class SortingApiTest {
    @Test
    public void sortsListsSortsStreamsAndSplitsWhitespace() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "sortingClient" + suffix;
        final var className = packageName + ".SortingTest" + suffix;
        final var source = parse("SortingTest.zn", """
                package %s;
                import zeron.collections.ArrayList;
                import zeron.collections.ConsList;
                import zeron.collections.LinkedList;
                import zeron.collections.Stream;

                fn arrayListSortedCopy(): String {
                    let values = ArrayList.of(3, 1, 2);
                    let sorted = values.sorted();
                    return sorted.at(0).getOrElse(0).toString() + ","
                        + sorted.at(1).getOrElse(0).toString() + ","
                        + sorted.at(2).getOrElse(0).toString() + "|"
                        + values.at(0).getOrElse(0).toString();
                }
                fn arrayListInPlaceSort(): String {
                    let mut values = ArrayList.of(1, 3, 2);
                    values.sortBy((a, b) -> b.compareTo(a));
                    return values.at(0).getOrElse(0).toString()
                        + values.at(1).getOrElse(0).toString()
                        + values.at(2).getOrElse(0).toString();
                }
                fn linkedListInPlaceSort(): String {
                    let mut values = LinkedList.of(3, 1, 2);
                    values.sort();
                    return values.at(0).getOrElse(0).toString()
                        + values.at(1).getOrElse(0).toString()
                        + values.at(2).getOrElse(0).toString();
                }
                fn consListSortedCopy(): String {
                    let values = ConsList.of(3, 1, 2);
                    let sorted = values.sorted();
                    return sorted.first().getOrElse(0).toString()
                        + values.first().getOrElse(0).toString()
                        + sorted.last().getOrElse(0).toString();
                }
                fn streamSorted(): String {
                    let values = Stream<String>.fromArray(["ccc", "a", "bb"])
                        .sortedBy((a, b) -> a.length().compareTo(b.length()))
                        .toList();
                    return values.at(0).getOrElse("") + values.at(1).getOrElse("")
                        + values.at(2).getOrElse("");
                }
                fn streamSortedCount(): Int =
                    Stream<Int>.fromArray([3, 1, 2])
                        .sortedBy((a, b) -> a.compareTo(b))
                        .count();
                fn streamSortedTwice(): String {
                    let values = Stream<Int>.fromArray([2, 1])
                        .sortedBy((a, b) -> a.compareTo(b));
                    let count = values.count();
                    return count.toString() + ":"
                        + values.toList().at(0).getOrElse(0).toString();
                }
                fn whitespaceParts(): String {
                    let parts = "  alpha\\tbeta\\n gamma  ".splitWhitespace().toList();
                    return parts.size.toString() + ":" + parts.at(0).getOrElse("")
                        + "," + parts.at(2).getOrElse("");
                }
                fn blankWhitespaceParts(): Int = " \\t\\n ".splitWhitespace().count();
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
                assertEquals("1,2,3|3", test.getMethod("arrayListSortedCopy").invoke(null));
                assertEquals("321", test.getMethod("arrayListInPlaceSort").invoke(null));
                assertEquals("123", test.getMethod("linkedListInPlaceSort").invoke(null));
                assertEquals("133", test.getMethod("consListSortedCopy").invoke(null));
                assertEquals("abbccc", test.getMethod("streamSorted").invoke(null));
                assertEquals(3, test.getMethod("streamSortedCount").invoke(null));
                assertEquals("2:1", test.getMethod("streamSortedTwice").invoke(null));
                assertEquals("3:alpha,gamma", test.getMethod("whitespaceParts").invoke(null));
                assertEquals(0, test.getMethod("blankWhitespaceParts").invoke(null));
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
