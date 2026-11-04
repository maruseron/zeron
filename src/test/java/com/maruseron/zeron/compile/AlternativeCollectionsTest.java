package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AlternativeCollectionsTest {
    private static final String SOURCE = """
            package app;

            import zeron.collections.*;

            fn total(values: List<Int>): Int {
                let mut sum = 0;
                for (let value in values) sum = sum + value;
                return sum;
            }

            fn lookup(values: Map<ArrayList<Int>, Int>, key: ArrayList<Int>): Int =
                values.get(key).getOrElse(0);

            fn listBehavior(): Int {
                let mut list = LinkedList<Int>.of(1, 2, 3);
                list.addFirst(0);
                list.addLast(4);
                let mut score = 0;
                if (list.size == 5) score = score + 1;
                if (list.first().getOrElse(-1) == 0) score = score + 10;
                if (list.last().getOrElse(-1) == 4) score = score + 100;
                if (list.at(2).getOrElse(-1) == 2) score = score + 1000;
                let removedFirst = list.removeFirst();
                let removedLast = list.removeLast();
                if (removedFirst.getOrElse(-1) == 0) score = score + 10000;
                if (removedLast.getOrElse(-1) == 4) score = score + 100000;
                if (total(list) == 6) score = score + 1000000;
                let reversed = list.reversed();
                if (reversed.first().getOrElse(-1) == 3) score = score + 10000000;
                return score;
            }

            fn consBehavior(): Int {
                let list = ConsList<Int>.of(1, 2, 3);
                let mut score = 0;
                if (list.size == 3) score = score + 1;
                if (list.first().getOrElse(-1) == 1) score = score + 10;
                if (list.last().getOrElse(-1) == 3) score = score + 100;
                if (total(list) == 6) score = score + 1000;
                let reversed = list.reversed();
                if (reversed.first().getOrElse(-1) == 3) score = score + 10000;
                let prepended = list.prepend(0);
                if (prepended.size == 4 and prepended.first().getOrElse(-1) == 0) {
                    score = score + 100000;
                }
                if (list.size == 3) score = score + 1000000;
                return score;
            }

            fn dequeBehavior(): Int {
                let mut deque = ArrayDeque<Int>.empty();
                deque.addLast(2);
                deque.addLast(3);
                deque.addFirst(1);
                deque.addFirst(0);
                let mut score = 0;
                if (deque.size == 4) score = score + 1;
                if (deque.first().getOrElse(-1) == 0) score = score + 10;
                if (deque.last().getOrElse(-1) == 3) score = score + 100;
                if (deque.at(2).getOrElse(-1) == 2) score = score + 1000;
                let first = deque.removeFirst();
                let last = deque.removeLast();
                if (first.getOrElse(-1) == 0) score = score + 10000;
                if (last.getOrElse(-1) == 3) score = score + 100000;
                if (total(deque) == 3) score = score + 1000000;
                let reversed = deque.reversed();
                if (reversed.first().getOrElse(-1) == 2) score = score + 10000000;
                deque.clear();
                if (deque.isEmpty()) score = score + 100000000;
                return score;
            }

            fn identityBehavior(): Boolean {
                let mut map = IdentityHashMap<ArrayList<Int>, String>.empty();
                let first = ArrayList<Int>.of(1);
                let second = ArrayList<Int>.of(1);
                map.put(first, "first");
                map.put(second, "second");
                if (map.size != 2) return false;
                if (map.get(first).getOrElse("") != "first") return false;
                if (map.get(second).getOrElse("") != "second") return false;
                let third = ArrayList<Int>.of(1);
                if (map.containsKey(third)) return false;
                let removed = map.remove(first);
                if (removed.getOrElse("") != "first") return false;
                if (map.size != 1) return false;
                map.clear();
                return map.isEmpty();
            }

            fn contractBehavior(): Boolean {
                let list: List<Int> = LinkedList<Int>.of(1, 2, 3);
                let cons: List<Int> = ConsList<Int>.of(1, 2, 3);
                let deque: List<Int> = ArrayDeque<Int>.of(1, 2, 3);
                let mut map = IdentityHashMap<ArrayList<Int>, Int>.empty();
                let key = ArrayList<Int>.of(1);
                map.put(key, 7);
                return total(list) == 6
                    and total(cons) == 6
                    and total(deque) == 6
                    and lookup(map, key) == 7;
            }
            """;

    @Test
    public void alternativeImplementationsHonorTheirContracts() throws Exception {
        final var output = Files.createTempDirectory("zeron-alternative-collections-");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(parse(SOURCE)), "app.Main", "app", List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass("app.Main");
                assertEquals(11111111, generated.getMethod("listBehavior").invoke(null));
                assertEquals(1111111, generated.getMethod("consBehavior").invoke(null));
                assertEquals(111111111, generated.getMethod("dequeBehavior").invoke(null));
                assertTrue((Boolean) generated.getMethod("identityBehavior").invoke(null));
                assertTrue((Boolean) generated.getMethod("contractBehavior").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final String source) {
        final var result = Parser.of(Scanner.from(source, "Main.zn").scanTokens())
                .parseCompilationUnitWithDiagnostics("Main.zn");
        if (!result.diagnostics().isEmpty()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.compilationUnit();
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
