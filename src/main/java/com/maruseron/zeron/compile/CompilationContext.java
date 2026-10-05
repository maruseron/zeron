package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ResolutionResult;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.FunctionBindingRegistry;
import com.maruseron.zeron.domain.SymbolTable;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Token;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Label;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CompilationContext {
    final ClassFile classFile = ClassFile.of();
    final List<Stmt> topLevelDeclarations;
    final List<CompilationUnit> compilationUnits;
    final Path outputDirectory;
    final String mainClassName;
    final boolean includeBundledSourcesInIndex;
    final boolean libraryBuild;
    final CompilationMetadata metadata;
    final List<Path> javaClassPathRoots;
    final FunctionBindingRegistry functionBindings;
    final Map<Stmt, String> sourcePathsByDeclaration = new IdentityHashMap<>();

    ResolutionResult resolution;
    LambdaCompilationPlan lambdaPlan;
    InitializationPlanner.Plan initializationPlan =
            new InitializationPlanner.Plan(List.of(), java.util.Map.of(), List.of());
    String currentHolderName;
    SymbolTable symbols;
    TypeDescriptor lastEmittedType;
    TypeDescriptor currentReturnType;
    int localSlotOffset;
    boolean emittingLambdaImplementation;
    int loopTemporaryCount;
    int propertyTemporaryCount;
    final Deque<Label> loopExitLabels = new ArrayDeque<>();
    final Deque<Label> loopContinueLabels = new ArrayDeque<>();

    CompilationContext(final List<CompilationUnit> units,
                       final String mainClassName,
                       final List<ZeronLibraryIndex> libraries,
                       final Path outputDirectory,
                       final boolean includeBundledSourcesInIndex,
                       final boolean libraryBuild,
                       final List<Path> javaClassPathRoots,
                       final FunctionBindingRegistry functionBindings) {
        compilationUnits = List.copyOf(units);
        this.outputDirectory = outputDirectory;
        this.includeBundledSourcesInIndex = includeBundledSourcesInIndex;
        this.libraryBuild = libraryBuild;
        this.javaClassPathRoots = List.copyOf(javaClassPathRoots);
        this.functionBindings = Objects.requireNonNull(functionBindings);
        metadata = new CompilationMetadata(compilationUnits, libraries, mainClassName);
        topLevelDeclarations = compilationUnits.stream().filter(unit -> !unit.metadataOnly())
                .flatMap(unit -> unit.declarations().stream()).toList();
        for (final var unit : compilationUnits) {
            for (final var declaration : unit.declarations()) {
                sourcePathsByDeclaration.put(declaration, unit.sourcePath());
            }
        }
        this.mainClassName = mainClassName;
    }

    String sourcePath(final Stmt declaration) {
        return sourcePathsByDeclaration.get(declaration);
    }

    String sourcePath(final Token token) {
        for (final var declaration : topLevelDeclarations) {
            if (declarationName(declaration) == token) return sourcePath(declaration);
        }
        return null;
    }

    private static Token declarationName(final Stmt declaration) {
        return switch (declaration) {
            case Stmt.ClassDecl classDeclaration -> classDeclaration.name();
            case Stmt.ContractDecl contractDeclaration -> contractDeclaration.name();
            case Stmt.FunctionDeclaration function -> function.name();
            case Stmt.Var variable -> variable.name();
            default -> null;
        };
    }
}
