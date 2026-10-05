package com.maruseron.zeron;

import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.domain.ZeronLibraryJar;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static java.lang.IO.println;

public class Zeron {
    private static final java.util.Set<String> STANDARD_LIBRARY_TYPES = java.util.Set.of(
            "zeron.collections.Iterator", "zeron.collections.Iterable",
            "zeron.ranges.IntIterator", "zeron.ranges.IntRange");

    static boolean hadError = false;
    static boolean hadResolutionError = false;
    private static boolean debugEnabled;

    static void main(final String... args) throws IOException {
        final var exitCode = runCli(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    public static int runCli(final String... args) throws IOException {
        hadError = false;
        hadResolutionError = false;
        debugEnabled = false;
        try {
            return runCliInvocation(args);
        } catch (final ResolutionError _) {
            return exitCode();
        }
    }

    private static int runCliInvocation(final String... args) throws IOException {
        final var scriptPaths = new ArrayList<String>();
        final var sourceRoots = new ArrayList<Path>();
        final var libraryRoots = new ArrayList<Path>();
        final var javaClassPathRoots = new ArrayList<Path>();
        Path entry = null;
        Path standardLibraryOutput = null;
        Path jarOutput = null;
        Path runClassFile = null;
        Path standardLibraryJar = Paths.get("target", "zeron-stdlib.jar");
        var customStandardLibraryJar = false;
        var bundleStandardLibrarySources = true;
        for (var index = 0; index < args.length; index++) {
            final var argument = args[index];
            if (argument.equals("--debug") || argument.equals("-d")) {
                debugEnabled = true;
            } else if (argument.equals("--root")) {
                if (++index >= args.length) throw new IllegalArgumentException("--root requires a directory.");
                sourceRoots.add(Paths.get(args[index]));
            } else if (argument.equals("--entry")) {
                if (++index >= args.length) throw new IllegalArgumentException("--entry requires a source file.");
                entry = Paths.get(args[index]);
            } else if (argument.equals("--library")) {
                if (++index >= args.length) throw new IllegalArgumentException("--library requires a class directory or JAR.");
                libraryRoots.add(Paths.get(args[index]));
            } else if (argument.equals("--java-classpath")) {
                if (++index >= args.length) throw new IllegalArgumentException("--java-classpath requires a class directory.");
                javaClassPathRoots.add(Paths.get(args[index]));
            } else if (argument.equals("--build-stdlib")) {
                if (++index >= args.length) throw new IllegalArgumentException("--build-stdlib requires an output directory.");
                standardLibraryOutput = Paths.get(args[index]);
            } else if (argument.equals("--jar-output")) {
                if (++index >= args.length) throw new IllegalArgumentException("--jar-output requires a JAR file.");
                jarOutput = Paths.get(args[index]);
            } else if (argument.equals("--run-class")) {
                if (++index >= args.length) throw new IllegalArgumentException("--run-class requires a .class file.");
                runClassFile = Paths.get(args[index]);
            } else if (argument.equals("--stdlib-jar")) {
                if (++index >= args.length) throw new IllegalArgumentException("--stdlib-jar requires a JAR file.");
                standardLibraryJar = Paths.get(args[index]);
                customStandardLibraryJar = true;
            } else if (argument.equals("--stdlib")) {
                if (++index >= args.length) throw new IllegalArgumentException("--stdlib requires 'source' or 'compiled'.");
                if (args[index].equals("source")) bundleStandardLibrarySources = true;
                else if (args[index].equals("compiled")) bundleStandardLibrarySources = false;
                else throw new IllegalArgumentException("--stdlib must be 'source' or 'compiled'.");
            } else {
                scriptPaths.add(argument);
            }
        }
        if (runClassFile != null) {
            if (entry != null || !sourceRoots.isEmpty() || !scriptPaths.isEmpty()
                    || standardLibraryOutput != null || jarOutput != null || !javaClassPathRoots.isEmpty()
                    || !bundleStandardLibrarySources) {
                throw new IllegalArgumentException("--run-class cannot be combined with compilation options.");
            }
            return runGeneratedClass(runClassFile, standardLibraryJar, libraryRoots);
        }
        if (customStandardLibraryJar) {
            throw new IllegalArgumentException("--stdlib-jar can only be used with --run-class.");
        }
        if (standardLibraryOutput != null) {
            if (entry != null || !sourceRoots.isEmpty() || !scriptPaths.isEmpty()
                    || !libraryRoots.isEmpty() || !javaClassPathRoots.isEmpty()) {
                throw new IllegalArgumentException("--build-stdlib cannot be combined with source or library inputs.");
            }
            buildStandardLibrary(standardLibraryOutput);
            if (jarOutput != null && exitCode() == 0) {
                ZeronLibraryJar.write(standardLibraryOutput, jarOutput);
            }
            return exitCode();
        }
        if (entry == null && sourceRoots.isEmpty() && scriptPaths.isEmpty()) {
            if (jarOutput != null) throw new IllegalArgumentException("--jar-output requires compilation input.");
            if (!libraryRoots.isEmpty() || !javaClassPathRoots.isEmpty() || !bundleStandardLibrarySources) {
                throw new IllegalArgumentException("Library options require source files or a project entry.");
            }
            runPrompt();
            return exitCode();
        }

        if (entry != null || !sourceRoots.isEmpty()) {
            if (entry == null || sourceRoots.isEmpty()) {
                throw new IllegalArgumentException("Project compilation requires both --root and --entry.");
            }
        }
        if (jarOutput != null) {
            validateJarOutputDoesNotReplaceInputs(jarOutput, scriptPaths, sourceRoots, entry, libraryRoots);
        }

        final var outputDirectory = jarOutput == null
                ? Paths.get("dist")
                : Files.createTempDirectory("zeron-jar-stage-");
        try {
            if (entry != null || !sourceRoots.isEmpty()) {
                runProject(sourceRoots, entry, libraryRoots, javaClassPathRoots,
                        bundleStandardLibrarySources, outputDirectory);
            } else {
                runFiles(scriptPaths, libraryRoots, javaClassPathRoots,
                        bundleStandardLibrarySources, outputDirectory);
            }
            if (jarOutput != null && exitCode() == 0) {
                ZeronLibraryJar.write(outputDirectory, jarOutput);
            }
            return exitCode();
        } finally {
            if (jarOutput != null) deleteTree(outputDirectory);
        }
    }

    private static int exitCode() {
        if (hadError) return 65;
        if (hadResolutionError) return 71;
        return 0;
    }

    private static int runGeneratedClass(final Path classFile,
                                         final Path standardLibraryJar,
                                         final List<Path> libraries) throws IOException {
        final var outputRoot = Paths.get("dist").toAbsolutePath().normalize();
        final var absoluteClassFile = classFile.toAbsolutePath().normalize();
        if (!absoluteClassFile.startsWith(outputRoot)
                || !absoluteClassFile.getFileName().toString().endsWith(".class")
                || !Files.isRegularFile(absoluteClassFile)) {
            throw new IllegalArgumentException("--run-class requires an existing .class file under dist/.");
        }
        if (!Files.exists(standardLibraryJar)) {
            buildStandardLibraryJar(standardLibraryJar);
        } else {
            ZeronLibraryIndex.readFromJar(standardLibraryJar);
        }
        loadLibraries(libraries);
        final var relativeClassFile = outputRoot.relativize(absoluteClassFile).toString()
                .replace(File.separatorChar, '.');
        final var className = relativeClassFile.substring(0, relativeClassFile.length() - ".class".length());
        final Path runtimeLocation;
        try {
            runtimeLocation = Path.of(Zeron.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
        } catch (final java.net.URISyntaxException exception) {
            throw new IOException("Could not resolve the Zeron runtime classpath location.", exception);
        }
        final var classPathEntries = new ArrayList<String>();
        classPathEntries.add(outputRoot.toString());
        classPathEntries.add(standardLibraryJar.toAbsolutePath().normalize().toString());
        classPathEntries.add(runtimeLocation.toString());
        for (final var library : libraries) {
            classPathEntries.add(library.toAbsolutePath().normalize().toString());
        }
        final var javaExecutable = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        final var command = new ArrayList<String>();
        command.add(javaExecutable.toString());
        command.add("--enable-preview");
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classPathEntries));
        command.add(className);
        try {
            return new ProcessBuilder(command).inheritIO().start().waitFor();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running generated class " + className + ".", exception);
        }
    }

    private static void buildStandardLibraryJar(final Path jarPath) throws IOException {
        final var absoluteJarPath = jarPath.toAbsolutePath().normalize();
        final var parent = absoluteJarPath.getParent();
        if (parent == null) throw new IOException("Standard-library JAR path must have a parent: " + jarPath);
        Files.createDirectories(parent);
        final var stagingDirectory = Files.createTempDirectory(parent, "zeron-stdlib-stage-");
        try {
            buildStandardLibrary(stagingDirectory);
            if (exitCode() != 0) {
                throw new IOException("Could not build the standard library required to run generated classes.");
            }
            ZeronLibraryJar.write(stagingDirectory, absoluteJarPath);
        } finally {
            deleteTree(stagingDirectory);
        }
    }

    public static void debug(final String message) {
        if (debugEnabled) System.err.println(message);
    }

    private static void runFiles(final List<String> paths,
                                 final List<Path> libraryRoots,
                                 final List<Path> javaClassPathRoots,
                                 final boolean bundleStandardLibrarySources,
                                 final Path outputDirectory) throws IOException {
        final var libraries = loadLibraries(libraryRoots);
        validateJavaClassPathRoots(javaClassPathRoots);
        validateStandardLibraryMode(libraries, bundleStandardLibrarySources);
        final var units = new ArrayList<CompilationUnit>();
        for (final var path : paths) {
            final var sourcePath = Paths.get(path);
            final var bytes = Files.readAllBytes(sourcePath);
            units.add(Parser.of(Scanner.from(new String(bytes, Charset.defaultCharset())).scanTokens())
                    .parseCompilationUnit(sourcePath.toString()));
        }
        if (hadError) {
            return;
        }
        final var packageName = units.getFirst().packageName();
        final var programName = packageName.isEmpty()
                ? sourceClassName(Paths.get(paths.getFirst()))
                : packageName + "." + sourceClassName(Paths.get(paths.getFirst()));
        runUnits(units, programName, packageName, libraries,
            bundleStandardLibrarySources, javaClassPathRoots, outputDirectory);
    }

    private static void runProject(final List<Path> roots,
                                   final Path entry,
                                   final List<Path> libraryRoots,
                                   final List<Path> javaClassPathRoots,
                                   final boolean bundleStandardLibrarySources,
                                   final Path outputDirectory) throws IOException {
        final var libraries = loadLibraries(libraryRoots);
        validateJavaClassPathRoots(javaClassPathRoots);
        validateStandardLibraryMode(libraries, bundleStandardLibrarySources);
        final var absoluteRoots = roots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        final var absoluteEntry = entry.isAbsolute()
                ? entry.normalize()
                : absoluteRoots.getFirst().resolve(entry).normalize();
        if (!Files.isRegularFile(absoluteEntry)
                || absoluteRoots.stream().noneMatch(absoluteEntry::startsWith)) {
            throw new IllegalArgumentException("Entry source must exist under a configured --root: " + entry);
        }

        final var sourcePaths = new java.util.TreeSet<Path>();
        for (final var root : absoluteRoots) {
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("Source root is not a directory: " + root);
            try (final var paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".zn"))
                        .map(path -> path.toAbsolutePath().normalize())
                        .forEach(sourcePaths::add);
            }
        }
        if (!sourcePaths.contains(absoluteEntry)) {
            throw new IllegalArgumentException("Entry source was not discovered under configured roots: " + entry);
        }

        final var orderedSources = new ArrayList<Path>();
        orderedSources.add(absoluteEntry);
        sourcePaths.stream().filter(path -> !path.equals(absoluteEntry)).forEach(orderedSources::add);
        final var units = new ArrayList<CompilationUnit>();
        for (final var sourcePath : orderedSources) {
            final var sourceRootIndex = sourceRootIndex(absoluteRoots, sourcePath);
            final var relativePath = absoluteRoots.get(sourceRootIndex).relativize(sourcePath);
            final var relativeDirectory = relativePath.getParent() == null
                    ? ""
                    : relativePath.getParent().toString().replace('\\', '/').replace('/', '.');
            final var unit = Parser.of(Scanner.from(Files.readString(sourcePath)).scanTokens())
                    .parseCompilationUnit("root" + sourceRootIndex + "/"
                            + relativePath.toString().replace('\\', '/'));
            if (hadError) return;
            if (!unit.packageName().equals(relativeDirectory)) {
                error(1, "Package '" + (unit.packageName().isEmpty() ? "<default>" : unit.packageName())
                        + "' in " + sourcePath + " must match its directory under source root '"
                        + absoluteRoots.get(sourceRootIndex) + "' (expected '"
                        + (relativeDirectory.isEmpty() ? "<default>" : relativeDirectory) + "').");
                return;
            }
            units.add(unit);
        }
        if (hadError) {
            return;
        }
        final var entryUnit = units.getFirst();
        final var programName = entryUnit.packageName().isEmpty()
                ? sourceClassName(absoluteEntry)
                : entryUnit.packageName() + "." + sourceClassName(absoluteEntry);
        runUnits(units, programName, entryUnit.packageName(), libraries,
            bundleStandardLibrarySources, javaClassPathRoots, outputDirectory);
    }

    private static int sourceRootIndex(final List<Path> roots, final Path sourcePath) {
        var selectedIndex = -1;
        var selectedDepth = -1;
        for (var index = 0; index < roots.size(); index++) {
            final var root = roots.get(index);
            if (!sourcePath.startsWith(root)) continue;
            final var depth = root.getNameCount();
            if (depth > selectedDepth) {
                selectedIndex = index;
                selectedDepth = depth;
            }
        }
        if (selectedIndex < 0) {
            throw new IllegalArgumentException("Source file is outside configured roots: " + sourcePath);
        }
        return selectedIndex;
    }

    private static void runPrompt() throws IOException {
        try (final var reader = new BufferedReader(new InputStreamReader(System.in))) {
            for (;;) {
                println("> ");
                final var line = reader.readLine();
                if (line == null) break;
                run(line);
                hadError = false;
            }
        }
    }

    private static void run(final String source) throws IOException {
        run(source, "ZeronMain");
    }

    private static void run(final String source, final String outputClassName) throws IOException {
        final var scanner = Scanner.from(source);
        final var tokens = scanner.scanTokens();
        final var parser = Parser.of(tokens);
        final var unit = parser.parseCompilationUnit(null);

        if (hadError) return;

        runUnits(List.of(unit),
            unit.packageName().isEmpty() ? outputClassName : unit.packageName() + "." + outputClassName,
            unit.packageName(), List.of(), true, List.of(), Paths.get("dist"));
    }

    private static void runUnits(final List<CompilationUnit> units,
                                 final String outputClassName,
                                 final String packageName,
                     final List<ZeronLibraryIndex> libraries,
                                 final boolean bundleStandardLibrarySources,
                                 final List<Path> javaClassPathRoots,
                                 final Path outputDirectory) throws IOException {
        final var compiler = Compiler.forCompilationUnits(
                        units, outputClassName, packageName, libraries,
                        bundleStandardLibrarySources, javaClassPathRoots, outputDirectory);
        compiler.resolve();

        if (hadResolutionError) return;

        compiler.compile();
    }

    private static List<ZeronLibraryIndex> loadLibraries(final List<Path> libraryRoots) throws IOException {
        final var libraries = new ArrayList<ZeronLibraryIndex>();
        for (final var root : libraryRoots) {
            libraries.add(Files.isDirectory(root)
                    ? ZeronLibraryIndex.readFromDirectory(root)
                    : ZeronLibraryIndex.readFromJar(root));
        }
        return List.copyOf(libraries);
    }

    private static void validateStandardLibraryMode(final List<ZeronLibraryIndex> libraries,
                                                    final boolean bundleStandardLibrarySources) {
        if (bundleStandardLibrarySources) return;
        final var availableTypes = libraries.stream()
                .flatMap(library -> library.declarations().stream())
                .map(declaration -> declaration.qualifiedName())
                .collect(java.util.stream.Collectors.toSet());
        if (!availableTypes.containsAll(STANDARD_LIBRARY_TYPES)) {
            final var missingTypes = new java.util.TreeSet<>(STANDARD_LIBRARY_TYPES);
            missingTypes.removeAll(availableTypes);
            throw new IllegalArgumentException("--stdlib compiled requires a library index exporting: "
                    + String.join(", ", missingTypes));
        }
    }

    private static void buildStandardLibrary(final Path outputDirectory) throws IOException {
        final var compiler = Compiler.forStandardLibrary(outputDirectory);
        compiler.resolve();
        if (hadResolutionError) return;
        compiler.compile();
    }

    private static void deleteTree(final Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static void validateJarOutputDoesNotReplaceInputs(final Path jarOutput,
                                                              final List<String> sourcePaths,
                                                              final List<Path> sourceRoots,
                                                              final Path entry,
                                                              final List<Path> libraryRoots) throws IOException {
        final var outputPath = jarOutput.toAbsolutePath().normalize();
        for (final var sourcePath : sourcePaths) {
            if (outputPath.equals(Paths.get(sourcePath).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("--jar-output must not replace a source file.");
            }
        }
        if (entry != null) {
            final var entryPath = entry.isAbsolute()
                    ? entry.normalize()
                    : sourceRoots.getFirst().toAbsolutePath().normalize().resolve(entry).normalize();
            if (outputPath.equals(entryPath)) {
                throw new IllegalArgumentException("--jar-output must not replace the project entry.");
            }
            for (final var root : sourceRoots) {
                if (outputPath.equals(root.toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException("--jar-output must not replace a project source root.");
                }
                if (!Files.isDirectory(root)) continue;
                try (final var paths = Files.walk(root)) {
                    if (paths.filter(path -> path.getFileName().toString().endsWith(".zn"))
                            .map(path -> path.toAbsolutePath().normalize())
                            .anyMatch(outputPath::equals)) {
                        throw new IllegalArgumentException("--jar-output must not replace a project source file.");
                    }
                }
            }
        }
        for (final var libraryRoot : libraryRoots) {
            if (outputPath.equals(libraryRoot.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("--jar-output must not replace a library input.");
            }
        }
    }

    private static void validateJavaClassPathRoots(final List<Path> roots) {
        for (final var root : roots) {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("Java classpath root must be a class directory; JAR loading is not implemented: "
                        + root);
            }
        }
    }

    private static String sourceClassName(final java.nio.file.Path sourcePath) {
        final var fileName = sourcePath.getFileName().toString();
        final var extensionStart = fileName.lastIndexOf('.');
        final var className = extensionStart > 0 ? fileName.substring(0, extensionStart) : fileName;
        if (className.isBlank() || className.contains(".")) {
            throw new IllegalArgumentException(
                    "Source filename must produce a valid default-package class name: " + fileName);
        }
        return className;
    }

    public static void error(final int line, final String message) {
        report(line, "", message);
    }

    private static void report(final int line, final String where, final String message) {
        println("[line " + line + "] Error" + where + ": " + message);
        hadError = true;
    }

    public static void error(final Token token, final String message) {
        if (token.type() == TokenType.EOF) {
            report(token.line(), " at end", message);
        } else {
            report(token.line(), " at '" + token.lexeme() + "'", message);
        }
    }

    public static void resolutionError(final ResolutionError error) {
        println(error.getMessage() + "\n[line " + error.token.line() + "]");
        hadResolutionError = true;
        throw error;
    }
}
