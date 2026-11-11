package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

public record ImportDeclaration(String qualifiedName, String localName, Token location,
                                boolean onDemand, boolean suffixImport, boolean witness) {
    public ImportDeclaration(final String qualifiedName, final String localName, final Token location,
                             final boolean onDemand, final boolean suffixImport) {
        this(qualifiedName, localName, location, onDemand, suffixImport, false);
    }

    public ImportDeclaration(final String qualifiedName, final String localName, final Token location,
                             final boolean onDemand) {
        this(qualifiedName, localName, location, onDemand, false, false);
    }

    public ImportDeclaration(final String qualifiedName, final String localName, final Token location) {
        this(qualifiedName, localName, location, false, false, false);
    }
}