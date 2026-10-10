package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class ResolutionService {
    private final String packageName;
    private final List<Path> javaClassPathRoots;
    private final FunctionBindingRegistry functionBindings;

    public ResolutionService() {
        this("", List.of(), FunctionBindingRegistry.standard());
    }

    public ResolutionService(final String packageName) {
        this(packageName, List.of(), FunctionBindingRegistry.standard());
    }

    public ResolutionService(final String packageName, final List<Path> javaClassPathRoots) {
        this(packageName, javaClassPathRoots, FunctionBindingRegistry.standard());
    }

    public ResolutionService(final String packageName,
                             final List<Path> javaClassPathRoots,
                             final FunctionBindingRegistry functionBindings) {
        this.packageName = packageName == null ? "" : packageName;
        this.javaClassPathRoots = List.copyOf(javaClassPathRoots);
        this.functionBindings = FunctionBindingRegistry.standard()
                .withBindings(Objects.requireNonNull(functionBindings));
    }

    public ResolutionResult resolve(final List<Stmt> statements) {
        return resolveUnits(List.of(new CompilationUnit(null, packageName, statements)));
    }

    public ResolutionResult resolveUnits(final List<CompilationUnit> units) {
        final var result = resolveUnitsWithDiagnostics(units);
        if (!result.errors().isEmpty()) throw ResolutionError.fromDiagnostic(result.errors().getFirst());
        return result;
    }

    public ResolutionResult resolveWithDiagnostics(final List<Stmt> statements) {
        return resolveUnitsWithDiagnostics(List.of(new CompilationUnit(null, packageName, statements)));
    }

    public ResolutionResult resolveUnitsWithDiagnostics(final List<CompilationUnit> units) {
        final var context = new ResolutionContext(packageName,
                new JavaClassPath(javaClassPathRoots), functionBindings);
        return Resolver.resolveUnits(context, units);
    }
}
