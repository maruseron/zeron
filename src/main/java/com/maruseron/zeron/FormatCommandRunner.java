package com.maruseron.zeron;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class FormatCommandRunner {
    private FormatCommandRunner() {}

    static int run(final CliParser.Format command) throws IOException {
        final CanonicalStyle style;
        try {
            style = CanonicalStyle.load();
        } catch (final IOException exception) {
            System.err.println("error: " + exception.getMessage());
            return 1;
        }

        final var files = new TreeSet<Path>();
        final var errors = new ArrayList<String>();
        if (command.paths().isEmpty()) {
            discoverProjectFiles(files, errors);
        } else {
            for (final var path : command.paths()) {
                discoverExplicitPath(path, files, errors);
            }
        }

        var failed = !errors.isEmpty();
        errors.forEach(message -> System.err.println("error: " + message));
        for (final var path : files) {
            try {
                final var source = Files.readString(path, StandardCharsets.UTF_8);
                final var sourcePath = path.toAbsolutePath().normalize().toString();
                Zeron.rememberSource(sourcePath, source);
                final var result = SourceFormatter.format(source, sourcePath, style);
                if (!result.diagnostics().isEmpty()) {
                    Zeron.reportParseDiagnostics(result.diagnostics());
                }
                if (result.formattedSource().isEmpty()) {
                    failed = true;
                    continue;
                }
                final var formatted = result.formattedSource().get();
                if (source.equals(formatted)) continue;
                if (command.check()) {
                    System.out.println("would reformat: " + path);
                    failed = true;
                } else {
                    replaceFile(path, formatted);
                    System.out.println("formatted: " + path);
                }
            } catch (final IOException exception) {
                System.err.println("error: unable to format " + path + ": " + exception.getMessage());
                failed = true;
            }
        }
        if (Zeron.hadError) return 65;
        return failed ? 1 : 0;
    }

    private static void discoverProjectFiles(final Set<Path> files, final List<String> errors) {
        final var manifestPath = Path.of("zeron.toml").toAbsolutePath().normalize();
        if (!Files.isRegularFile(manifestPath)) {
            errors.add("No project manifest found at " + manifestPath
                    + "; pass one or more .zn files or directories.");
            return;
        }
        final ProjectManifest project;
        try {
            project = ProjectManifest.load(manifestPath);
        } catch (final IOException | IllegalArgumentException exception) {
            errors.add("unable to load project manifest " + manifestPath + ": " + exception.getMessage());
            return;
        }
        final var excluded = excludedRoots(project);
        for (final var root : project.sourceRoots()) {
            final var normalizedRoot = root.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalizedRoot)) {
                errors.add("project source root is not a directory: " + normalizedRoot);
                continue;
            }
            try {
                Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(final Path directory,
                                                              final BasicFileAttributes attributes) {
                        final var normalized = directory.toAbsolutePath().normalize();
                        if (!directory.equals(normalizedRoot)
                                && (isExcluded(normalized, excluded) || isGeneratedDirectory(directory))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                        if (attributes.isRegularFile() && isZeronSource(file)
                                && !isExcluded(file.toAbsolutePath().normalize(), excluded)) {
                            addRealPath(files, file, errors);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(final Path file, final IOException exception) {
                        errors.add("unable to inspect " + file + ": " + exception.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (final IOException exception) {
                errors.add("unable to discover sources under " + normalizedRoot + ": " + exception.getMessage());
            }
        }
    }

    private static List<Path> excludedRoots(final ProjectManifest project) {
        final var excluded = new ArrayList<Path>();
        excluded.add(project.outputDirectory());
        excluded.addAll(project.libraryRoots());
        excluded.addAll(project.javaClassPathRoots());
        return excluded.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    private static boolean isExcluded(final Path path, final List<Path> excludedRoots) {
        return excludedRoots.stream().anyMatch(path::startsWith);
    }

    private static boolean isGeneratedDirectory(final Path directory) {
        final var name = directory.getFileName();
        if (name == null) return false;
        return Set.of("target", "dist", "build", ".git").contains(name.toString());
    }

    private static void discoverExplicitPath(final Path path,
                                             final Set<Path> files,
                                             final List<String> errors) {
        final var absolute = path.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) {
            try {
                Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(final Path directory,
                                                              final BasicFileAttributes attributes) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                        if (attributes.isRegularFile() && isZeronSource(file)) addRealPath(files, file, errors);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(final Path file, final IOException exception) {
                        errors.add("unable to inspect " + file + ": " + exception.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (final IOException exception) {
                errors.add("unable to discover sources under " + absolute + ": " + exception.getMessage());
            }
            return;
        }
        if (!Files.isRegularFile(absolute)) {
            errors.add("source path is not an existing file or directory: " + absolute);
        } else if (!isZeronSource(absolute)) {
            errors.add("source file must have a .zn extension: " + absolute);
        } else {
            addRealPath(files, absolute, errors);
        }
    }

    private static boolean isZeronSource(final Path path) {
        final var name = path.getFileName();
        return name != null && name.toString().endsWith(".zn");
    }

    private static void addRealPath(final Set<Path> files, final Path path, final List<String> errors) {
        try {
            files.add(path.toRealPath());
        } catch (final IOException exception) {
            errors.add("unable to resolve source path " + path + ": " + exception.getMessage());
        }
    }

    private static void replaceFile(final Path path, final String formatted) throws IOException {
        final var parent = path.getParent();
        if (parent == null) throw new IOException("source has no parent directory.");
        final var temporary = Files.createTempFile(parent, "." + path.getFileName(), ".fmt");
        try {
            Files.writeString(temporary, formatted, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
