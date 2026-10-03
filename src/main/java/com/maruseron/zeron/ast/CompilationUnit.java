package com.maruseron.zeron.ast;

import java.util.List;

public record CompilationUnit(String sourcePath,
                              String packageName,
                              List<ImportDeclaration> imports,
                              List<Stmt> declarations,
                              boolean metadataOnly) {
    public CompilationUnit {
        packageName = packageName == null ? "" : packageName;
        imports = List.copyOf(imports);
        declarations = List.copyOf(declarations);
    }

    public CompilationUnit(final String sourcePath,
                           final String packageName,
                           final List<Stmt> declarations) {
        this(sourcePath, packageName, List.of(), declarations, false);
    }

    public CompilationUnit(final String sourcePath,
                           final String packageName,
                           final List<ImportDeclaration> imports,
                           final List<Stmt> declarations) {
        this(sourcePath, packageName, imports, declarations, false);
    }
}