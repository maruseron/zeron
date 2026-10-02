package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.scan.Scanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
public final class StandardLibrary {
    private static final String ITERATION_SOURCE = "/stdlib/iteration.zn";
    private static final String INT_RANGE_SOURCE = "/stdlib/intrange.zn";

    private StandardLibrary() {}

    static CompilationUnit iterationUnit() {
        return loadUnit(ITERATION_SOURCE);
    }

    static CompilationUnit intRangeUnit() {
        return loadUnit(INT_RANGE_SOURCE);
    }

    public static List<CompilationUnit> withBundledUnits(final List<CompilationUnit> sourceUnits) {
        final var combined = new ArrayList<>(sourceUnits);
        if (!containsSource(combined, ITERATION_SOURCE)) combined.add(iterationUnit());
        if (!containsSource(combined, INT_RANGE_SOURCE)) combined.add(intRangeUnit());
        return List.copyOf(combined);
    }

    private static boolean containsSource(final List<CompilationUnit> units, final String resourcePath) {
        final var normalizedResource = resourcePath.replace('\\', '/');
        return units.stream().map(CompilationUnit::sourcePath).filter(java.util.Objects::nonNull)
                .map(path -> path.replace('\\', '/'))
                .anyMatch(path -> path.endsWith(normalizedResource));
    }

    private static CompilationUnit loadUnit(final String resourcePath) {
        try (final var stream = StandardLibrary.class.getResourceAsStream(resourcePath)) {
            if (stream == null) throw new IllegalStateException("Missing standard library source " + resourcePath);
            final var source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(resourcePath);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
