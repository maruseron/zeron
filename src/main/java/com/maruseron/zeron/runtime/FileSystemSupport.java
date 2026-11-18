package com.maruseron.zeron.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/// Host-side support for `zeron.fs.Path`. Results are returned as `Object[]` so the Zeron facade can
/// declare the element type it expects; every element is a non-null path string.
public final class FileSystemSupport {
    private FileSystemSupport() {}

    public static Object[] list(final Path path) throws IOException {
        try (Stream<Path> entries = Files.list(path)) {
            return entries.map(Path::toString).toArray();
        }
    }

    public static Object[] walk(final Path path) throws IOException {
        try (Stream<Path> entries = Files.walk(path)) {
            return entries.map(Path::toString).toArray();
        }
    }
}
