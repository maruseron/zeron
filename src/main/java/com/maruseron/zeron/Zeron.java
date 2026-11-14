package com.maruseron.zeron;

import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.DiagnosticFormatter;
import com.maruseron.zeron.diagnostic.ExplanationFormatter;
import com.maruseron.zeron.diagnostic.TerminalColorSupport;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.IO.println;

public class Zeron {
    private static final int USAGE_ERROR_EXIT_CODE = 64;
    private static final java.util.Set<String> STANDARD_LIBRARY_TYPES = java.util.Set.of(
            "zeron.collections.Iterator", "zeron.collections.Iterable", "zeron.collections.Sink",
            "zeron.ranges.IntIterator", "zeron.ranges.IntRange");

    static boolean hadError = false;
    static boolean hadResolutionError = false;
    static boolean hadCompilationError = false;
    private static boolean hadWarning = false;
    private static boolean denyWarnings = false;
    private static boolean suppressWarnings = false;
    private static final List<Diagnostic> warnings = new java.util.ArrayList<>();
    private static final java.util.Set<String> warningKeys = new java.util.HashSet<>();
    private static boolean debugEnabled;
    private static boolean replSession;
    private static final java.util.Map<String, List<String>> sourceLines = new java.util.HashMap<>();

    static void main(final String... args) throws IOException {
        final var exitCode = runCli(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    public static int runCli(final String... args) throws IOException {
        hadError = false;
        hadResolutionError = false;
        hadCompilationError = false;
        hadWarning = false;
        denyWarnings = false;
        suppressWarnings = false;
        warnings.clear();
        warningKeys.clear();
        debugEnabled = false;
        replSession = false;
        sourceLines.clear();
        try {
            return runCliInvocation(args);
        } catch (final ResolutionError _) {
            return exitCode();
        } catch (final IllegalArgumentException exception) {
            System.err.println("error: " + exception.getMessage());
            return USAGE_ERROR_EXIT_CODE;
        }
    }

    private static int runCliInvocation(final String... args) throws IOException {
        final var parseResult = CliParser.parse(args);
        if (parseResult instanceof CliParser.InvalidArguments invalidArguments) {
            System.err.println("error: " + invalidArguments.message());
            return USAGE_ERROR_EXIT_CODE;
        }
        final var invocation = ((CliParser.Parsed) parseResult).invocation();
        debugEnabled = invocation.debug();
        denyWarnings = invocation instanceof CliParser.ProjectCommand project && project.denyWarnings()
                || invocation instanceof CliParser.Compile compilation && compilation.denyWarnings();
        suppressWarnings = invocation instanceof CliParser.ProjectCommand project && project.noWarnings()
                || invocation instanceof CliParser.Compile compilation && compilation.noWarnings();
        return switch (invocation) {
            case CliParser.Version _ -> {
                System.out.println("zeron " + ZeronVersion.current());
                yield 0;
            }
            case CliParser.Initialize _ -> initializeProject(Paths.get("."));
            case CliParser.ProjectCommand command -> runProjectCommand(command);
            case CliParser.Format command -> FormatCommandRunner.run(command);
            case CliParser.LspCommand command -> com.maruseron.zeron.lsp.LspServer.run(command.manifest());
            case CliParser.Explain explain -> explainDiagnostic(explain.code());
            case CliParser.Help help -> {
                CliHelp.print(help.topic());
                yield 0;
            }
            case CliParser.Dump dump -> {
                ZeronLibraryIndexDump.main(dump.source().toString());
                yield 0;
            }
            case CliParser.DumpStandardLibrary dump -> {
                final var successful = ZeronLibraryIndexDump.dumpBundledStandardLibrary(dump.packageName());
                yield successful ? 0 : exitCode() == 0 ? 1 : exitCode();
            }
            case CliParser.BuildStandardLibrary build -> buildStandardLibraryCommand(build);
            case CliParser.Repl _ -> runRepl();
            case CliParser.Compile compilation -> runCompilation(compilation);
        };
    }

    private static int buildStandardLibraryCommand(final CliParser.BuildStandardLibrary command)
            throws IOException {
        buildStandardLibrary(command.outputDirectory());
        if (command.jarOutput() != null && exitCode() == 0) {
            ZeronLibraryJar.write(command.outputDirectory(), command.jarOutput());
        }
        return exitCode();
    }

    private static int runRepl() throws IOException {
        replSession = true;
        try {
            Repl.runPrompt();
        } finally {
            replSession = false;
        }
        return exitCode();
    }

    private static int runCompilation(final CliParser.Compile command) throws IOException {
        final var outputDirectory = command.jarOutput() == null
                ? Paths.get("dist")
                : Files.createTempDirectory("zeron-jar-stage-");
        try {
            if (command.entry() != null) {
                runProject(command.sourceRoots(), command.entry(), command.libraries(),
                        command.javaClassPathRoots(), command.standardLibraryMode(), outputDirectory);
            } else {
                runFiles(command.sourceFiles(), command.libraries(), command.javaClassPathRoots(),
                        command.standardLibraryMode(), outputDirectory);
            }
            if (command.jarOutput() != null && exitCode() == 0) {
                ZeronLibraryJar.write(outputDirectory, command.jarOutput());
            }
            return exitCode();
        } finally {
            if (command.jarOutput() != null) deleteTree(outputDirectory);
        }
    }

    static int initializeProject(final Path projectDirectory) throws IOException {
        final var manifestPath = projectDirectory.resolve("zeron.toml");
        final var sourcePath = projectDirectory.resolve(Paths.get("src", "main.zn"));
        if (Files.exists(manifestPath) || Files.exists(sourcePath)) {
            throw new IllegalArgumentException("Cannot initialize project: zeron.toml or src/main.zn already exists.");
        }

        Files.createDirectories(sourcePath.getParent());
        final var standardLibraryMode = bundledStandardLibraryJar().isPresent() ? "compiled" : "source";
        var manifestCreated = false;
        try {
            Files.writeString(manifestPath, """
                    [project]
                    entry = "src/main.zn"
                    source-roots = ["src"]
                    output = "dist"

                    [platform]
                    stdlib = "%s"
                    """.formatted(standardLibraryMode), java.nio.file.StandardOpenOption.CREATE_NEW);
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

    private static int runProjectCommand(final CliParser.ProjectCommand command) throws IOException {
        final var project = command.project();
        final var target = command.target();
        final var compilation = runProject(project.sourceRoots(), target.entry(), project.libraryRoots(),
                project.javaClassPathRoots(), project.standardLibraryMode(),
                target.outputDirectory());
        if (compilation == null || !command.launch() || exitCode() != 0) return exitCode();
        return runGeneratedClass(compilation.mainClass(), target.outputDirectory(),
                compilation.libraryRoots(), project.javaClassPathRoots());
    }

    private static int exitCode() {
        if (hadError) return 65;
        if (hadResolutionError) return 71;
        if (hadCompilationError) return 70;
        if (denyWarnings && hadWarning) return 71;
        return 0;
    }

    private static int explainDiagnostic(final String diagnosticCode) throws IOException {
        final var code = new DiagnosticCode(diagnosticCode);
        final var entry = DiagnosticCatalog.find(code)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Zeron diagnostic code: " + code));
        try (final var resource = Zeron.class.getClassLoader()
                .getResourceAsStream(entry.explanationResource())) {
            if (resource == null) {
                throw new IOException("Missing bundled explanation for diagnostic " + code + ".");
            }
            final var markdown = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.print(ExplanationFormatter.format(markdown, colorEnabled()));
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
                                 final StandardLibraryMode standardLibraryMode,
                                 final Path outputDirectory) throws IOException {
        final var setup = resolveStandardLibrary(standardLibraryMode, libraryRoots);
        validateJavaClassPathRoots(javaClassPathRoots);
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
        runUnits(units, programName, packageName, setup.libraries(),
            setup.bundleStandardLibrarySources(), javaClassPathRoots, outputDirectory);
    }

    private static ProjectCompilation runProject(final List<Path> roots,
                                   final Path entry,
                                   final List<Path> libraryRoots,
                                   final List<Path> javaClassPathRoots,
                                   final StandardLibraryMode standardLibraryMode,
                                   final Path outputDirectory) throws IOException {
        final var setup = resolveStandardLibrary(standardLibraryMode, libraryRoots);
        validateJavaClassPathRoots(javaClassPathRoots);
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
        runUnits(units, programName, entryUnit.packageName(), setup.libraries(),
            setup.bundleStandardLibrarySources(), javaClassPathRoots, outputDirectory);
        return new ProjectCompilation(programName, setup.libraryRoots());
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

        cleanOutputDirectory(outputDirectory, units);
        final var compilation = compiler.compileWithDiagnostics();
        compilation.diagnostics().forEach(Zeron::reportCompilationDiagnostic);
    }

    static void cleanOutputDirectory(final Path outputDirectory,
                                     final List<CompilationUnit> units) throws IOException {
        final var target = outputDirectory.toAbsolutePath().normalize();
        final var currentDirectory = Path.of("").toAbsolutePath().normalize();
        if (target.getParent() == null) {
            throw new IllegalArgumentException("Refusing to clean the filesystem root as an output directory.");
        }
        if (target.equals(currentDirectory)) {
            throw new IllegalArgumentException(
                    "Refusing to clean the current directory as an output directory: " + target);
        }
        final var home = System.getProperty("user.home");
        if (home != null && !home.isBlank()
                && target.equals(Path.of(home).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(
                    "Refusing to clean the user home directory as an output directory: " + target);
        }
        if (Files.exists(target) && !Files.isDirectory(target)) {
            throw new IllegalArgumentException("Output path is not a directory: " + target);
        }
        if (Files.exists(target.resolve("zeron.toml")) || Files.exists(target.resolve(".git"))) {
            throw new IllegalArgumentException(
                    "Refusing to clean a project or repository directory as an output directory: " + target);
        }
        for (final var unit : units) {
            final var sourcePath = unit.sourcePath();
            if (sourcePath == null || sourcePath.isBlank()) continue;
            final var source = Path.of(sourcePath).toAbsolutePath().normalize();
            if (source.startsWith(target)) {
                throw new IllegalArgumentException("Refusing to clean output directory " + target
                        + " because it contains compilation source " + source + ".");
            }
        }
        Files.createDirectories(target);
        try (final var entries = Files.list(target)) {
            for (final var entry : entries.toList()) deleteTree(entry);
        }
    }

    static List<ZeronLibraryIndex> loadLibraries(final List<Path> libraryRoots) throws IOException {
        final var libraries = new ArrayList<ZeronLibraryIndex>();
        for (final var root : libraryRoots) {
            libraries.add(Files.isDirectory(root)
                    ? ZeronLibraryIndex.readFromDirectory(root)
                    : ZeronLibraryIndex.readFromJar(root));
        }
        return List.copyOf(libraries);
    }

    record ProjectCompilation(String mainClass, List<Path> libraryRoots) {
        ProjectCompilation {
            libraryRoots = List.copyOf(libraryRoots);
        }
    }

    record StandardLibrarySetup(boolean bundleStandardLibrarySources,
                                List<Path> libraryRoots,
                                List<ZeronLibraryIndex> libraries) {
        StandardLibrarySetup {
            libraryRoots = List.copyOf(libraryRoots);
            libraries = List.copyOf(libraries);
        }
    }

    static StandardLibrarySetup resolveStandardLibrary(final StandardLibraryMode mode,
                                                       final List<Path> libraryRoots) throws IOException {
        return resolveStandardLibrary(mode, libraryRoots, getZeronCommandHome());
    }

    static StandardLibrarySetup resolveStandardLibrary(final StandardLibraryMode mode,
                                                       final List<Path> libraryRoots,
                                                       final Path home) throws IOException {
        final var libraries = loadLibraries(libraryRoots);
        if (mode == StandardLibraryMode.SOURCE) {
            return new StandardLibrarySetup(true, libraryRoots, libraries);
        }
        if (exportsStandardLibrary(libraries)) {
            return new StandardLibrarySetup(false, libraryRoots, libraries);
        }
        final var bundledJar = bundledStandardLibraryJar(home);
        if (bundledJar.isPresent()) {
            try {
                final var resolvedRoots = new ArrayList<>(libraryRoots);
                resolvedRoots.add(bundledJar.get());
                final var resolvedLibraries = new ArrayList<>(libraries);
                resolvedLibraries.add(ZeronLibraryIndex.readFromJar(bundledJar.get()));
                return new StandardLibrarySetup(false, resolvedRoots, resolvedLibraries);
            } catch (final IOException incompatible) {
                if (mode == StandardLibraryMode.COMPILED) {
                    throw new IllegalArgumentException("Compiled standard-library mode found an incompatible "
                            + "library at " + bundledJar.get() + ": " + incompatible.getMessage()
                            + " Install a matching Zeron release, add one to [dependencies].zeron-libraries, "
                            + "or set [platform].stdlib = \"source\".", incompatible);
                }
                debug("Installed compiled standard library is incompatible; "
                        + "using bundled standard-library sources: " + incompatible.getMessage());
            }
        }
        if (mode == StandardLibraryMode.COMPILED) {
            throw new IllegalArgumentException("Compiled standard-library mode requires a standard-library "
                    + "library. Install a Zeron release that bundles lib/zeron-stdlib-*.jar, add one to "
                    + "[dependencies].zeron-libraries, or set [platform].stdlib = \"source\".");
        }
        debug("No compiled standard library found; using bundled standard-library sources.");
        return new StandardLibrarySetup(true, libraryRoots, libraries);
    }

    private static boolean exportsStandardLibrary(final List<ZeronLibraryIndex> libraries) {
        final var availableTypes = libraries.stream()
                .flatMap(library -> library.declarations().stream())
                .map(declaration -> declaration.qualifiedName())
                .collect(java.util.stream.Collectors.toSet());
        return availableTypes.containsAll(STANDARD_LIBRARY_TYPES);
    }

    private static String getSystemPath() {
        final var env = System.getenv();
        if (env.containsKey("Path")) return env.get("Path");
        if (env.containsKey("PATH")) return env.get("PATH");

        // Fallback: Case-insensitive search just to be safe
        return env.entrySet().stream()
                .filter(e -> "PATH".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    static Path getZeronCommandHome() {
        final var configuredHome = System.getProperty("zeron.home");
        if (configuredHome != null && !configuredHome.isBlank()) {
            return Path.of(configuredHome).toAbsolutePath().normalize();
        }
        return getCommandHome(getSystemPath(), "zeron").orElseThrow(() -> new IllegalArgumentException("No Zeron installation found."));
    }

    static Optional<Path> getCommandHome(final String pathEnvironment, final String comand) {
        if (pathEnvironment == null) return Optional.empty();

        // Windows expects extensions like .exe or .cmd, whereas Unix doesn't
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String[] extensions = isWindows ? new String[]{ ".exe", ".cmd", ".bat" } : new String[]{""};

        // 2. Split by the OS-safe separator (';' on Windows)
        return Arrays.stream(pathEnvironment.split(File.pathSeparator))
            .map(Paths::get)
            // 3. For each directory, check if the command file actually exists
            .flatMap(dir -> Arrays.stream(extensions).map(ext -> dir.resolve(comand + ext)))
            .filter(Files::isRegularFile)   // Ensures it's a file, not a directory
            .filter(Files::isExecutable)    // Ensures we actually have rights to run it
            .map(Path::getParent)           // Discard the command, so {installation.home}/bin
            .map(Path::getParent)           // We don't need bin, we need the installation home
            .findFirst();
    }

    public static Optional<Path> bundledStandardLibraryJar() throws IOException {
        return bundledStandardLibraryJar(getZeronCommandHome());
    }

    static Optional<Path> bundledStandardLibraryJar(final Path installationHome) throws IOException {
        if (installationHome == null) return Optional.empty();
        final var libraryDirectory = installationHome.resolve("lib");
        if (!Files.isDirectory(libraryDirectory)) return Optional.empty();
        try (final var entries = Files.list(libraryDirectory)) {
            final var candidates = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        final var name = path.getFileName().toString();
                        return name.startsWith("zeron-stdlib-") && name.endsWith(".jar");
                    })
                    .sorted()
                    .toList();
            if (candidates.isEmpty()) return Optional.empty();
            if (candidates.size() > 1) {
                throw new IllegalArgumentException("Multiple compiled standard libraries found in "
                        + libraryDirectory + ": " + candidates);
            }
            return Optional.of(candidates.getFirst());
        }
    }

    private static void buildStandardLibrary(final Path outputDirectory) throws IOException {
        final var compiler = CompilationService.forStandardLibrary(outputDirectory);
        final var resolution = compiler.resolveWithDiagnostics();
        resolution.errors().forEach(Zeron::reportResolutionDiagnostic);
        if (!resolution.errors().isEmpty()) return;
        cleanOutputDirectory(outputDirectory, List.of());
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

    static void validateJavaClassPathRoots(final List<Path> roots) {
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
        hadWarning = false;
        suppressWarnings = false;
        warnings.clear();
        warningKeys.clear();
        sourceLines.clear();
    }

    static void reportParseDiagnostics(final List<Diagnostic> diagnostics) {
        diagnostics.forEach(Zeron::reportParseDiagnostic);
    }

    public static void reportDiagnostic(final Diagnostic diagnostic) {
        final var colorize = colorEnabled();
        println(DiagnosticFormatter.format(diagnostic, Zeron::sourceLine, colorize));
        if (DiagnosticCatalog.find(diagnostic.code()).isPresent()) {
            final var help = "  = help: Run 'zeron explain " + diagnostic.code() + "' for more information.";
            println(colorize
                    ? "\u001b[32m  = help:\u001b[0m Run 'zeron explain " + diagnostic.code()
                            + "' for more information."
                    : help);
        }
    }

    private static boolean colorEnabled() {
        return !replSession && TerminalColorSupport.isEnabled();
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
        if (suppressWarnings && diagnostic.severity() == Severity.WARNING) return;
        reportStageDiagnostic(diagnostic);
        if (diagnostic.severity() == Severity.ERROR) hadResolutionError = true;
    }

    public static void reportCompilationDiagnostic(final Diagnostic diagnostic) {
        if (suppressWarnings && diagnostic.severity() == Severity.WARNING) return;
        reportStageDiagnostic(diagnostic);
        if (diagnostic.severity() == Severity.ERROR) hadCompilationError = true;
    }

    public static void warning(final Token token, final DiagnosticCatalog.Entry entry,
                               final String message) {
        warning(Diagnostic.atToken(entry, token, token.span().sourcePath(), message));
    }

    /// Reports a non-fatal diagnostic. Warnings share the formatter and source spans with errors but
    /// never set an error flag, so the current stage keeps running.
    public static void warning(final Diagnostic diagnostic) {
        final var key = diagnostic.code() + "|" + diagnostic.primarySpan() + "|"
                + diagnostic.message();
        if (!warningKeys.add(key)) return;
        warnings.add(diagnostic);
        hadWarning = true;
        if (!suppressWarnings) reportStageDiagnostic(diagnostic);
    }

    public static List<Diagnostic> warnings() {
        return List.copyOf(warnings);
    }

    /// Starts a fresh warning collection without disturbing error state, used by repeated
    /// language-server analyses that parse several files before resolving them.
    static void clearWarnings() {
        warnings.clear();
        warningKeys.clear();
        hadWarning = false;
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
