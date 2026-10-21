package com.maruseron.zeron.ast;

import com.maruseron.zeron.scan.Token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SyntheticNames {
    private SyntheticNames() {}

    public static String witnessMethod(final Token witnessName, final String methodName) {
        final var sourcePath = witnessName.span().sourcePath();
        final var sourceId = sourcePath == null ? "inline" : sourceId(sourcePath);
        return "$zeron$witness$" + sourceId + "$" + witnessName.span().start().offset() + "$" + methodName;
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
