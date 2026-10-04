package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

public record ImportDeclaration(String qualifiedName, String localName, Token location,
                                boolean onDemand) {
    public ImportDeclaration(final String qualifiedName, final String localName, final Token location) {
        this(qualifiedName, localName, location, false);
    }
}