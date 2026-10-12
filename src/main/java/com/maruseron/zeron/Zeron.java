package com.maruseron.zeron;

import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.DiagnosticFormatter;
import com.maruseron.zeron.diagnostic.DiagnosticCode;
import com.maruseron.zeron.diagnostic.Severity;
import com.maruseron.zeron.diagnostic.SourcePosition;
import com.maruseron.zeron.diagnostic.SourceSpan;
import com.maruseron.zeron.domain.ZeronLibraryJar;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.Token;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static java.lang.IO.println;

public class Zeron {
    private static final java.util.Set<String> STANDARD_LIBRARY_TYPES = java.util.Set.of(
            "zeron.collections.Iterator", "zeron.collections.Iterable", "zeron.collections.Sink",
            "zeron.ranges.IntIterator", "zeron.ranges.IntRange");

    static boolean hadError = false;
    static boolean hadResolutionError = false;
    static boolean hadCompilationError = false;
    private static boolean debugEnabled;
    private static final java.util.Map<String, List<String>> sourceLines = new java.util.HashMap<>();
    static void main(final String... args) throws IOException {
        final var exitCode = runCli(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    public static int runCli(final String... args) throws IOException {
        hadError = false;
        hadResolutionError = false;
        hadCompilationError = false;
        debugEnabled = false;
        sourceLines.clear();
        try {
            return runCliInvocation(args);
        } catch (final ResolutionError _) {
            return exitCode();
        }
    }

    private static int runCliInvocation(final String... args) throws IOException {
        if (args.length > 0 && args[0].equals("init")) {
            return initializeProject(args);
        }
        if (args.length > 0 && args[0].equals("build")) {
            return runProjectCommand(false, args);
        }
        if (args.length > 0 && args[0].equals("run")) {
            return runProjectCommand(true, args);
        }
        if (args.length > 0 && args[0].equals("explain")) {
            return explainDiagnostic(args);
        }
        final var scriptPaths = new ArrayList<String>();
        final var sourceRoots = new ArrayList<Path>();
        final var libraryRoots = new ArrayList<Path>();
        final var javaClassPathRoots = new ArrayList<Path>();
        Path entry = null;
        Path standardLibraryOutput = null;
        Path jarOutput = null;
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
                if (++index >= args.length) {
                    throw new IllegalArgumentException("--java-classpath requires a class directory or JAR.");
                }
                javaClassPathRoots.add(Paths.get(args[index]));
            } else if (argument.equals("--build-stdlib")) {
                if (++index >= args.length) throw new IllegalArgumentException("--build-stdlib requires an output directory.");
                standardLibraryOutput = Paths.get(args[index]);
            } else if (argument.equals("--jar-output")) {
                if (++index >= args.length) throw new IllegalArgumentException("--jar-output requires a JAR file.");
                jarOutput = Paths.get(args[index]);
            } else if (argument.equals("--run-class")) {
                throw new IllegalArgumentException("--run-class was replaced by 'zeron run [--manifest <path>]'.");
            } else if (argument.equals("--stdlib-jar")) {
                throw new IllegalArgumentException("--stdlib-jar is no longer supported; configure dependencies in zeron.toml.");
            } else if (argument.equals("--stdlib")) {
                if (++index >= args.length) throw new IllegalArgumentException("--stdlib requires 'source' or 'compiled'.");
                if (args[index].equals("source")) bundleStandardLibrarySources = true;
                else if (args[index].equals("compiled")) bundleStandardLibrarySources = false;
                else throw new IllegalArgumentException("--stdlib must be 'source' or 'compiled'.");
            } else {
                scriptPaths.add(argument);
            }
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
            Repl.runPrompt();
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

    private static int initializeProject(final String... args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: zeron init");
        return initializeProject(Paths.get("."));
    }

    static int initializeProject(final Path projectDirectory) throws IOException {
        final var manifestPath = projectDirectory.resolve("zeron.toml");
        final var sourcePath = projectDirectory.resolve(Paths.get("src", "main.zn"));
        if (Files.exists(manifestPath) || Files.exists(sourcePath)) {
            throw new IllegalArgumentException("Cannot initialize project: zeron.toml or src/main.zn already exists.");
        }

        Files.createDirectories(sourcePath.getParent());
        var manifestCreated = false;
        try {
            Files.writeString(manifestPath, """
                    [project]
                    entry = "src/main.zn"
                    source-roots = ["src"]
                    output = "dist"

                    [platform]
                    stdlib = "source"
                    """, java.nio.file.StandardOpenOption.CREATE_NEW);
            manifestCreated = true;
            Files.writeString(sourcePath, """
                    fn main() {
                    }
                    """, java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (final IOException exception) {
            if (manifestCreated) Files.deleteIfExists(manifestPath);
            throw exception;
        }
        println("Initialized Zeron project with zeron.toml and src/main.zn.");
        return 0;
    }

    private static int runProjectCommand(final boolean launch, final String... args) throws IOException {
        Path manifestPath = Paths.get("zeron.toml");
        for (var index = 1; index < args.length; index++) {
            final var argument = args[index];
            if (argument.equals("--debug") || argument.equals("-d")) {
                debugEnabled = true;
            } else if (argument.equals("--manifest")) {
                if (++index >= args.length) {
                    throw new IllegalArgumentException("build --manifest requires a TOML file.");
                }
                manifestPath = Paths.get(args[index]);
            } else {
                throw new IllegalArgumentException("Unknown build option: " + argument);
            }
        }
        final var project = ProjectManifest.load(manifestPath);
        final var mainClass = runProject(project.sourceRoots(), project.entry(), project.libraryRoots(),
                project.javaClassPathRoots(), project.bundleStandardLibrarySources(),
                project.outputDirectory());
        if (!launch || exitCode() != 0) return exitCode();
        return runGeneratedClass(mainClass, project.outputDirectory(),
                project.libraryRoots(), project.javaClassPathRoots());
    }

    private static int exitCode() {
        if (hadError) return 65;
        if (hadResolutionError) return 71;
        if (hadCompilationError) return 70;
        return 0;
    }

    private static int explainDiagnostic(final String... args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: zeron explain <ZR0000>");
        final var code = new DiagnosticCode(args[1]);
        final var entry = DiagnosticCatalog.find(code)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Zeron diagnostic code: " + code));
        try (final var resource = Zeron.class.getClassLoader()
                .getResourceAsStream(entry.explanationResource())) {
            if (resource == null) {
                throw new IOException("Missing bundled explanation for diagnostic " + code + ".");
            }
            System.out.print(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return 0;
    }

    private static int runGeneratedClass(final String className,
                                         final Path outputDirectory,
                                         final List<Path> libraries,
                                         final List<Path> javaClassPathRoots) throws IOException {
        validateJavaClassPathRoots(javaClassPathRoots);
        loadLibraries(libraries);
        final Path runtimeLocation;
        try {
            runtimeLocation = Path.of(Zeron.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
        } catch (final java.net.URISyntaxException exception) {
            throw new IOException("Could not resolve the Zeron runtime classpath location.", exception);
        }
        final var classPathEntries = new ArrayList<String>();
        classPathEntries.add(outputDirectory.toAbsolutePath().normalize().toString());
        classPathEntries.add(runtimeLocation.toString());
        for (final var library : libraries) {
            classPathEntries.add(library.toAbsolutePath().normalize().toString());
        }
        for (final var root : javaClassPathRoots) {
            classPathEntries.add(root.toAbsolutePath().normalize().toString());
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
            final var source = new String(bytes, Charset.defaultCharset());
            rememberSource(sourcePath.toString(), source);
            final var result = Parser.of(Scanner.from(source, sourcePath.toString()).scanWithDiagnostics())
                    .parseCompilationUnitWithDiagnostics(sourcePath.toString());
            reportParseDiagnostics(result.diagnostics());
            units.add(result.compilationUnit());
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

    private static String runProject(final List<Path> roots,
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
            throw new IllegalArgumentException("Entry source must exist under a configured source root: " + entry);
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
            final var unitSourcePath = "root" + sourceRootIndex + "/"
                    + relativePath.toString().replace('\\', '/');
            final var source = Files.readString(sourcePath);
            rememberSource(unitSourcePath, source);
            final var result = Parser.of(Scanner.from(source, unitSourcePath)
                    .scanWithDiagnostics())
                    .parseCompilationUnitWithDiagnostics(unitSourcePath);
            reportParseDiagnostics(result.diagnostics());
            final var unit = result.compilationUnit();
            if (!unit.packageName().equals(relativeDirectory)) {
                reportParseDiagnostic(new Diagnostic(DiagnosticCatalog.PACKAGE_SOURCE_DIRECTORY_MISMATCH.code(),
                        Severity.ERROR,
                        "Package '" + (unit.packageName().isEmpty() ? "<default>" : unit.packageName())
                        + "' in " + sourcePath + " must match its directory under source root '"
                        + absoluteRoots.get(sourceRootIndex) + "' (expected '"
                        + (relativeDirectory.isEmpty() ? "<default>" : relativeDirectory) + "').",
                        new SourceSpan(unitSourcePath, new SourcePosition(1, 1, 0),
                                new SourcePosition(1, 1, 0)),
                        List.of(), List.of(), List.of()));
                continue;
            }
            units.add(unit);
        }
        if (hadError) {
            return null;
        }
        final var entryUnit = units.getFirst();
        final var programName = entryUnit.packageName().isEmpty()
                ? sourceClassName(absoluteEntry)
                : entryUnit.packageName() + "." + sourceClassName(absoluteEntry);
        runUnits(units, programName, entryUnit.packageName(), libraries,
            bundleStandardLibrarySources, javaClassPathRoots, outputDirectory);
        return programName;
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

    private static void runUnits(final List<CompilationUnit> units,
                                 final String outputClassName,
                                 final String packageName,
                     final List<ZeronLibraryIndex> libraries,
                                 final boolean bundleStandardLibrarySources,
                                 final List<Path> javaClassPathRoots,
                                 final Path outputDirectory) throws IOException {
        final var compiler = CompilationService.forCompilationUnits(
                        units, outputClassName, packageName, libraries,
                        bundleStandardLibrarySources, javaClassPathRoots, outputDirectory);
        final var resolution = compiler.resolveWithDiagnostics();
        resolution.errors().forEach(Zeron::reportResolutionDiagnostic);
        if (!resolution.errors().isEmpty()) return;

        final var compilation = compiler.compileWithDiagnostics();
        compilation.diagnostics().forEach(Zeron::reportCompilationDiagnostic);
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
        final var compiler = CompilationService.forStandardLibrary(outputDirectory);
        final var resolution = compiler.resolveWithDiagnostics();
        resolution.errors().forEach(Zeron::reportResolutionDiagnostic);
        if (!resolution.errors().isEmpty()) return;
        compiler.compileWithDiagnostics().diagnostics().forEach(Zeron::reportCompilationDiagnostic);
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
            if (Files.isDirectory(root)) continue;
            if (Files.isRegularFile(root)
                    && root.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            throw new IllegalArgumentException(
                    "Java classpath root must be an existing class directory or .jar file: " + root);
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
        reportParseDiagnostic(new Diagnostic(DiagnosticCatalog.INVALID_DECLARATION.code(), Severity.ERROR, message,
                SourceSpan.line(null, line), List.of(), List.of(), List.of()));
    }

    public static void error(final SourceSpan span, final String message) {
        reportParseDiagnostic(new Diagnostic(DiagnosticCatalog.INVALID_DECLARATION.code(), Severity.ERROR, message,
                span, List.of(), List.of(), List.of()));
    }

    static void beginReplSubmission() {
        hadError = false;
        hadResolutionError = false;
        hadCompilationError = false;
        sourceLines.clear();
    }

    static void reportParseDiagnostics(final List<Diagnostic> diagnostics) {
        diagnostics.forEach(Zeron::reportParseDiagnostic);
    }

    public static void reportDiagnostic(final Diagnostic diagnostic) {
        println(DiagnosticFormatter.format(diagnostic, Zeron::sourceLine));
        if (DiagnosticCatalog.find(diagnostic.code()).isPresent()) {
            println("  = help: Run 'zeron explain " + diagnostic.code() + "' for more information.");
        }
    }

    public static void reportParseDiagnostic(final Diagnostic diagnostic) {
        reportDiagnostic(diagnostic);
        if (diagnostic.severity() == Severity.ERROR) hadError = true;
    }

    public static void reportParseError(final Token token, final String message) {
        reportParseDiagnostic(new Diagnostic(DiagnosticCatalog.EXPECTED_SYNTAX.code(), Severity.ERROR, message,
                token.span(), List.of(), List.of(), List.of()));
    }

    public static void error(final Token token, final String message) {
        reportParseDiagnostic(Diagnostic.atToken(DiagnosticCatalog.EXPECTED_SYNTAX, token,
                token.span().sourcePath(), message));
    }

    public static void resolutionError(final ResolutionError error) {
        throw error;
    }

    public static void reportResolutionError(final ResolutionError error) {
        reportResolutionDiagnostic(error.toDiagnostic(error.sourcePath));
    }

    public static void reportResolutionDiagnostic(final Diagnostic diagnostic) {
        reportStageDiagnostic(diagnostic);
        if (diagnostic.severity() == Severity.ERROR) hadResolutionError = true;
    }

    public static void reportCompilationDiagnostic(final Diagnostic diagnostic) {
        reportStageDiagnostic(diagnostic);
        if (diagnostic.severity() == Severity.ERROR) hadCompilationError = true;
    }

    private static void reportStageDiagnostic(final Diagnostic diagnostic) {
        reportDiagnostic(diagnostic);
    }

    static void rememberSource(final String sourcePath, final String source) {
        sourceLines.put(sourcePath, source.lines().toList());
    }

    private static String sourceLine(final String sourcePath, final int lineNumber) {
        final var lines = sourceLines.get(sourcePath);
        if (lines == null || lineNumber < 1 || lineNumber > lines.size()) return null;
        return lines.get(lineNumber - 1);
    }
}
