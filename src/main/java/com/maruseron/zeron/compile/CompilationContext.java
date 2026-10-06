package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ResolutionResult;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.NamespaceMembers;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.FunctionBindingRegistry;
import com.maruseron.zeron.domain.SymbolTable;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Token;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CompilationContext {
    final ClassFile classFile;
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
        classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                sourceTypeResolver(compilationUnits)));
        this.outputDirectory = outputDirectory;
        this.includeBundledSourcesInIndex = includeBundledSourcesInIndex;
        this.libraryBuild = libraryBuild;
        this.javaClassPathRoots = List.copyOf(javaClassPathRoots);
        this.functionBindings = Objects.requireNonNull(functionBindings);
        metadata = new CompilationMetadata(compilationUnits, libraries, mainClassName);
        topLevelDeclarations = compilationUnits.stream().filter(unit -> !unit.metadataOnly())
                .flatMap(unit -> NamespaceMembers.flatten(unit.declarations()).stream())
                .map(NamespaceMembers.Member::declaration).toList();
        for (final var unit : compilationUnits) {
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                sourcePathsByDeclaration.put(member.declaration(), unit.sourcePath());
            }
        }
        this.mainClassName = mainClassName;
    }

    private static ClassHierarchyResolver sourceTypeResolver(final List<CompilationUnit> units) {
        final var sourceTypes = new HashMap<ClassDesc, ClassHierarchyResolver.ClassHierarchyInfo>();
        for (final var unit : units) {
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                final var declaration = member.declaration();
                if (declaration instanceof Stmt.ClassDecl classDeclaration) {
                    sourceTypes.put(ClassDesc.of(classDeclaration.name().lexeme()),
                            ClassHierarchyResolver.ClassHierarchyInfo.ofClass(ClassDesc.of("java.lang.Object")));
                } else if (declaration instanceof Stmt.ContractDecl contractDeclaration) {
                    sourceTypes.put(ClassDesc.of(contractDeclaration.name().lexeme()),
                            ClassHierarchyResolver.ClassHierarchyInfo.ofInterface());
                }
            }
        }
        final var fallback = ClassHierarchyResolver.defaultResolver();
        return resolver -> {
            final var sourceType = sourceTypes.get(resolver);
            return sourceType != null ? sourceType : fallback.getClassInfo(resolver);
        };
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
