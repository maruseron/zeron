package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.ParseResult;
import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.scan.ScanResult;
import com.maruseron.zeron.scan.Scanner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class StandardLibrary {
    public record LoadResult(List<CompilationUnit> units, List<Diagnostic> diagnostics) {
        public LoadResult {
            units = List.copyOf(units);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public static final int API_VERSION = ZeronVersions.STANDARD_LIBRARY_API_VERSION;
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

    public static LoadResult withBundledUnitsWithDiagnostics(final List<CompilationUnit> sourceUnits) {
        final var combined = new ArrayList<>(sourceUnits);
        final var diagnostics = new ArrayList<Diagnostic>();
        for (final var sourcePath : BUNDLED_SOURCE_PATHS) {
            if (containsSource(combined, sourcePath)) continue;
            final var loaded = loadUnitWithDiagnostics(sourcePath);
            combined.add(loaded.compilationUnit());
            diagnostics.addAll(loaded.diagnostics());
        }
        return new LoadResult(combined, diagnostics);
    }

    public static boolean isBundledSourcePath(final String sourcePath) {
        if (sourcePath == null) return false;
        final var normalized = sourcePath.replace('\\', '/');
        return BUNDLED_SOURCE_PATHS.stream().anyMatch(resourcePath -> normalized.endsWith(resourcePath));
    }

    public static java.util.Optional<Path> bundledSourceFile(final String resourcePath) {
        final var normalized = resourcePath == null ? null : resourcePath.replace('\\', '/');
        if (normalized == null || !BUNDLED_SOURCE_PATHS.contains(normalized)) {
            throw new IllegalArgumentException("Not a bundled standard-library source path: " + resourcePath);
        }
        final var relativePath = Path.of(normalized.substring("/stdlib/".length()));
        final var installationHome = System.getProperty("zeron.home");
        if (installationHome != null && !installationHome.isBlank()) {
            final var source = Path.of(installationHome).resolve("share")
                    .resolve("zeron").resolve("stdlib").resolve(relativePath).toAbsolutePath().normalize();
            if (Files.isRegularFile(source)) return java.util.Optional.of(source);
        }
        final var resource = StandardLibrary.class.getResource(normalized);
        if (resource != null && resource.getProtocol().equals("file")) {
            try {
                return java.util.Optional.of(Path.of(resource.toURI()).toAbsolutePath().normalize());
            } catch (final URISyntaxException exception) {
                throw new IllegalStateException("Invalid standard-library source URL: " + resource, exception);
            }
        }
        final var developmentSource = Path.of(System.getProperty("user.dir"),
                "src", "main", "resources", "stdlib").resolve(relativePath).toAbsolutePath().normalize();
        if (Files.isRegularFile(developmentSource)) return java.util.Optional.of(developmentSource);
        if (installationHome != null && !installationHome.isBlank()) {
            throw new IllegalStateException("Bundled standard-library source is missing from distribution and "
                    + "development resources: " + relativePath);
        }
        return java.util.Optional.empty();
    }

    private static boolean containsSource(final List<CompilationUnit> units, final String resourcePath) {
        final var normalizedResource = resourcePath.replace('\\', '/');
        return units.stream().map(CompilationUnit::sourcePath).filter(java.util.Objects::nonNull)
                .map(path -> path.replace('\\', '/'))
                .anyMatch(path -> path.endsWith(normalizedResource));
    }

    private static CompilationUnit loadUnit(final String resourcePath) {
        final var loaded = loadUnitWithDiagnostics(resourcePath);
        Zeron.reportParseDiagnostics(loaded.diagnostics());
        return loaded.compilationUnit();
    }

    private static UnitLoadResult loadUnitWithDiagnostics(final String resourcePath) {
        try (final var stream = StandardLibrary.class.getResourceAsStream(resourcePath)) {
            if (stream == null) throw new IllegalStateException("Missing standard library source " + resourcePath);
            final var source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            final ScanResult scan = Scanner.from(source, resourcePath).scanWithDiagnostics();
            final ParseResult parsed = Parser.of(scan).parseCompilationUnitWithDiagnostics(resourcePath);
            final var diagnostics = new ArrayList<>(scan.diagnostics());
            diagnostics.addAll(parsed.diagnostics());
            return new UnitLoadResult(parsed.compilationUnit(), diagnostics);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record UnitLoadResult(CompilationUnit compilationUnit, List<Diagnostic> diagnostics) {
        private UnitLoadResult {
            diagnostics = List.copyOf(diagnostics);
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
