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
    public static final int API_VERSION = 8;
    public static final List<String> BUNDLED_SOURCE_PATHS =
            List.of("/stdlib/zeron/collections/iteration.zn",
                    "/stdlib/zeron/collections/list.zn",
                    "/stdlib/zeron/collections/sequence.zn",
                    "/stdlib/zeron/ranges/intrange.zn",
                    "/stdlib/zeron/io/io.zn",
                    "/stdlib/zeron/lang/option.zn");

    private StandardLibrary() {}

    static CompilationUnit iterationUnit() {
        return loadUnit(BUNDLED_SOURCE_PATHS.getFirst());
    }

    static CompilationUnit intRangeUnit() {
        return loadUnit(BUNDLED_SOURCE_PATHS.get(3));
    }

    public static List<CompilationUnit> bundledUnits() {
        return BUNDLED_SOURCE_PATHS.stream().map(StandardLibrary::loadUnit).toList();
    }

    public static List<CompilationUnit> withBundledUnits(final List<CompilationUnit> sourceUnits) {
        final var combined = new ArrayList<>(sourceUnits);
        for (final var sourcePath : BUNDLED_SOURCE_PATHS) {
            if (!containsSource(combined, sourcePath)) combined.add(loadUnit(sourcePath));
        }
        return List.copyOf(combined);
    }

    public static boolean isBundledSourcePath(final String sourcePath) {
        if (sourcePath == null) return false;
        final var normalized = sourcePath.replace('\\', '/');
        return BUNDLED_SOURCE_PATHS.stream().anyMatch(resourcePath -> normalized.endsWith(resourcePath));
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
            return Parser.of(Scanner.from(source, resourcePath).scanWithDiagnostics())
                    .parseCompilationUnit(resourcePath);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
