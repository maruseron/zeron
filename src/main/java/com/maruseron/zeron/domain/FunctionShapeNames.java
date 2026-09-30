package com.maruseron.zeron.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class FunctionShapeNames {
    private static final String INTERFACE_PREFIX =
            "com.maruseron.zeron.runtime.lambda.Lambda$V1_";

    private FunctionShapeNames() {}

    public static String interfaceName(final FunctionShapeKey key) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256").digest(
                    key.canonicalEncoding().getBytes(StandardCharsets.UTF_8));
            return INTERFACE_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by the Java platform", e);
        }
    }

    public static String interfaceName(final FunctionDescriptor functionType) {
        return interfaceName(FunctionShapeKey.of(functionType));
    }
}