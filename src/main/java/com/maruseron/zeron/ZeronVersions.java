package com.maruseron.zeron;

import java.nio.file.Path;

public final class ZeronVersions {
    public static final int API_INDEX_SCHEMA_VERSION = 6;
    public static final int STANDARD_LIBRARY_API_VERSION = 7;
    public static final String API_INDEX_FILE_NAME = "api-v" + API_INDEX_SCHEMA_VERSION + ".bin";
    public static final String API_INDEX_JAR_ENTRY = "META-INF/zeron/" + API_INDEX_FILE_NAME;
    public static final Path API_INDEX_RELATIVE_PATH =
            Path.of("META-INF", "zeron", API_INDEX_FILE_NAME);

    private ZeronVersions() {}
}
