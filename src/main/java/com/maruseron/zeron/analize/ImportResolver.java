package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.JavaClassPath;

import java.util.*;

final class ImportResolver {
    record Validation(ImportEnvironment environment, List<ResolutionError> errors,
                      Set<String> invalidAliases) {}

    record ImportEnvironment(Map<String, String> types,
                             Map<String, String> functions,
                             Map<String, String> values,
                             Map<String, String> extensions,
                             List<String> onDemandPackages) {}

    private static final Set<String> BUILTIN_TYPES = Set.of(
            "Never", "Any", "Infer", "Unit", "Int", "Float", "Boolean", "String", "Array");

    private final Map<String, Stmt.ClassDecl> classes;
    private final Map<String, Stmt.ContractDecl> contracts;
    private final Map<String, Stmt.FunctionDeclaration> functions;
    private final Map<String, List<Stmt.ExtensionMethod>> extensionMethods;
    private final Map<String, Stmt.Var> topLevelValues;
    private final Map<String, String> declarationPackages;
    private final JavaClassPath javaClassPath;

    ImportResolver(    final Map<String, Stmt.ClassDecl> classes,
    final Map<String, Stmt.ContractDecl> contracts,
    final Map<String, Stmt.FunctionDeclaration> functions,
    final Map<String, List<Stmt.ExtensionMethod>> extensionMethods,
    final Map<String, Stmt.Var> topLevelValues,
                   final Map<String, String> declarationPackages,
                   final JavaClassPath javaClassPath) {
        this.classes = classes;
        this.contracts = contracts;
        this.functions = functions;
        this.extensionMethods = extensionMethods;
        this.topLevelValues = topLevelValues;
        this.declarationPackages = declarationPackages;
        this.javaClassPath = javaClassPath;
    }

    Validation validate(final CompilationUnit unit) {
        final var importedTypes = new LinkedHashMap<String, String>();
        final var importedFunctions = new LinkedHashMap<String, String>();
        final var importedValues = new LinkedHashMap<String, String>();
        final var importedExtensions = new LinkedHashMap<String, String>();
        final var onDemandPackages = new ArrayList<String>();
        final var localTypeNames = new HashSet<String>();
        final var localValueNames = new HashSet<String>();
        final var errors = new ArrayList<ResolutionError>();
        final var invalidAliases = new HashSet<String>();
        classes.keySet().stream().filter(name -> packageOf(name).equals(unit.packageName()))
                .map(ImportResolver::simpleName).forEach(localTypeNames::add);
        contracts.keySet().stream().filter(name -> packageOf(name).equals(unit.packageName()))
                .map(ImportResolver::simpleName).forEach(localTypeNames::add);
        functions.keySet().stream().filter(name -> packageOf(name).equals(unit.packageName()))
                .map(ImportResolver::simpleName).forEach(localValueNames::add);
        topLevelValues.keySet().stream().filter(name -> packageOf(name).equals(unit.packageName()))
                .map(ImportResolver::simpleName).forEach(localValueNames::add);
        unit.declarations().stream().filter(Stmt.Var.class::isInstance).map(Stmt.Var.class::cast)
                .map(variable -> variable.name().lexeme()).forEach(localValueNames::add);

        for (final var importDeclaration : unit.imports()) {
            try {
            final var target = importDeclaration.qualifiedName();
            if (importDeclaration.onDemand()) {
                if (onDemandPackages.contains(target)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Duplicate star import for package '" + target + "'."));
                }
                if (!packageExists(target)) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Unknown Zeron package '" + target + "' in star import."));
                }
                onDemandPackages.add(target);
                continue;
            }
            final var classDeclaration = classes.get(target);
            final var contractDeclaration = contracts.get(target);
            final var functionDeclaration = functions.get(target);
            final var extensionFamily = extensionMethods.getOrDefault(target, List.of());
            final var valueDeclaration = topLevelValues.get(target);
            final var javaClass = javaClassPath.find(target);
            if (classDeclaration == null && contractDeclaration == null
                    && functionDeclaration == null && extensionFamily.isEmpty()
                    && valueDeclaration == null && javaClass == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                        importDeclaration.location(),
                        "Unknown import target '" + target + "'."));
            }
            final var targetPackage = declarationPackages.getOrDefault(target, packageOf(target));
            if (targetPackage.isEmpty() && !unit.packageName().isEmpty()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                        importDeclaration.location(),
                        "Default-package types cannot be imported into a named package."));
            }
            final var isPublic = classDeclaration != null ? classDeclaration.isPublic()
                    : contractDeclaration != null ? contractDeclaration.isPublic()
                    : functionDeclaration != null ? functionDeclaration.isPublic()
                    : !extensionFamily.isEmpty() ? extensionFamily.stream().anyMatch(Stmt.ExtensionMethod::isPublic)
                    : valueDeclaration != null ? valueDeclaration.isPublic()
                    : true;
            if (!targetPackage.equals(unit.packageName()) && !isPublic) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                        importDeclaration.location(),
                        (valueDeclaration == null ? "Type '" : "Value '") + target + "' is not public."));
            }
            if (classDeclaration != null || contractDeclaration != null || javaClass != null) {
                if (javaClass != null && javaClass.hasGenericSignature()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                            importDeclaration.location(),
                            "Generic Java classes are not supported by the current interop slice."));
                }
                if (BUILTIN_TYPES.contains(importDeclaration.localName())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Type import alias cannot shadow a built-in type."));
                }
                if (localTypeNames.contains(importDeclaration.localName())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Import alias conflicts with a type in the current package."));
                }
                if (importedTypes.putIfAbsent(importDeclaration.localName(), target) != null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Duplicate or ambiguous type import '" + importDeclaration.localName() + "'."));
                }
            } else if (valueDeclaration != null) {
                if (importedFunctions.containsKey(importDeclaration.localName())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Import alias conflicts with an imported function."));
                }
                if (localValueNames.contains(importDeclaration.localName())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Import alias conflicts with a value in the current package."));
                }
                if (importedValues.putIfAbsent(importDeclaration.localName(), target) != null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Duplicate or ambiguous value import '" + importDeclaration.localName() + "'."));
                }
            } else if (!extensionFamily.isEmpty()) {
                if (importedExtensions.putIfAbsent(importDeclaration.localName(), target) != null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Duplicate or ambiguous extension import '" + importDeclaration.localName() + "'."));
                }
            } else {
                if (importedValues.containsKey(importDeclaration.localName())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Import alias conflicts with an imported value."));
                }
                if (localValueNames.contains(importDeclaration.localName())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Import alias conflicts with a value in the current package."));
                }
                if (importedFunctions.putIfAbsent(importDeclaration.localName(), target) != null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT,
                            importDeclaration.location(),
                            "Duplicate or ambiguous function import '" + importDeclaration.localName() + "'."));
                }
            }
            } catch (final ResolutionError error) {
                errors.add(error);
                invalidAliases.add(importDeclaration.localName());
            }
        }
        for (final var packageName : List.of("zeron.io", "zeron.lang")) {
            if (packageExists(packageName) && !onDemandPackages.contains(packageName)) {
                onDemandPackages.add(packageName);
            }
        }
        return new Validation(new ImportEnvironment(Map.copyOf(importedTypes), Map.copyOf(importedFunctions),
                Map.copyOf(importedValues), Map.copyOf(importedExtensions), List.copyOf(onDemandPackages)),
                List.copyOf(errors),
                Set.copyOf(invalidAliases));
    }

    private boolean packageExists(final String packageName) {
        return classes.keySet().stream().anyMatch(name -> packageOf(name).equals(packageName))
                || contracts.keySet().stream().anyMatch(name -> packageOf(name).equals(packageName))
                || declarationPackages.containsValue(packageName);
    }

    private static String packageOf(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? "" : qualifiedName.substring(0, separator);
    }

    private static String simpleName(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    }
}
