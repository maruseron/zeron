package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

public record ImportDeclaration(String qualifiedName, String localName, Token location,
                                boolean onDemand, boolean suffixImport) {
    public ImportDeclaration(final String qualifiedName, final String localName, final Token location,
                             final boolean onDemand) {
        this(qualifiedName, localName, location, onDemand, false);
    }

    public ImportDeclaration(final String qualifiedName, final String localName, final Token location) {
        this(qualifiedName, localName, location, false, false);
    }
}