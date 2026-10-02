package com.maruseron.zeron;

import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.io.BufferedReader;
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
    static boolean hadError = false;
    static boolean hadResolutionError = false;
    private static boolean debugEnabled;

    static void main(final String... args) throws IOException {
        debugEnabled = false;
        final var scriptPaths = new ArrayList<String>();
        final var sourceRoots = new ArrayList<Path>();
        Path entry = null;
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
            } else {
                scriptPaths.add(argument);
            }
        }
        if (entry != null || !sourceRoots.isEmpty()) {
            if (entry == null || sourceRoots.isEmpty()) {
                throw new IllegalArgumentException("Project compilation requires both --root and --entry.");
            }
            runProject(sourceRoots, entry);
        } else if (scriptPaths.isEmpty()) runPrompt();
        else runFiles(scriptPaths);
    }

    public static void debug(final String message) {
        if (debugEnabled) System.err.println(message);
    }

    private static void runFiles(final List<String> paths) throws IOException {
        final var units = new ArrayList<CompilationUnit>();
        for (final var path : paths) {
            final var sourcePath = Paths.get(path);
            final var bytes = Files.readAllBytes(sourcePath);
            units.add(Parser.of(Scanner.from(new String(bytes, Charset.defaultCharset())).scanTokens())
                    .parseCompilationUnit(sourcePath.toString()));
        }
        if (hadError) {
            System.exit(65);
            return;
        }
        final var packageName = units.getFirst().packageName();
        for (final var unit : units) {
            if (unit.packageName().equals(packageName)) continue;
            final var hasTopLevelValues = unit.declarations().stream()
                    .anyMatch(Stmt.Var.class::isInstance);
            if (hasTopLevelValues) {
                error(1, "Cross-package top-level values are deferred until initialization order is specified.");
                System.exit(65);
                return;
            }
        }
        units.add(StandardLibrary.iterationUnit());
        final var programName = packageName.isEmpty()
                ? sourceClassName(Paths.get(paths.getFirst()))
                : packageName + "." + sourceClassName(Paths.get(paths.getFirst()));
        runUnits(units, programName, packageName);
        if (hadError) System.exit(65);
        if (hadResolutionError) System.exit(71);
    }

    private static void runProject(final List<Path> roots, final Path entry) throws IOException {
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
            final var sourceRootIndex = absoluteRoots.stream().filter(sourcePath::startsWith)
                .mapToInt(absoluteRoots::indexOf).findFirst().orElse(0);
            final var relativePath = "root" + sourceRootIndex + "/"
                + absoluteRoots.get(sourceRootIndex).relativize(sourcePath).toString().replace('\\', '/');
            units.add(Parser.of(Scanner.from(Files.readString(sourcePath)).scanTokens())
                .parseCompilationUnit(relativePath));
        }
        if (hadError) {
            System.exit(65);
            return;
        }
        final var entryUnit = units.getFirst();
        final var programName = entryUnit.packageName().isEmpty()
                ? sourceClassName(absoluteEntry)
                : entryUnit.packageName() + "." + sourceClassName(absoluteEntry);
        runUnits(units, programName, entryUnit.packageName());
        if (hadError) System.exit(65);
        if (hadResolutionError) System.exit(71);
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

        runUnits(List.of(unit, StandardLibrary.iterationUnit()),
            unit.packageName().isEmpty() ? outputClassName : unit.packageName() + "." + outputClassName,
            unit.packageName());
    }

        private static void runUnits(final List<CompilationUnit> units,
                     final String outputClassName,
                     final String packageName) throws IOException {
        final var compiler = Compiler.forCompilationUnits(units, outputClassName, packageName);
        compiler.resolve();

        if (hadResolutionError) return;

        compiler.compile();
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
