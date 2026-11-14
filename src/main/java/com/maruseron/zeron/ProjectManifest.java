package com.maruseron.zeron;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

record ProjectManifest(List<Path> sourceRoots,
                       Path entry,
                       Path outputDirectory,
                       StandardLibraryMode standardLibraryMode,
                       List<Path> libraryRoots,
                       List<Path> javaClassPathRoots,
                       List<Path> javaSourceRoots,
                       boolean denyWarnings,
                       Optional<String> defaultTargetName,
                       Map<String, ProjectTarget> targets,
                       Optional<ProjectTarget> implicitTarget) {
    private static final Set<String> TOP_LEVEL_KEYS =
            Set.of("project", "platform", "dependencies", "build", "targets");
    private static final Set<String> PROJECT_KEYS =
            Set.of("entry", "source-roots", "output", "default-target");
    private static final Set<String> PLATFORM_KEYS = Set.of("stdlib");
    private static final Set<String> DEPENDENCY_KEYS =
            Set.of("zeron-libraries", "java-classpath", "java-sources");
    private static final Set<String> BUILD_KEYS = Set.of("deny-warnings");
    private static final Set<String> TARGET_KEYS = Set.of("entry", "output", "deny-warnings");
    private static final Pattern TARGET_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    record ProjectTarget(String name,
                         Path entry,
                         Path outputDirectory,
                         boolean denyWarnings,
                         boolean implicit) {
        ProjectTarget {
            if (name == null) name = "";
        }

        public String displayName() {
            return implicit ? "implicit project target" : name;
        }
    }

    ProjectManifest {
        sourceRoots = List.copyOf(sourceRoots);
        libraryRoots = List.copyOf(libraryRoots);
        javaClassPathRoots = List.copyOf(javaClassPathRoots);
        javaSourceRoots = List.copyOf(javaSourceRoots);
        targets = Map.copyOf(targets);
        implicitTarget = implicitTarget == null ? Optional.empty() : implicitTarget;
    }

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
        final var sourceRoots = resolvePaths(baseDirectory,
                requireStringArray(project, "source-roots", "[project]"));
        if (sourceRoots.isEmpty()) {
            throw new IllegalArgumentException("[project].source-roots must contain at least one directory.");
        }
        final var configuredEntry = optionalPath(baseDirectory, project, "entry", "[project]");
        final var outputDirectory = resolvePath(baseDirectory,
                optionalString(project, "output", "dist", "[project]"));
        final var defaultTargetName = optionalStringOptional(project, "default-target", "[project]");

        final var build = optionalTable(document, "build");
        if (build != null) requireKeys(build, BUILD_KEYS, "[build]");
        final var denyWarnings = build != null
                && optionalBoolean(build, "deny-warnings", false, "[build]");

        final var platform = optionalTable(document, "platform");
        if (platform != null) requireKeys(platform, PLATFORM_KEYS, "[platform]");
        final StandardLibraryMode standardLibraryMode;
        if (platform == null || !platform.contains("stdlib")) {
            standardLibraryMode = StandardLibraryMode.DEFAULT;
        } else {
            final var stdlib = requireString(platform, "stdlib", "[platform]");
            standardLibraryMode = switch (stdlib) {
                case "source" -> StandardLibraryMode.SOURCE;
                case "compiled" -> StandardLibraryMode.COMPILED;
                default -> throw new IllegalArgumentException(
                        "[platform].stdlib must be 'source' or 'compiled'.");
            };
        }

        final var dependencies = optionalTable(document, "dependencies");
        if (dependencies != null) requireKeys(dependencies, DEPENDENCY_KEYS, "[dependencies]");
        final var libraryRoots = resolvePaths(baseDirectory, dependencies == null
                ? List.of() : optionalStringArray(dependencies, "zeron-libraries", "[dependencies]"));
        final var javaClassPathRoots = resolvePaths(baseDirectory, dependencies == null
                ? List.of() : optionalStringArray(dependencies, "java-classpath", "[dependencies]"));
        final var javaSourceRoots = resolvePaths(baseDirectory, dependencies == null
                ? List.of() : optionalStringArray(dependencies, "java-sources", "[dependencies]"));

        final var targets = new LinkedHashMap<String, ProjectTarget>();
        final var targetTable = optionalTable(document, "targets");
        if (targetTable != null) {
            for (final var name : targetTable.keySet().stream().sorted().toList()) {
                if (!TARGET_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("Invalid target name '" + name
                            + "': target names must match [A-Za-z][A-Za-z0-9_-]*.");
                }
                final var rawTarget = targetTable.get(name);
                if (!(rawTarget instanceof TomlTable table)) {
                    throw new IllegalArgumentException("[targets." + name + "] must be a TOML table.");
                }
                requireKeys(table, TARGET_KEYS, "[targets." + name + "]");
                final var targetEntry = optionalPath(baseDirectory, table, "entry",
                        configuredEntry.orElse(null), "[targets." + name + "]");
                if (targetEntry == null) {
                    throw new IllegalArgumentException("[targets." + name
                            + "].entry is required when [project].entry is omitted.");
                }
                final var targetOutput = optionalPath(baseDirectory, table, "output",
                        outputDirectory.resolve(name), "[targets." + name + "]");
                final var targetDenyWarnings = optionalBoolean(table, "deny-warnings",
                        denyWarnings, "[targets." + name + "]");
                targets.put(name, new ProjectTarget(name, targetEntry, targetOutput,
                        targetDenyWarnings, false));
            }
        }
        if (defaultTargetName.isPresent() && !targets.containsKey(defaultTargetName.get())) {
            throw new IllegalArgumentException("[project].default-target must name a [targets.<name>] entry: "
                    + defaultTargetName.get());
        }
        final Optional<ProjectTarget> implicitTarget = configuredEntry
                .map(targetEntry -> new ProjectTarget("", targetEntry, outputDirectory, denyWarnings, true));
        if (implicitTarget.isEmpty() && defaultTargetName.isEmpty()) {
            throw new IllegalArgumentException(
                    "[project].entry is required when no default target is configured.");
        }

        final var effectiveTargets = new ArrayList<ProjectTarget>();
        implicitTarget.ifPresent(effectiveTargets::add);
        targets.values().stream().sorted(java.util.Comparator.comparing(ProjectTarget::name))
                .forEach(effectiveTargets::add);
        final var outputs = new LinkedHashMap<Path, String>();
        for (final var target : effectiveTargets) {
            final var key = target.outputDirectory().toAbsolutePath().normalize();
            final var previous = outputs.putIfAbsent(key, target.displayName());
            if (previous != null) {
                throw new IllegalArgumentException("Effective targets " + previous + " and "
                        + target.displayName() + " share output directory " + key + ".");
            }
        }
        for (final var target : effectiveTargets) {
            validateTargetEntry(sourceRoots, target);
        }

        final var selectedEntry = defaultTargetName
                .map(targets::get)
                .map(ProjectTarget::entry)
                .or(() -> implicitTarget.map(ProjectTarget::entry))
                .orElseThrow();
        return new ProjectManifest(sourceRoots, selectedEntry, outputDirectory,
                standardLibraryMode, libraryRoots, javaClassPathRoots, javaSourceRoots,
                denyWarnings, defaultTargetName, targets, implicitTarget);
    }

    ProjectTarget selectTarget(final Optional<String> requestedName) {
        if (requestedName.isPresent()) {
            final var requested = requestedName.get();
            final var target = targets.get(requested);
            if (target == null) {
                throw new IllegalArgumentException("Unknown target '" + requested
                        + "'. Available targets: " + availableTargetNames() + ".");
            }
            return target;
        }
        if (defaultTargetName.isPresent()) return targets.get(defaultTargetName.get());
        return implicitTarget.orElseThrow(() -> new IllegalArgumentException(
                "No implicit project target is configured and no default target is set."));
    }

    List<ProjectTarget> effectiveTargets() {
        final var effective = new ArrayList<ProjectTarget>();
        implicitTarget.ifPresent(effective::add);
        targets.values().stream().sorted(java.util.Comparator.comparing(ProjectTarget::name))
                .forEach(effective::add);
        return List.copyOf(effective);
    }

    private String availableTargetNames() {
        if (targets.isEmpty()) return "(none)";
        return String.join(", ", targets.keySet().stream().sorted().toList());
    }

    private static void validateTargetEntry(final List<Path> sourceRoots, final ProjectTarget target) {
        final var fileName = target.entry().getFileName();
        if (fileName == null || !fileName.toString().endsWith(".zn")) {
            throw new IllegalArgumentException("Target " + target.displayName()
                    + " entry source must have a .zn extension: " + target.entry());
        }
        final var absoluteEntry = target.entry().toAbsolutePath().normalize();
        if (sourceRoots.stream()
                .map(root -> root.toAbsolutePath().normalize())
                .noneMatch(absoluteEntry::startsWith)) {
            throw new IllegalArgumentException("Target " + target.displayName()
                    + " entry source must be under a configured source root: " + target.entry());
        }
    }

    private static Path resolvePath(final Path baseDirectory, final String value) {
        final var path = Path.of(value);
        return (path.isAbsolute() ? path : baseDirectory.resolve(path)).normalize();
    }

    private static List<Path> resolvePaths(final Path baseDirectory, final List<String> values) {
        return values.stream().map(value -> resolvePath(baseDirectory, value)).toList();
    }

    private static Optional<Path> optionalPath(final Path baseDirectory,
                                               final TomlTable table,
                                               final String key,
                                               final String context) {
        final var raw = table.get(key);
        if (raw == null) return Optional.empty();
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(context + "." + key + " must be a non-empty string.");
        }
        return Optional.of(resolvePath(baseDirectory, value));
    }

    private static Path optionalPath(final Path baseDirectory,
                                     final TomlTable table,
                                     final String key,
                                     final Path defaultValue,
                                     final String context) {
        return optionalPath(baseDirectory, table, key, context).orElse(defaultValue);
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

    private static Optional<String> optionalStringOptional(final TomlTable table,
                                                           final String key,
                                                           final String context) {
        return table.contains(key) ? Optional.of(requireString(table, key, context)) : Optional.empty();
    }

    private static boolean optionalBoolean(final TomlTable table,
                                           final String key,
                                           final boolean defaultValue,
                                           final String context) {
        if (!table.contains(key)) return defaultValue;
        final var raw = table.get(key);
        if (!(raw instanceof Boolean value)) {
            throw new IllegalArgumentException(context + "." + key + " must be a boolean.");
        }
        return value;
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
