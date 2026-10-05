package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.analize.ResolutionResult;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.FunctionBindingRegistry;
import com.maruseron.zeron.domain.ZeronLibraryIndex;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class CompilationService {
    private static final Path DEFAULT_OUTPUT_DIRECTORY = Paths.get("dist");

    private final CompilationContext context;

    public CompilationService(final List<Stmt> declarations) {
        this(declarations, "ZeronMain");
    }

    public CompilationService(final List<Stmt> declarations, final String mainClassName) {
        this(declarations, mainClassName, "");
    }

    public CompilationService(final List<Stmt> declarations,
                              final String mainClassName,
                              final String packageName) {
        context = new CompilationContext(
                StandardLibrary.withBundledUnits(List.of(new CompilationUnit(null, packageName, declarations))),
                mainClassName, List.of(), DEFAULT_OUTPUT_DIRECTORY,
                true, false, List.of(), FunctionBindingRegistry.standard());
    }

    public static CompilationService forCompilationUnits(final List<CompilationUnit> units,
                                                          final String mainClassName,
                                                          final String packageName) {
        return forCompilationUnits(units, mainClassName, packageName, List.of());
    }

    public static CompilationService forCompilationUnits(final List<CompilationUnit> units,
                                                          final String mainClassName,
                                                          final String packageName,
                                                          final List<ZeronLibraryIndex> libraries) {
        return forCompilationUnits(units, mainClassName, packageName, libraries, true);
    }

    public static CompilationService forCompilationUnits(final List<CompilationUnit> units,
                                                          final String mainClassName,
                                                          final String packageName,
                                                          final List<ZeronLibraryIndex> libraries,
                                                          final boolean bundleStandardLibrarySources) {
        return forCompilationUnits(units, mainClassName, packageName, libraries,
                bundleStandardLibrarySources, List.of());
    }

    public static CompilationService forCompilationUnits(final List<CompilationUnit> units,
                                                          final String mainClassName,
                                                          final String packageName,
                                                          final List<ZeronLibraryIndex> libraries,
                                                          final boolean bundleStandardLibrarySources,
                                                          final List<Path> javaClassPathRoots) {
        return forCompilationUnits(units, mainClassName, packageName, libraries,
                bundleStandardLibrarySources, javaClassPathRoots, FunctionBindingRegistry.standard());
    }

    public static CompilationService forCompilationUnits(final List<CompilationUnit> units,
                                                          final String mainClassName,
                                                          final String packageName,
                                                          final List<ZeronLibraryIndex> libraries,
                                                          final boolean bundleStandardLibrarySources,
                                                          final List<Path> javaClassPathRoots,
                                                          final FunctionBindingRegistry functionBindings) {
        return forCompilationUnits(units, mainClassName, packageName, libraries,
                bundleStandardLibrarySources, javaClassPathRoots, functionBindings, DEFAULT_OUTPUT_DIRECTORY);
    }

    public static CompilationService forCompilationUnits(final List<CompilationUnit> units,
                                                          final String mainClassName,
                                                          final String packageName,
                                                          final List<ZeronLibraryIndex> libraries,
                                                          final boolean bundleStandardLibrarySources,
                                                          final List<Path> javaClassPathRoots,
                                                          final Path outputDirectory) {
        return forCompilationUnits(units, mainClassName, packageName, libraries,
                bundleStandardLibrarySources, javaClassPathRoots, FunctionBindingRegistry.standard(), outputDirectory);
    }

    private static CompilationService forCompilationUnits(final List<CompilationUnit> units,
                                                           final String mainClassName,
                                                           final String packageName,
                                                           final List<ZeronLibraryIndex> libraries,
                                                           final boolean bundleStandardLibrarySources,
                                                           final List<Path> javaClassPathRoots,
                                                           final FunctionBindingRegistry functionBindings,
                                                           final Path outputDirectory) {
        final var combinedUnits = new ArrayList<>(units);
        for (int i = 0; i < libraries.size(); i++) {
            combinedUnits.addAll(libraries.get(i).toCompilationUnits("library-index-" + i));
        }
        final var compilationUnits = bundleStandardLibrarySources
                ? StandardLibrary.withBundledUnits(combinedUnits)
                : List.copyOf(combinedUnits);
        return new CompilationService(new CompilationContext(compilationUnits, mainClassName,
                libraries, outputDirectory, false, false, javaClassPathRoots, functionBindings));
    }

    public static CompilationService forStandardLibrary(final Path outputDirectory) {
        return new CompilationService(new CompilationContext(StandardLibrary.bundledUnits(),
                "zeron.$StandardLibrary", List.of(), outputDirectory, true, true, List.of(),
                FunctionBindingRegistry.standard()));
    }

    private CompilationService(final CompilationContext context) {
        this.context = context;
    }

    public void resolve() {
        final var result = resolveWithDiagnostics();
        if (!result.errors().isEmpty()) {
            result.errors().forEach(com.maruseron.zeron.Zeron::reportResolutionDiagnostic);
            throw ResolutionError.fromDiagnostic(result.errors().getFirst());
        }
    }

    public void compile() throws IOException {
        final var result = compileWithDiagnostics();
        if (!result.successful()) {
            throw new IllegalStateException(result.diagnostics().getFirst().message());
        }
    }

    public ResolutionResult resolveWithDiagnostics() {
        return Compiler.resolve(context);
    }

    public CompilationResult compileWithDiagnostics() throws IOException {
        return Compiler.compile(context);
    }
}
