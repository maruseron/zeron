package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.ZeronLibraryIndex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class CompilationMetadata {
    private final IdentityHashMap<Stmt, String> declarationOwners = new IdentityHashMap<>();
    private final Map<String, String> functionOwners = new HashMap<>();
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

    Set<String> metadataTypeNames() {
        return Collections.unmodifiableSet(metadataTypeNames);
    }

    String declarationOwner(final Stmt declaration, final String fallback) {
        return declarationOwners.getOrDefault(declaration, fallback);
    }

    String functionOwner(final String qualifiedName) {
        return functionOwners.get(qualifiedName);
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
            for (final var declaration : unit.declarations()) {
                if (declaration instanceof Stmt.FunctionDeclaration function) {
                    declarationOwners.put(declaration, owner);
                    functionOwners.put(
                            qualifiedName(unit.packageName(), function.name().lexeme()), owner);
                } else if (declaration instanceof Stmt.Var) {
                    declarationOwners.put(declaration, owner);
                }
            }
        }
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
