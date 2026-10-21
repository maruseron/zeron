package com.maruseron.zeron.analize;

import com.maruseron.zeron.StandardLibrary;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TimeApiTest {
    @Test
    public void checksDurationArithmeticAndProvidesMonotonicInstants() throws Exception {
        final var className = "TimeApi" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-time-");
        final var unit = parse("TimeApi.zn", """
                package timefixture;
                import zeron.lang.*;
                import zeron.time.Duration;
                import zeron.time.Instant;

                fn additionIsRepresentable(): Boolean =
                    Duration.fromNanoseconds(9223372036854775807L)
                        .plus(Duration.fromNanoseconds(1L)).isSome();
                fn safeAddition(): Long =
                    Duration.fromNanoseconds(40L)
                        .plus(Duration.fromNanoseconds(2L))
                        .getOrElse(Duration.zero()).nanoseconds;
                fn subtractionIsRepresentable(): Boolean =
                    Duration.fromNanoseconds(-9223372036854775808L)
                        .minus(Duration.fromNanoseconds(1L)).isSome();
                fn negationIsRepresentable(): Boolean =
                    Duration.fromNanoseconds(-9223372036854775808L).negated().isSome();
                fn multiplicationIsRepresentable(): Boolean =
                    Duration.fromNanoseconds(9223372036854775807L).multipliedBy(2L).isSome();
                fn minimumTimesNegativeOneIsRepresentable(): Boolean =
                    Duration.fromNanoseconds(-9223372036854775808L).multipliedBy(-1L).isSome();
                fn secondsAreRepresentable(): Boolean = Duration.fromSeconds(9223372037L).isSome();
                fn negativeSecondsAreRepresentable(): Boolean = Duration.fromSeconds(-9223372037L).isSome();
                fn secondsBoundary(): Boolean = Duration.fromSeconds(9223372036L).isSome();
                fn negativeSecondsBoundary(): Boolean = Duration.fromSeconds(-9223372036L).isSome();
                fn durationOrdering(): Boolean {
                    let earlier: Duration = Duration.fromNanoseconds(-1L);
                    let zero: Duration = Duration.zero();
                    return earlier < zero;
                }
                fn durationEquality(): Boolean {
                    let left: Duration = Duration.fromNanoseconds(42L);
                    let right: Duration = Duration.fromNanoseconds(42L);
                    return left == right;
                }
                fn hash<T: mut + Hashable<T>>(value: T): Int = value.hash();
                fn durationHash(): Int = hash(Duration.fromNanoseconds(42L));
                fn durationHashIsStable(): Boolean =
                    durationHash() == hash(Duration.fromNanoseconds(42L));
                fn instantOrdering(): Boolean {
                    let start: Instant = Instant.now();
                    let end: Instant = Instant.now();
                    return end >= start;
                }
                fn elapsed(): Long {
                    let start = Instant.now();
                    let end = Instant.now();
                    return end.elapsedSince(start).nanoseconds;
                }
                fn advancedBy(): Long {
                    let start = Instant.now();
                    let end = start.advancedBy(Duration.fromNanoseconds(7L));
                    return end.elapsedSince(start).nanoseconds;
                }
                fn retreatedBy(): Long {
                    let start = Instant.now();
                    let earlier = start.retreatedBy(Duration.fromNanoseconds(7L));
                    return earlier.elapsedSince(start).nanoseconds;
                }
                """);

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)), className, "timefixture",
                    List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertFalse((Boolean) generated.getMethod("additionIsRepresentable").invoke(null));
                assertEquals(42L, generated.getMethod("safeAddition").invoke(null));
                assertFalse((Boolean) generated.getMethod("subtractionIsRepresentable").invoke(null));
                assertFalse((Boolean) generated.getMethod("negationIsRepresentable").invoke(null));
                assertFalse((Boolean) generated.getMethod("multiplicationIsRepresentable").invoke(null));
                assertFalse((Boolean) generated.getMethod("minimumTimesNegativeOneIsRepresentable")
                        .invoke(null));
                assertFalse((Boolean) generated.getMethod("secondsAreRepresentable").invoke(null));
                assertFalse((Boolean) generated.getMethod("negativeSecondsAreRepresentable").invoke(null));
                assertTrue((Boolean) generated.getMethod("secondsBoundary").invoke(null));
                assertTrue((Boolean) generated.getMethod("negativeSecondsBoundary").invoke(null));
                assertTrue((Boolean) generated.getMethod("durationOrdering").invoke(null));
                assertTrue((Boolean) generated.getMethod("durationEquality").invoke(null));
                assertTrue((Boolean) generated.getMethod("durationHashIsStable").invoke(null));
                assertTrue((Boolean) generated.getMethod("instantOrdering").invoke(null));
                assertTrue((Long) generated.getMethod("elapsed").invoke(null) >= 0L);
                assertEquals(7L, generated.getMethod("advancedBy").invoke(null));
                assertEquals(-7L, generated.getMethod("retreatedBy").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens())
                .parseCompilationUnit(sourceName);
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
