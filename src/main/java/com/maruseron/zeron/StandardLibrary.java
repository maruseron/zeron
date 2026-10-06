package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.scan.Scanner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class StandardLibrary {
    public static final int API_VERSION = 10;
    private static final String SOURCE_INDEX = "/stdlib/sources.index";
    public static final List<String> BUNDLED_SOURCE_PATHS = readSourceIndex();

    private StandardLibrary() {}

    static CompilationUnit intRangeUnit() {
        return BUNDLED_SOURCE_PATHS.stream()
                .filter(path -> path.endsWith("/zeron/ranges/intrange.zn"))
                .findFirst()
                .map(StandardLibrary::loadUnit)
                .orElseThrow(() -> new IllegalStateException(
                        "Standard library source index does not include zeron/ranges/intrange.zn"));
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

    private static List<String> readSourceIndex() {
        try (final var stream = StandardLibrary.class.getResourceAsStream(SOURCE_INDEX)) {
            if (stream == null) throw new IllegalStateException("Missing standard library source index "
                    + SOURCE_INDEX);
            try (final var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                final var paths = reader.lines().filter(line -> !line.isBlank()).toList();
                if (paths.isEmpty() || paths.stream().anyMatch(path ->
                        !path.startsWith("/stdlib/") || !path.endsWith(".zn"))) {
                    throw new IllegalStateException("Invalid standard library source index " + SOURCE_INDEX);
                }
                if (paths.stream().distinct().count() != paths.size()) {
                    throw new IllegalStateException("Duplicate source path in standard library index "
                            + SOURCE_INDEX);
                }
                return paths.stream().sorted().toList();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
