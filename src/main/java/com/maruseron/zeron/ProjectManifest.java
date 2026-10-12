package com.maruseron.zeron;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

record ProjectManifest(List<Path> sourceRoots,
                       Path entry,
                       Path outputDirectory,
                       boolean bundleStandardLibrarySources,
                       List<Path> libraryRoots,
                       List<Path> javaClassPathRoots) {
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("project", "platform", "dependencies");
    private static final Set<String> PROJECT_KEYS = Set.of("entry", "source-roots", "output");
    private static final Set<String> PLATFORM_KEYS = Set.of("stdlib");
    private static final Set<String> DEPENDENCY_KEYS = Set.of("zeron-libraries", "java-classpath");

    static ProjectManifest load(final Path path) throws IOException {
        final var absolutePath = path.toAbsolutePath().normalize();
        final TomlParseResult document = Toml.parse(absolutePath);
        if (document.hasErrors()) {
            throw new IllegalArgumentException("Invalid project manifest " + absolutePath + ":\n"
                    + String.join("\n", document.errors().stream().map(Object::toString).toList()));
        }
        requireKeys(document, TOP_LEVEL_KEYS, "manifest");
        final var project = requireTable(document, "project", "manifest");
        requireKeys(project, PROJECT_KEYS, "[project]");

        final var baseDirectory = absolutePath.getParent();
        if (baseDirectory == null) {
            throw new IllegalArgumentException("Project manifest must have a parent directory: " + absolutePath);
        }
        final var entry = resolvePath(baseDirectory, requireString(project, "entry", "[project]"));
        final var sourceRoots = resolvePaths(baseDirectory,
                requireStringArray(project, "source-roots", "[project]"));
        if (sourceRoots.isEmpty()) {
            throw new IllegalArgumentException("[project].source-roots must contain at least one directory.");
        }
        final var outputDirectory = resolvePath(baseDirectory,
                optionalString(project, "output", "dist", "[project]"));

        final var platform = optionalTable(document, "platform");
        if (platform != null) requireKeys(platform, PLATFORM_KEYS, "[platform]");
        final var stdlib = platform == null ? "source" : optionalString(platform, "stdlib", "source", "[platform]");
        final boolean bundleStandardLibrarySources;
        if (stdlib.equals("source")) bundleStandardLibrarySources = true;
        else if (stdlib.equals("compiled")) bundleStandardLibrarySources = false;
        else throw new IllegalArgumentException("[platform].stdlib must be 'source' or 'compiled'.");

        final var dependencies = optionalTable(document, "dependencies");
        if (dependencies != null) requireKeys(dependencies, DEPENDENCY_KEYS, "[dependencies]");
        final var libraryRoots = resolvePaths(baseDirectory, dependencies == null
                ? List.of() : optionalStringArray(dependencies, "zeron-libraries", "[dependencies]"));
        final var javaClassPathRoots = resolvePaths(baseDirectory, dependencies == null
                ? List.of() : optionalStringArray(dependencies, "java-classpath", "[dependencies]"));

        return new ProjectManifest(sourceRoots, entry, outputDirectory,
                bundleStandardLibrarySources, libraryRoots, javaClassPathRoots);
    }

    private static Path resolvePath(final Path baseDirectory, final String value) {
        final var path = Path.of(value);
        return (path.isAbsolute() ? path : baseDirectory.resolve(path)).normalize();
    }

    private static List<Path> resolvePaths(final Path baseDirectory, final List<String> values) {
        return values.stream().map(value -> resolvePath(baseDirectory, value)).toList();
    }

    private static TomlTable requireTable(final TomlTable parent, final String key, final String context) {
        final var value = parent.get(key);
        if (!(value instanceof TomlTable table)) {
            throw new IllegalArgumentException(context + " must contain a [" + key + "] table.");
        }
        return table;
    }

    private static TomlTable optionalTable(final TomlTable parent, final String key) {
        if (!parent.contains(key)) return null;
        final var value = parent.get(key);
        if (!(value instanceof TomlTable table)) {
            throw new IllegalArgumentException("[" + key + "] must be a TOML table.");
        }
        return table;
    }

    private static void requireKeys(final TomlTable table, final Set<String> allowed, final String context) {
        final var unknownKeys = table.keySet().stream()
                .filter(key -> !allowed.contains(key))
                .sorted()
                .toList();
        if (!unknownKeys.isEmpty()) {
            throw new IllegalArgumentException("Unknown key(s) in " + context + ": "
                    + String.join(", ", unknownKeys));
        }
    }

    private static String requireString(final TomlTable table, final String key, final String context) {
        final var rawValue = table.get(key);
        if (!(rawValue instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(context + "." + key + " must be a non-empty string.");
        }
        return value;
    }

    private static String optionalString(final TomlTable table,
                                         final String key,
                                         final String defaultValue,
                                         final String context) {
        if (!table.contains(key)) return defaultValue;
        return requireString(table, key, context);
    }

    private static List<String> requireStringArray(final TomlTable table,
                                                  final String key,
                                                  final String context) {
        if (!table.contains(key)) {
            throw new IllegalArgumentException(context + "." + key + " must be an array of strings.");
        }
        return stringArray(table, key, context);
    }

    private static List<String> optionalStringArray(final TomlTable table,
                                                    final String key,
                                                    final String context) {
        return table.contains(key) ? stringArray(table, key, context) : List.of();
    }

    private static List<String> stringArray(final TomlTable table, final String key, final String context) {
        final var rawValue = table.get(key);
        if (!(rawValue instanceof TomlArray array)) {
            throw new IllegalArgumentException(context + "." + key + " must be an array of strings.");
        }
        final var values = new ArrayList<String>();
        for (var index = 0; index < array.size(); index++) {
            final var rawItem = array.get(index);
            if (!(rawItem instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException(context + "." + key
                        + " must contain only non-empty strings.");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }
}
