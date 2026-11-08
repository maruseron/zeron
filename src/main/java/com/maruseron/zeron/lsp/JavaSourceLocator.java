package com.maruseron.zeron.lsp;

import org.eclipse.lsp4j.Location;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves Java binary names to source files in attached source roots or source archives. Lookup
 * is bounded to the attached roots and archives and the single candidate file for the requested
 * class; packages and classpath entries are never scanned. Source found inside an archive is
 * extracted once into a temporary cache so an editor can open it through a file URI.
 */
final class JavaSourceLocator {
    private JavaSourceLocator() {}

    static Location locate(final List<Path> sourceRoots, final List<Path> sourceArchives,
                           final String binaryName, final String memberName) {
        if (binaryName == null) return null;
        final var nestedSeparator = binaryName.indexOf('$');
        final var outerName = nestedSeparator < 0
                ? binaryName : binaryName.substring(0, nestedSeparator);
        final var separator = outerName.lastIndexOf('.');
        final var packagePath = separator < 0
                ? "" : outerName.substring(0, separator).replace('.', '/');
        final var outerSimpleName = separator < 0
                ? outerName : outerName.substring(separator + 1);
        final var relative = packagePath.isEmpty()
                ? outerSimpleName + ".java"
                : packagePath + "/" + outerSimpleName + ".java";
        final var declaredName = binaryName.substring(
                Math.max(binaryName.lastIndexOf('$'), binaryName.lastIndexOf('.')) + 1);
        final var searchedName = memberName == null || memberName.equals("new")
                ? declaredName : memberName;
        for (final var root : sourceRoots) {
            final var file = root.resolve(relative);
            if (!Files.isRegularFile(file)) continue;
            final var text = read(file);
            if (text == null) continue;
            final var location = location(file, text, memberName, searchedName);
            if (location != null) return location;
        }
        for (final var archive : sourceArchives) {
            final var location = locateInArchive(archive, relative, memberName, searchedName);
            if (location != null) return location;
        }
        return null;
    }

    private static Location locateInArchive(final Path archive, final String relative,
                                            final String memberName, final String searchedName) {
        try (final var zip = new ZipFile(archive.toFile())) {
            final var entry = sourceEntry(zip, relative);
            if (entry == null) return null;
            final String text;
            try (final var input = zip.getInputStream(entry)) {
                text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            final var file = extractedSource(archive, relative, text);
            return file == null ? null : location(file, text, memberName, searchedName);
        } catch (final IOException _) {
            return null;
        }
    }

    private static ZipEntry sourceEntry(final ZipFile zip, final String relative) {
        final var exact = zip.getEntry(relative);
        if (exact != null) return exact;
        final var suffix = "/" + relative;
        final var entries = zip.entries();
        while (entries.hasMoreElements()) {
            final var entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().endsWith(suffix)) return entry;
        }
        return null;
    }

    private static Path extractedSource(final Path archive, final String relative,
                                        final String text) {
        final var key = Integer.toHexString(archive.toAbsolutePath().normalize().toString()
                .hashCode()) + "-" + archive.getFileName().toString()
                .replaceAll("[^A-Za-z0-9._-]", "_");
        final var file = Path.of(System.getProperty("java.io.tmpdir"), "zeron-java-sources", key)
                .resolve(relative);
        try {
            final var bytes = text.getBytes(StandardCharsets.UTF_8);
            if (!Files.isRegularFile(file) || Files.size(file) != bytes.length) {
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
            }
            return file;
        } catch (final IOException _) {
            return null;
        }
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException _) {
            return null;
        }
    }

    private static Location location(final Path file, final String text,
                                     final String memberName, final String searchedName) {
        final var offset = memberName == null
                ? declarationOffset(text, searchedName)
                : memberOffset(text, searchedName);
        if (offset < 0) return null;
        return new Location(file.toUri().toString(),
                DocumentCoordinates.rangeAt(text, offset, offset + searchedName.length()));
    }

    private static int declarationOffset(final String text, final String simpleName) {
        final var matcher = Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+"
                + Pattern.quote(simpleName) + "\\b").matcher(text);
        if (!matcher.find()) return -1;
        return matcher.start() + matcher.group().length() - simpleName.length();
    }

    private static int memberOffset(final String text, final String memberName) {
        final var matcher = Pattern.compile("\\b" + Pattern.quote(memberName)
                + "\\s*(?:\\(|;|=|,|\\))").matcher(text);
        return matcher.find() ? matcher.start() : -1;
    }
}
