package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.NamespaceMembers;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.ZeronLibraryIndex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class CompilationMetadata {
    private final IdentityHashMap<Stmt, String> declarationOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Stmt.Var, String> initializationOwners = new IdentityHashMap<>();
    private final Map<String, String> functionOwners = new HashMap<>();
    private final Map<String, String> valueOwners = new HashMap<>();
    private final Map<String, String> valueInitializationOwners = new HashMap<>();
    private final Set<String> metadataTypeNames = new HashSet<>();

    CompilationMetadata(final List<CompilationUnit> units,
                        final List<ZeronLibraryIndex> libraries,
                        final String mainClassName) {
        indexDeclarationOwners(units, mainClassName);
        for (final var unit : units) {
            if (!unit.metadataOnly()) continue;
            for (final var declaration : unit.declarations()) {
                if (declaration instanceof Stmt.ClassDecl classDeclaration) {
                    metadataTypeNames.add(classDeclaration.name().lexeme());
                } else if (declaration instanceof Stmt.ContractDecl contractDeclaration) {
                    metadataTypeNames.add(contractDeclaration.name().lexeme());
                }
            }
        }
        for (final var library : libraries) {
            for (final var declaration : library.declarations()) {
                if (declaration instanceof ZeronLibraryIndex.FunctionExport function) {
                    functionOwners.putIfAbsent(function.qualifiedName(), function.jvmOwner());
                } else if (declaration instanceof ZeronLibraryIndex.ExtensionExport extension) {
                    functionOwners.putIfAbsent(extension.qualifiedName(), extension.jvmOwner());
                } else if (declaration instanceof ZeronLibraryIndex.ValueExport value) {
                    valueOwners.putIfAbsent(value.qualifiedName(), value.jvmOwner());
                    valueInitializationOwners.putIfAbsent(value.qualifiedName(), value.initializationOwner());
                } else if (declaration instanceof ZeronLibraryIndex.WitnessExport witness) {
                    for (final var method : witness.methods()) {
                        functionOwners.putIfAbsent(method.helperQualifiedName(), method.helperJvmOwner());
                    }
                }
                for (final var unit : units) {
                    if (!unit.metadataOnly()) continue;
                    for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                        if (!(member.declaration() instanceof Stmt.FunctionDeclaration function)) continue;
                        final var qualifiedName = member.namespaceName() == null
                                ? qualifiedName(unit.packageName(), function.name().lexeme())
                                : NamespaceMembers.qualifiedName(unit.packageName(), member.namespaceName(),
                                        function.name().lexeme());
                        final var owner = libraries.stream().flatMap(libraryIndex -> libraryIndex.declarations().stream())
                                .filter(export -> export.qualifiedName().equals(qualifiedName))
                                .filter(export -> switch (export) {
                                    case ZeronLibraryIndex.FunctionExport functionExport ->
                                            !(function instanceof Stmt.ExtensionMethod)
                                                    && functionExport.signature().equals(function.typeDescriptor());
                                    case ZeronLibraryIndex.ExtensionExport extensionExport ->
                                            function instanceof Stmt.ExtensionMethod
                                                    && extensionExport.signature().equals(function.typeDescriptor());
                                    default -> false;
                                })
                                .map(export -> switch (export) {
                                    case ZeronLibraryIndex.FunctionExport functionExport -> functionExport.jvmOwner();
                                    case ZeronLibraryIndex.ExtensionExport extensionExport -> extensionExport.jvmOwner();
                                    default -> null;
                                })
                                .findFirst().orElse(null);
                        if (owner != null) declarationOwners.put(function, owner);
                    }
                }
            }
        }
        for (final var unit : units) {
            if (!unit.metadataOnly()) continue;
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                if (!(member.declaration() instanceof Stmt.Var variable)) continue;
                final var qualifiedName = member.namespaceName() == null
                        ? qualifiedName(unit.packageName(), variable.name().lexeme())
                        : NamespaceMembers.qualifiedName(
                                unit.packageName(), member.namespaceName(), variable.name().lexeme());
                final var owner = valueOwners.get(qualifiedName);
                final var initializationOwner = valueInitializationOwners.get(qualifiedName);
                if (owner != null) declarationOwners.put(variable, owner);
                if (initializationOwner != null) {
                    initializationOwners.put(variable, initializationOwner);
                }
            }
        }
    }

    Map<Stmt, String> declarationOwners() {
        return Collections.unmodifiableMap(declarationOwners);
    }

    Map<String, String> functionOwners() {
        return Collections.unmodifiableMap(functionOwners);
    }

    String valueOwner(final Stmt.Var variable) {
        return declarationOwners.get(variable);
    }

    String initializationOwner(final Stmt.Var variable) {
        return initializationOwners.getOrDefault(variable, "");
    }

    Set<String> metadataTypeNames() {
        return Collections.unmodifiableSet(metadataTypeNames);
    }

    String declarationOwner(final Stmt declaration, final String fallback) {
        return declarationOwners.getOrDefault(declaration, fallback);
    }

    String functionOwner(final String qualifiedName) {
        return functionOwners.get(qualifiedName);
    }

    String functionOwner(final Stmt.FunctionDeclaration function) {
        return declarationOwners.get(function);
    }

    void indexResolvedFunctions(final List<CompilationUnit> units, final String mainClassName) {
        for (var unitIndex = 0; unitIndex < units.size(); unitIndex++) {
            final var unit = units.get(unitIndex);
            if (unit.metadataOnly()) continue;
            final var owner = unitIndex == 0 ? mainClassName : holderName(unit, unitIndex);
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                if (!(member.declaration() instanceof Stmt.FunctionDeclaration function)) continue;
                if (!function.name().lexeme().startsWith("$zeron$witness$")) continue;
                final var qualifiedName = member.namespaceName() == null
                        ? qualifiedName(unit.packageName(), function.name().lexeme())
                        : NamespaceMembers.qualifiedName(unit.packageName(), member.namespaceName(),
                                function.name().lexeme());
                functionOwners.put(qualifiedName, owner);
                declarationOwners.put(function, owner);
            }
        }
    }

    String holderName(final CompilationUnit unit, final int unitIndex) {
        final var sourcePath = Objects.requireNonNullElse(unit.sourcePath(),
                "anonymous" + unitIndex + ".zn").replace('\\', '/');
        final var fileName = Path.of(sourcePath).getFileName().toString().replaceFirst("\\.zn$", "")
                .replaceAll("[^A-Za-z0-9_$]", "_");
        final var owner = "$File$" + fileName + "$" + shortHash(sourcePath);
        return unit.packageName().isEmpty() ? owner : unit.packageName() + "." + owner;
    }

    static String qualifiedName(final String ownerPackage, final String name) {
        return ownerPackage == null || ownerPackage.isEmpty() ? name : ownerPackage + "." + name;
    }

    private void indexDeclarationOwners(final List<CompilationUnit> units, final String mainClassName) {
        for (var unitIndex = 0; unitIndex < units.size(); unitIndex++) {
            final var unit = units.get(unitIndex);
            if (unit.metadataOnly()) continue;
            final var owner = unitIndex == 0 ? mainClassName : holderName(unit, unitIndex);
                for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                    final var declaration = member.declaration();
                    final var declarationOwner = member.namespaceName() == null
                            ? owner : namespaceOwner(unit.packageName(), member.namespaceName());
                    if (declaration instanceof Stmt.FunctionDeclaration function) {
                        declarationOwners.put(declaration, declarationOwner);
                        final var functionName = member.namespaceName() == null
                                ? qualifiedName(unit.packageName(), function.name().lexeme())
                                : NamespaceMembers.qualifiedName(unit.packageName(), member.namespaceName(),
                                        function.name().lexeme());
                        functionOwners.put(functionName, declarationOwner);
                    } else if (declaration instanceof Stmt.Var) {
                        declarationOwners.put(declaration, declarationOwner);
                        final var variable = (Stmt.Var) declaration;
                        final var valueName = member.namespaceName() == null
                                ? qualifiedName(unit.packageName(), variable.name().lexeme())
                                : NamespaceMembers.qualifiedName(
                                        unit.packageName(), member.namespaceName(), variable.name().lexeme());
                        valueOwners.put(valueName, declarationOwner);
                        initializationOwners.put(variable, mainClassName);
                    }
                }
            }
    }

    static String namespaceOwner(final String ownerPackage, final String namespaceName) {
            final var name = "$zeron$Namespace$" + namespaceName;
            return ownerPackage == null || ownerPackage.isEmpty() ? name : ownerPackage + "." + name;
    }

    private static String shortHash(final String value) {
        try {
            final var bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }
}
