package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

public record ImportDeclaration(String qualifiedName, String localName, Token location) {}