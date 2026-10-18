package com.maruseron.zeron;

import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.DiagnosticCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class CliParser {
    private CliParser() {}

    static ParseResult parse(final String... args) {
        try {
            return parseInvocation(args);
        } catch (final java.nio.file.InvalidPathException exception) {
            return invalid("Invalid path '" + exception.getInput() + "'.");
        }
    }

    private static ParseResult parseInvocation(final String... args) {
        if (args.length > 0) {
            switch (args[0]) {
                case "init" -> {
                    if (args.length != 1) return invalid("Usage: zeron init");
                    return parsed(new Initialize());
                }
                case "build", "run" -> {
                    return parseProjectCommand(args);
                }
                case "lsp" -> {
                    return parseLspCommand(args);
                }
                case "explain" -> {
                    if (args.length != 2) return invalid("Usage: zeron explain <ZR0000>");
                    if (!args[1].matches("ZR[0-9]{4}")) {
                        return invalid("Diagnostic codes must use the form ZR0000.");
                    }
                    final var code = new DiagnosticCode(args[1]);
                    if (DiagnosticCatalog.find(code).isEmpty()) {
                        return invalid("Unknown Zeron diagnostic code: " + code);
                    }
                    return parsed(new Explain(args[1]));
                }
                case "help" -> {
                    if (args.length > 2) return invalid("Usage: zeron help [command|flag]");
                    final var topic = args.length == 2 ? Optional.of(args[1]) : Optional.<String>empty();
                    if (topic.isPresent() && !CliHelp.hasTopic(topic.get())) {
                        return invalid("Unknown help topic: " + topic.get());
                    }
                    return parsed(new Help(topic));
                }
                case "dump" -> {
                    if (args.length != 2) {
                        return invalid("Usage: zeron dump <api-index|class-directory|library.jar>");
                    }
                    final var source = Paths.get(args[1]);
                    final var sourceError = dumpSourceError(source);
                    if (sourceError.isPresent()) return invalid(sourceError.get());
                    return parsed(new Dump(source));
                }
                default -> {
                }
            }
        }
        return parseStandalone(args);
    }

    private static ParseResult parseProjectCommand(final String[] args) {
        final var launch = args[0].equals("run");
        var manifest = Paths.get("zeron.toml");
        var debug = false;
        for (var index = 1; index < args.length; index++) {
            final var argument = args[index];
            if (argument.equals("--debug") || argument.equals("-d")) {
                debug = true;
            } else if (argument.equals("--manifest")) {
                if (++index >= args.length) {
                    return invalid(args[0] + " --manifest requires a TOML file.");
                }
                manifest = Paths.get(args[index]);
            } else {
                return invalid("Unknown " + args[0] + " option: " + argument);
            }
        }
        final var manifestError = readableFileError(manifest, "Project manifest");
        if (manifestError.isPresent()) return invalid(manifestError.get());
        if (!manifest.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".toml")) {
            return invalid("Project manifest must have a .toml extension: " + manifest);
        }
        final ProjectManifest project;
        try {
            project = ProjectManifest.load(manifest);
        } catch (final IOException exception) {
            return invalid("Unable to read project manifest " + manifest + ": " + exception.getMessage());
        } catch (final IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
        final var projectPathError = projectPathError(project);
        if (projectPathError.isPresent()) return invalid(projectPathError.get());
        return parsed(new ProjectCommand(launch, manifest, debug, project));
    }

    private static ParseResult parseLspCommand(final String[] args) {
        final var projectArguments = new ArrayList<String>();
        projectArguments.add("build");
        var stdioSpecified = false;
        for (var index = 1; index < args.length; index++) {
            if (args[index].equals("--stdio")) {
                if (stdioSpecified) return invalid("The lsp --stdio option may only be specified once.");
                stdioSpecified = true;
            } else {
                projectArguments.add(args[index]);
            }
        }
        final var projectArgs = projectArguments.toArray(String[]::new);
        final var result = parseProjectCommand(projectArgs);
        if (result instanceof InvalidArguments invalidArguments) return invalidArguments;
        final var project = (ProjectCommand) ((Parsed) result).invocation();
        return parsed(new LspCommand(project.manifest(), project.project()));
    }

    private static Optional<String> projectPathError(final ProjectManifest project) {
        for (final var sourceRoot : project.sourceRoots()) {
            final var directoryError = readableDirectoryError(sourceRoot, "Project source root");
            if (directoryError.isPresent()) return directoryError;
        }
        final var entryError = readableFileError(project.entry(), "Project entry source");
        if (entryError.isPresent()) return entryError;
        if (!project.entry().getFileName().toString().endsWith(".zn")) {
            return Optional.of("Project entry source must have a .zn extension: " + project.entry());
        }
        if (project.sourceRoots().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .noneMatch(project.entry().toAbsolutePath().normalize()::startsWith)) {
            return Optional.of("Project entry source must be under a configured source root: " + project.entry());
        }
        final var outputError = directoryOutputError(project.outputDirectory(), "Project output");
        if (outputError.isPresent()) return outputError;
        for (final var library : project.libraryRoots()) {
            final var libraryError = libraryRootError(library);
            if (libraryError.isPresent()) return libraryError;
        }
        for (final var root : project.javaClassPathRoots()) {
            final var classPathError = javaClassPathRootError(root);
            if (classPathError.isPresent()) return classPathError;
        }
        return Optional.empty();
    }

    private static ParseResult parseStandalone(final String[] args) {
        final var sourceFiles = new ArrayList<String>();
        final var sourceRoots = new ArrayList<Path>();
        final var libraryRoots = new ArrayList<Path>();
        final var javaClassPathRoots = new ArrayList<Path>();
        Path entry = null;
        Path standardLibraryOutput = null;
        Path jarOutput = null;
        var bundleStandardLibrarySources = true;
        var debug = false;

        for (var index = 0; index < args.length; index++) {
            final var argument = args[index];
            if (argument.equals("--debug") || argument.equals("-d")) {
                debug = true;
            } else if (argument.equals("--root")) {
                if (++index >= args.length) return invalid("--root requires a directory.");
                sourceRoots.add(Paths.get(args[index]));
            } else if (argument.equals("--entry")) {
                if (++index >= args.length) return invalid("--entry requires a source file.");
                entry = Paths.get(args[index]);
            } else if (argument.equals("--library")) {
                if (++index >= args.length) return invalid("--library requires a class directory or JAR.");
                libraryRoots.add(Paths.get(args[index]));
            } else if (argument.equals("--java-classpath")) {
                if (++index >= args.length) return invalid("--java-classpath requires a class directory or JAR.");
                javaClassPathRoots.add(Paths.get(args[index]));
            } else if (argument.equals("--build-stdlib")) {
                if (++index >= args.length) return invalid("--build-stdlib requires an output directory.");
                standardLibraryOutput = Paths.get(args[index]);
            } else if (argument.equals("--jar-output")) {
                if (++index >= args.length) return invalid("--jar-output requires a JAR file.");
                jarOutput = Paths.get(args[index]);
            } else if (argument.equals("--run-class")) {
                return invalid("--run-class was replaced by 'zeron run [--manifest <path>]'.");
            } else if (argument.equals("--stdlib-jar")) {
                return invalid("--stdlib-jar is no longer supported; configure dependencies in zeron.toml.");
            } else if (argument.equals("--stdlib")) {
                if (++index >= args.length) return invalid("--stdlib requires 'source' or 'compiled'.");
                final var mode = args[index];
                if (mode.equals("source")) bundleStandardLibrarySources = true;
                else if (mode.equals("compiled")) bundleStandardLibrarySources = false;
                else return invalid("--stdlib must be 'source' or 'compiled'.");
            } else if (argument.startsWith("-")) {
                return invalid("Unknown option: " + argument);
            } else {
                sourceFiles.add(argument);
            }
        }

        if (standardLibraryOutput != null) {
            if (entry != null || !sourceRoots.isEmpty() || !sourceFiles.isEmpty()
                    || !libraryRoots.isEmpty() || !javaClassPathRoots.isEmpty()) {
                return invalid("--build-stdlib cannot be combined with source or library inputs.");
            }
            final var outputError = directoryOutputError(standardLibraryOutput, "Standard-library output");
            if (outputError.isPresent()) return invalid(outputError.get());
            if (jarOutput != null) {
                final var jarError = fileOutputError(jarOutput, "JAR output");
                if (jarError.isPresent()) return invalid(jarError.get());
                if (jarOutput.toAbsolutePath().normalize()
                        .startsWith(standardLibraryOutput.toAbsolutePath().normalize())) {
                    return invalid("JAR output must not be inside the standard-library output directory.");
                }
            }
            return parsed(new BuildStandardLibrary(standardLibraryOutput, jarOutput, debug));
        }

        if (entry == null && sourceRoots.isEmpty() && sourceFiles.isEmpty()) {
            if (jarOutput != null) return invalid("--jar-output requires compilation input.");
            if (!libraryRoots.isEmpty() || !javaClassPathRoots.isEmpty() || !bundleStandardLibrarySources) {
                return invalid("Library options require source files or a project entry.");
            }
            return parsed(new Repl(debug));
        }

        if (entry != null || !sourceRoots.isEmpty()) {
            if (entry == null || sourceRoots.isEmpty()) {
                return invalid("Project compilation requires both --root and --entry.");
            }
            if (!sourceFiles.isEmpty()) {
                return invalid("Project compilation cannot be combined with source file arguments.");
            }
        }
        for (final var sourceFile : sourceFiles) {
            final var sourcePath = Paths.get(sourceFile);
            final var fileError = readableFileError(sourcePath, "Source file");
            if (fileError.isPresent()) return invalid(fileError.get());
            if (!sourcePath.getFileName().toString().endsWith(".zn")) {
                return invalid("Source file must have a .zn extension: " + sourcePath);
            }
        }
        for (final var sourceRoot : sourceRoots) {
            final var directoryError = readableDirectoryError(sourceRoot, "Source root");
            if (directoryError.isPresent()) return invalid(directoryError.get());
        }
        if (entry != null) {
            final var absoluteRoots = sourceRoots.stream()
                    .map(path -> path.toAbsolutePath().normalize()).toList();
            final var absoluteEntry = entry.isAbsolute()
                    ? entry.normalize()
                    : absoluteRoots.getFirst().resolve(entry).normalize();
            final var fileError = readableFileError(absoluteEntry, "Entry source");
            if (fileError.isPresent()) return invalid(fileError.get());
            if (!absoluteEntry.getFileName().toString().endsWith(".zn")) {
                return invalid("Entry source must have a .zn extension: " + entry);
            }
            if (absoluteRoots.stream().noneMatch(absoluteEntry::startsWith)) {
                return invalid("Entry source must be under a configured source root: " + entry);
            }
        }
        for (final var library : libraryRoots) {
            final var libraryError = libraryRootError(library);
            if (libraryError.isPresent()) return invalid(libraryError.get());
        }
        for (final var root : javaClassPathRoots) {
            final var classPathError = javaClassPathRootError(root);
            if (classPathError.isPresent()) return invalid(classPathError.get());
        }
        if (jarOutput != null) {
            final var outputError = fileOutputError(jarOutput, "JAR output");
            if (outputError.isPresent()) return invalid(outputError.get());
            final var collision = jarOutputInputConflict(jarOutput, sourceFiles, sourceRoots,
                    entry, libraryRoots);
            if (collision.isPresent()) return invalid(collision.get());
        }
        return parsed(new Compile(sourceFiles, sourceRoots, entry, libraryRoots, javaClassPathRoots,
                bundleStandardLibrarySources, jarOutput, debug));
    }

    private static Optional<String> readableFileError(final Path path, final String description) {
        if (!Files.isRegularFile(path)) {
            return Optional.of(description + " must be an existing file: " + path);
        }
        if (!Files.isReadable(path)) {
            return Optional.of(description + " is not readable: " + path);
        }
        return Optional.empty();
    }

    private static Optional<String> readableDirectoryError(final Path path, final String description) {
        if (!Files.isDirectory(path)) {
            return Optional.of(description + " must be an existing directory: " + path);
        }
        if (!Files.isReadable(path)) {
            return Optional.of(description + " is not readable: " + path);
        }
        return Optional.empty();
    }

    private static Optional<String> libraryRootError(final Path path) {
        if (Files.isDirectory(path)) {
            final var directoryError = readableDirectoryError(path, "Zeron library root");
            if (directoryError.isPresent()) return directoryError;
            final var index = path.resolve(ZeronVersions.API_INDEX_RELATIVE_PATH);
            return readableFileError(index, "API index in Zeron library root");
        }
        if (Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return readableFileError(path, "Zeron library JAR");
        }
        return Optional.of("Zeron library root must be an existing class directory or .jar file: " + path);
    }

    private static Optional<String> javaClassPathRootError(final Path path) {
        if (Files.isDirectory(path)) return readableDirectoryError(path, "Java classpath root");
        if (Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return readableFileError(path, "Java classpath JAR");
        }
        return Optional.of("Java classpath root must be an existing class directory or .jar file: " + path);
    }

    private static Optional<String> dumpSourceError(final Path path) {
        if (Files.isDirectory(path)) {
            final var index = path.resolve(ZeronVersions.API_INDEX_RELATIVE_PATH);
            return readableFileError(index, "API index in dump source");
        }
        if (!Files.isRegularFile(path)) {
            return Optional.of("Dump source must be an existing API index file, class directory, or library JAR: "
                    + path);
        }
        final var fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".bin") && !fileName.endsWith(".jar")) {
            return Optional.of("Dump source must be an existing API index file, class directory, or library JAR: "
                    + path);
        }
        return readableFileError(path, "Dump source");
    }

    private static Optional<String> directoryOutputError(final Path path, final String description) {
        if (Files.exists(path)) {
            if (!Files.isDirectory(path)) {
                return Optional.of(description + " must be a directory: " + path);
            }
            if (!Files.isWritable(path)) return Optional.of(description + " is not writable: " + path);
            return Optional.empty();
        }
        return writableAncestorError(path, description);
    }

    private static Optional<String> fileOutputError(final Path path, final String description) {
        final var fileName = path.getFileName();
        if (fileName == null || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return Optional.of(description + " must have a .jar extension: " + path);
        }
        if (Files.exists(path)) {
            if (!Files.isRegularFile(path)) {
                return Optional.of(description + " must be a file path: " + path);
            }
            if (!Files.isWritable(path)) {
                return Optional.of(description + " is not writable: " + path);
            }
        }
        return writableAncestorError(path, description);
    }

    private static Optional<String> writableAncestorError(final Path path, final String description) {
        var ancestor = path.toAbsolutePath().normalize().getParent();
        if (ancestor == null) return Optional.of(description + " must have a parent directory: " + path);
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor == null || !Files.isDirectory(ancestor)) {
            return Optional.of(description + " parent must resolve to a directory: " + path);
        }
        if (!Files.isWritable(ancestor)) {
            return Optional.of(description + " parent directory is not writable: " + ancestor);
        }
        return Optional.empty();
    }

    private static Optional<String> jarOutputInputConflict(final Path jarOutput,
                                                           final List<String> sourceFiles,
                                                           final List<Path> sourceRoots,
                                                           final Path entry,
                                                           final List<Path> libraries) {
        final var output = jarOutput.toAbsolutePath().normalize();
        for (final var source : sourceFiles) {
            if (output.equals(Paths.get(source).toAbsolutePath().normalize())) {
                return Optional.of("JAR output must not replace a source file.");
            }
        }
        if (entry != null) {
            final var entryPath = entry.isAbsolute()
                    ? entry.normalize()
                    : sourceRoots.getFirst().toAbsolutePath().normalize().resolve(entry).normalize();
            if (output.equals(entryPath)) {
                return Optional.of("JAR output must not replace the project entry.");
            }
        }
        for (final var root : sourceRoots) {
            if (output.equals(root.toAbsolutePath().normalize())) {
                return Optional.of("JAR output must not replace a project source root.");
            }
        }
        for (final var library : libraries) {
            if (output.equals(library.toAbsolutePath().normalize())) {
                return Optional.of("JAR output must not replace a library input.");
            }
        }
        return Optional.empty();
    }

    private static Parsed parsed(final Invocation invocation) {
        return new Parsed(invocation);
    }

    private static InvalidArguments invalid(final String message) {
        return new InvalidArguments(message);
    }

    sealed interface ParseResult permits Parsed, InvalidArguments {}

    record Parsed(Invocation invocation) implements ParseResult {}

    record InvalidArguments(String message) implements ParseResult {}

    sealed interface Invocation permits Initialize, ProjectCommand, LspCommand, Explain, Help, Dump,
            BuildStandardLibrary, Repl, Compile {
        boolean debug();
    }

    record Initialize() implements Invocation {
        @Override
        public boolean debug() { return false; }
    }

    record ProjectCommand(boolean launch, Path manifest, boolean debug, ProjectManifest project)
            implements Invocation {}

    record LspCommand(Path manifest, ProjectManifest project) implements Invocation {
        @Override
        public boolean debug() { return false; }
    }

    record Explain(String code) implements Invocation {
        @Override
        public boolean debug() { return false; }
    }

    record Help(Optional<String> topic) implements Invocation {
        @Override
        public boolean debug() { return false; }
    }

    record Dump(Path source) implements Invocation {
        @Override
        public boolean debug() { return false; }
    }

    record BuildStandardLibrary(Path outputDirectory, Path jarOutput, boolean debug) implements Invocation {}

    record Repl(boolean debug) implements Invocation {}

    record Compile(List<String> sourceFiles, List<Path> sourceRoots, Path entry,
                   List<Path> libraries, List<Path> javaClassPathRoots,
                   boolean bundleStandardLibrarySources, Path jarOutput, boolean debug)
            implements Invocation {
        Compile {
            sourceFiles = List.copyOf(sourceFiles);
            sourceRoots = List.copyOf(sourceRoots);
            libraries = List.copyOf(libraries);
            javaClassPathRoots = List.copyOf(javaClassPathRoots);
        }
    }
}
