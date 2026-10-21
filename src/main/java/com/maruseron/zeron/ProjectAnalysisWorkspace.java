package com.maruseron.zeron;

import com.maruseron.zeron.api.PublicMemberCatalog;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.analize.ResolutionResult;
import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.Severity;
import com.maruseron.zeron.diagnostic.SourcePosition;
import com.maruseron.zeron.diagnostic.SourceSpan;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.Token;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

public final class ProjectAnalysisWorkspace {
    public record Overlay(int version, String text) {
        public Overlay {
            if (version < 0) throw new IllegalArgumentException("Document version must not be negative.");
            Objects.requireNonNull(text);
        }
    }

    public static final class Snapshot {
        private final Map<Path, List<Diagnostic>> diagnostics;
        private final Map<Path, List<Token>> tokens;
        private final Map<Path, String> sourceText;
        private final Map<String, Path> filesByCompilerPath;
        private final List<CompilationUnit> units;
        private final List<Diagnostic> unassociatedDiagnostics;
        private final Optional<ResolutionResult> resolution;
        private final Optional<PublicMemberCatalog> memberCatalog;

        private Snapshot(final Map<Path, List<Diagnostic>> diagnostics,
                         final Map<Path, List<Token>> tokens,
                         final Map<Path, String> sourceText,
                         final Map<String, Path> filesByCompilerPath,
                         final List<CompilationUnit> units,
                         final List<Diagnostic> unassociatedDiagnostics,
                         final Optional<ResolutionResult> resolution,
                         final Optional<PublicMemberCatalog> memberCatalog) {
            this.diagnostics = immutableLists(diagnostics);
            this.tokens = immutableLists(tokens);
            this.sourceText = Map.copyOf(sourceText);
            this.filesByCompilerPath = Map.copyOf(filesByCompilerPath);
            this.units = List.copyOf(units);
            this.unassociatedDiagnostics = List.copyOf(unassociatedDiagnostics);
            this.resolution = resolution;
            this.memberCatalog = memberCatalog;
        }

        public Map<Path, List<Diagnostic>> diagnostics() { return diagnostics; }
        public Map<Path, List<Token>> tokens() { return tokens; }
        public Map<Path, String> sourceText() { return sourceText; }
        public Map<String, Path> filesByCompilerPath() { return filesByCompilerPath; }
        public List<CompilationUnit> units() { return units; }
        public List<Diagnostic> unassociatedDiagnostics() { return unassociatedDiagnostics; }
        public Optional<ResolutionResult> resolution() { return resolution; }
        public Optional<PublicMemberCatalog> memberCatalog() { return memberCatalog; }

        private static <T> Map<Path, List<T>> immutableLists(final Map<Path, List<T>> source) {
            final var copy = new LinkedHashMap<Path, List<T>>();
            source.forEach((path, values) -> copy.put(path, List.copyOf(values)));
            return Map.copyOf(copy);
        }
    }

    private final List<Path> roots;
    private final List<com.maruseron.zeron.domain.ZeronLibraryIndex> libraries;
    private final Optional<Path> entry;
    private final boolean bundleStandardLibrarySources;
    private final List<Path> javaClassPathRoots;

    private ProjectAnalysisWorkspace(final List<Path> sourceRoots,
                                     final Optional<Path> entry,
                                     final boolean bundleStandardLibrarySources,
                                     final List<com.maruseron.zeron.domain.ZeronLibraryIndex> libraries,
                                     final List<Path> javaClassPathRoots) {
        roots = sourceRoots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        this.libraries = List.copyOf(libraries);
        this.entry = entry;
        this.bundleStandardLibrarySources = bundleStandardLibrarySources;
        this.javaClassPathRoots = List.copyOf(javaClassPathRoots);
    }

    public static ProjectAnalysisWorkspace open(final Path manifest) throws IOException {
        final var project = ProjectManifest.load(manifest);
        for (final var root : project.sourceRoots()) {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("Project source root is not a directory: " + root);
            }
        }
        final var libraries = Zeron.loadLibraries(project.libraryRoots());
        Zeron.validateJavaClassPathRoots(project.javaClassPathRoots());
        Zeron.validateStandardLibraryMode(libraries, project.bundleStandardLibrarySources());
        return new ProjectAnalysisWorkspace(project.sourceRoots(), Optional.of(project.entry()),
                project.bundleStandardLibrarySources(), libraries, project.javaClassPathRoots());
    }

    public static ProjectAnalysisWorkspace openSourceWorkspace(final Path workspaceRoot) {
        final var root = workspaceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Language-server workspace is not a directory: " + root);
        }
        final var standardLibraryRoot = standardLibrarySourceRoot(root);
        final var sourceRoot = standardLibraryRoot.orElse(root);
        return new ProjectAnalysisWorkspace(List.of(sourceRoot), Optional.empty(),
                standardLibraryRoot.isEmpty(), List.of(), List.of());
    }

    public List<Path> sourceRoots() {
        return roots;
    }

    public Snapshot analyze(final Map<Path, Overlay> overlays) throws IOException {
        Objects.requireNonNull(overlays);
        checkCancellation();
        final var normalizedOverlays = new HashMap<Path, Overlay>();
        for (final var entry : overlays.entrySet()) {
            checkCancellation();
            final var path = entry.getKey().toAbsolutePath().normalize();
            if (isInSourceRoots(path) && path.getFileName().toString().endsWith(".zn")) {
                normalizedOverlays.put(path, entry.getValue());
            }
        }

        final var paths = new TreeSet<Path>();
        for (final var root : roots) {
            checkCancellation();
            try (final var files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".zn"))
                        .map(path -> path.toAbsolutePath().normalize())
                        .forEach(path -> {
                            checkCancellation();
                            paths.add(path);
                        });
            }
        }
        paths.addAll(normalizedOverlays.keySet());
        final var entryPath = entry.map(this::absoluteEntry).orElse(null);
        if (entryPath != null && !paths.contains(entryPath)) {
            throw new IllegalArgumentException("Project entry source was not found under its configured roots: "
                    + entry.orElseThrow());
        }

        final var orderedPaths = new ArrayList<Path>();
        if (entryPath != null) orderedPaths.add(entryPath);
        paths.stream().filter(path -> !path.equals(entryPath)).forEach(orderedPaths::add);

        final var units = new ArrayList<CompilationUnit>();
        final var unassociatedDiagnostics = new ArrayList<Diagnostic>();
        final var diagnostics = new LinkedHashMap<Path, List<Diagnostic>>();
        final var tokenLists = new LinkedHashMap<Path, List<Token>>();
        final var sourceText = new LinkedHashMap<Path, String>();
        final var filesByCompilerPath = new LinkedHashMap<String, Path>();
        for (final var path : orderedPaths) {
            checkCancellation();
            final var rootIndex = sourceRootIndex(path);
            final var relative = roots.get(rootIndex).relativize(path);
            final var relativeDirectory = relative.getParent() == null
                    ? ""
                    : relative.getParent().toString().replace('\\', '/').replace('/', '.');
            final var compilerPath = "root" + rootIndex + "/"
                    + relative.toString().replace('\\', '/');
            final var overlay = normalizedOverlays.get(path);
            final var source = overlay == null ? Files.readString(path) : overlay.text();
            sourceText.put(path, source);
            filesByCompilerPath.put(compilerPath, path);

            final var scan = Scanner.from(source, compilerPath).scanWithDiagnostics();
            final var parsed = Parser.of(scan).parseCompilationUnitWithDiagnostics(compilerPath);
            tokenLists.put(path, scan.tokens());
            final var fileDiagnostics = diagnostics.computeIfAbsent(path, _ -> new ArrayList<>());
            fileDiagnostics.addAll(scan.diagnostics());
            fileDiagnostics.addAll(parsed.diagnostics());

            final var unit = parsed.compilationUnit();
            if (!unit.packageName().equals(relativeDirectory)) {
                final var position = new SourcePosition(1, 1, 0);
                fileDiagnostics.add(new Diagnostic(
                        DiagnosticCatalog.PACKAGE_SOURCE_DIRECTORY_MISMATCH.code(),
                        Severity.ERROR,
                        "Package '" + (unit.packageName().isEmpty() ? "<default>" : unit.packageName())
                                + "' in " + path + " must match its directory under source root '"
                                + roots.get(rootIndex) + "' (expected '"
                                + (relativeDirectory.isEmpty() ? "<default>" : relativeDirectory) + "').",
                        new SourceSpan(compilerPath, position, position), List.of(), List.of(), List.of()));
            } else {
                units.add(unit);
            }
        }
        for (final var sourcePath : StandardLibrary.BUNDLED_SOURCE_PATHS) {
            final var file = StandardLibrary.bundledSourceFile(sourcePath).orElse(null);
            if (file != null) {
                filesByCompilerPath.put(sourcePath, file);
                sourceText.put(file, Files.readString(file));
            }
        }

        final var entryCompilerPath = entryPath == null ? null : filesByCompilerPath.entrySet().stream()
                .filter(item -> item.getValue().equals(entryPath))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        final var analysisUnit = entryCompilerPath == null
                ? units.stream().findFirst().orElse(null)
                : units.stream().filter(unit -> Objects.equals(unit.sourcePath(), entryCompilerPath))
                        .findFirst().orElse(null);
        if (analysisUnit == null) {
            return new Snapshot(diagnostics, tokenLists, sourceText, filesByCompilerPath,
                    units, unassociatedDiagnostics, Optional.empty(), Optional.empty());
        }

        final List<CompilationUnit> compilerUnits;
        if (bundleStandardLibrarySources) {
            final var bundled = StandardLibrary.withBundledUnitsWithDiagnostics(units);
            compilerUnits = bundled.units();
            unassociatedDiagnostics.addAll(bundled.diagnostics());
        } else {
            compilerUnits = List.copyOf(units);
        }
        final var compiler = CompilationService.forCompilationUnits(
                compilerUnits, "ZeronLanguageServer", analysisUnit.packageName(), libraries,
                false, javaClassPathRoots);
        checkCancellation();
        final var resolution = compiler.resolveWithDiagnostics();
        checkCancellation();
        for (final var diagnostic : resolution.errors()) {
            final var file = diagnostic.primarySpan().sourcePath() == null
                    ? null : filesByCompilerPath.get(diagnostic.primarySpan().sourcePath());
            if (file == null) unassociatedDiagnostics.add(diagnostic);
            else diagnostics.computeIfAbsent(file, _ -> new ArrayList<>()).add(diagnostic);
        }
        final var catalog = PublicMemberCatalog.fromSource(resolution);
        return new Snapshot(diagnostics, tokenLists, sourceText, filesByCompilerPath,
                units, unassociatedDiagnostics, Optional.of(resolution), Optional.of(catalog));
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Project analysis was cancelled.");
        }
    }

    private Path absoluteEntry(final Path entry) {
        return entry.isAbsolute() ? entry.normalize() : roots.getFirst().resolve(entry).normalize();
    }

    private static Optional<Path> standardLibrarySourceRoot(final Path workspaceRoot) {
        final var nestedStandardLibrary = workspaceRoot.resolve(
                Path.of("src", "main", "resources", "stdlib"));
        if (isStandardLibrarySourceRoot(nestedStandardLibrary)) {
            return Optional.of(nestedStandardLibrary);
        }
        return isStandardLibrarySourceRoot(workspaceRoot)
                ? Optional.of(workspaceRoot)
                : Optional.empty();
    }

    private static boolean isStandardLibrarySourceRoot(final Path root) {
        return Files.isDirectory(root.resolve(Path.of("zeron", "lang")));
    }

    private boolean isInSourceRoots(final Path path) {
        return roots.stream().anyMatch(path::startsWith);
    }

    private int sourceRootIndex(final Path sourcePath) {
        return java.util.stream.IntStream.range(0, roots.size())
                .filter(index -> sourcePath.startsWith(roots.get(index)))
                .boxed()
                .max(Comparator.comparingInt(index -> roots.get(index).getNameCount()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Source file is outside configured roots: " + sourcePath));
    }
}
