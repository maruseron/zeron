package com.maruseron.zeron.ast;

import java.util.List;
import java.util.Map;

public record CompilationUnit(String sourcePath,
                              String packageName,
                              List<ImportDeclaration> imports,
                              List<Stmt> declarations,
                              boolean metadataOnly,
                              Map<com.maruseron.zeron.scan.Token, String> documentation) {
    public CompilationUnit {
        packageName = packageName == null ? "" : packageName;
        imports = List.copyOf(imports);
        declarations = List.copyOf(declarations);
        documentation = Map.copyOf(documentation);
    }

    public CompilationUnit(final String sourcePath, final String packageName,
                           final List<ImportDeclaration> imports, final List<Stmt> declarations,
                           final boolean metadataOnly) {
        this(sourcePath, packageName, imports, declarations, metadataOnly, Map.of());
    }

    public CompilationUnit(final String sourcePath,
                           final String packageName,
                           final List<Stmt> declarations) {
        this(sourcePath, packageName, List.of(), declarations, false, Map.of());
    }

    public CompilationUnit(final String sourcePath,
                           final String packageName,
                           final List<ImportDeclaration> imports,
                           final List<Stmt> declarations) {
        this(sourcePath, packageName, imports, declarations, false, Map.of());
    }
}