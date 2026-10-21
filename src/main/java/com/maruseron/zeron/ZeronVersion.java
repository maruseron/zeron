package com.maruseron.zeron;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class ZeronVersion {
    private static final String METADATA_RESOURCE = "/META-INF/zeron/version.properties";

    private ZeronVersion() {}

    static String current() throws IOException {
        try (final var input = ZeronVersion.class.getResourceAsStream(METADATA_RESOURCE)) {
            return read(input);
        }
    }

    static String read(final InputStream input) throws IOException {
        if (input == null) return "development";

        final var properties = new Properties();
        properties.load(input);
        final var version = properties.getProperty("version");
        if (version == null || version.isBlank()) {
            throw new IOException("Zeron version metadata does not contain a version.");
        }
        return version.strip();
    }
}
