package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SyntheticNames {
    /// The standard-library property-reference type.
    public static final String PROPERTY_REF_TYPE = "zeron.lang.PropertyRef";
    /// The compiler-only factory that constructs property references. `$` is not a legal
    /// character in a Zeron identifier, so source code cannot call this member.
    public static final String PROPERTY_REF_FACTORY = "$zeron$propertyRef";
    /// The standard-library property-identity type.
    public static final String PROPERTY_ID_TYPE = "zeron.lang.PropertyId";
    /// The compiler-only factory that constructs property identities.
    public static final String PROPERTY_ID_FACTORY = "$zeron$propertyId";

    private SyntheticNames() {}

    public static String witnessMethod(final Token witnessName, final String methodName) {
        final var sourcePath = witnessName.span().sourcePath();
        final var sourceId = sourcePath == null ? "inline" : sourceId(sourcePath);
        return "$zeron$witness$" + sourceId + "$" + witnessName.span().start().offset() + "$" + methodName;
    }

    public static String hktDefaultMethod(final String contractName, final String methodName) {
        return "$zeron$hkt$default$" + sourceId(contractName + "#" + methodName) + "$" + methodName;
    }

    public static String hktDefaultOwner(final String contractName) {
        return contractName + "$zeron$defaults";
    }

    private static String sourceId(final String sourcePath) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sourcePath.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for stable synthetic names.", exception);
        }
    }
}
