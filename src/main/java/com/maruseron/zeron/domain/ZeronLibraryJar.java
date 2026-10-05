package com.maruseron.zeron.domain;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class ZeronLibraryJar {
    private ZeronLibraryJar() {}

    public static void write(final Path classDirectory, final Path jarPath) throws IOException {
        if (!Files.isDirectory(classDirectory)) {
            throw new IOException("Zeron library output is not a class directory: " + classDirectory);
        }
        ZeronLibraryIndex.readFromDirectory(classDirectory);
        final var absoluteJarPath = jarPath.toAbsolutePath().normalize();
        final var absoluteClassDirectory = classDirectory.toAbsolutePath().normalize();
        if (absoluteJarPath.startsWith(absoluteClassDirectory)) {
            throw new IOException("JAR output must not be inside its class directory: " + jarPath);
        }
        final var parent = absoluteJarPath.getParent();
        if (parent == null) throw new IOException("JAR output must have a parent directory: " + jarPath);
        Files.createDirectories(parent);
        final var temporaryJar = Files.createTempFile(parent, "zeron-library-", ".tmp");
        try {
            try (final var output = new JarOutputStream(Files.newOutputStream(temporaryJar));
                 final var paths = Files.walk(classDirectory)) {
                for (final var file : paths.filter(path -> Files.isRegularFile(path,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .sorted(Comparator.comparing(path -> classDirectory.relativize(path).toString()))
                        .toList()) {
                    final var entryName = classDirectory.relativize(file).toString().replace('\\', '/');
                    final var entry = new JarEntry(entryName);
                    entry.setTime(0L);
                    output.putNextEntry(entry);
                    Files.copy(file, output);
                    output.closeEntry();
                }
            }
            try {
                Files.move(temporaryJar, absoluteJarPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException _) {
                Files.move(temporaryJar, absoluteJarPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryJar);
        }
    }
}
