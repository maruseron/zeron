package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertTrue;

public final class FunctionLibraryAdapterTest {
    @Test
    public void compilesAndRunsEnabledCombinatorsAndPredicates() throws Exception {
        final var units = new ArrayList<>(StandardLibrary.bundledUnits());
        units.add(0, parse("""
                import zeron.function.*;

                fn verify(): Boolean {
                    let supplied = supply<Int>(7);
                    let constantFunction = constant<Int, Int>(8);
                    let composed = compose<Int, Int, Int>(value -> value + 1, value -> value * 2);
                    let piped = pipe<Int, Int, Int>(value -> value + 1, value -> value * 2);
                    let flipped = flip<Int, Int, Int>((left, right) -> left - right);
                    let curried = curry<Int, Int, Int>((left, right) -> left + right);
                    let inverted = negate<Int>(value -> value > 0);
                    let conjunction = both<Int>(value -> value > 0, value -> value < 10);
                    let disjunction = either<Int>(value -> value < 0, value -> value == 4);
                    let addTwo = curried(2);
                    return supplied() == 7
                        and constantFunction(0) == 8
                        and composed(4) == 9
                        and piped(4) == 10
                        and flipped(3, 8) == 5
                        and addTwo(3) == 5
                        and inverted(0)
                        and not inverted(2)
                        and conjunction(4)
                        and not conjunction(12)
                        and disjunction(4)
                        and not disjunction(3);
                }
                """, null));

        final var outputDirectory = Files.createTempDirectory("zeron-function-adapters-");
        final var className = "FunctionAdapterVerification"
                + UUID.randomUUID().toString().replace("-", "");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    units, className, "", List.of(), false, List.of(), outputDirectory);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{outputDirectory.toUri().toURL()}, getClass().getClassLoader())) {
                assertTrue((Boolean) loader.loadClass(className).getMethod("verify").invoke(null));
            }
        } finally {
            try (final var paths = Files.walk(outputDirectory)) {
                for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static CompilationUnit parse(final String source, final String sourcePath) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens()).parseCompilationUnit(sourcePath);
    }
}
