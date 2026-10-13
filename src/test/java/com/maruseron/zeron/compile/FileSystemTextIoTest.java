package com.maruseron.zeron.compile;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FileSystemTextIoTest {
    @Test
    public void handlesPathsFilesystemOperationsAndUtf8Text() throws Exception {
        final var packageName = "fileSystem" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-fs-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.fs.Path;
                import zeron.io.IoFailure;
                import zeron.io.text.readText;
                import zeron.io.text.writeText;
                import zeron.resource.DisposeFailure;
                public fn normalized(value: String): String =
                    Path.from(value).normalize().toString();
                public fn displayPath(value: String): String = "path=" + Path.from(value);
                public fn resolved(value: String, child: String): String =
                    Path.from(value).resolve(child).toString();
                public fn same(left: String, right: String): Boolean =
                    Path.from(left) == Path.from(right);
                public fn exists(value: String): Boolean = Path.from(value).exists();
                public fn isFile(value: String): Boolean = Path.from(value).isFile();
                public fn isDirectory(value: String): Boolean = Path.from(value).isDirectory();
                public fn createDirectories(value: String): Unit raises IoFailure {
                    Path.from(value).createDirectories();
                }
                public fn createDirectory(value: String): Unit raises IoFailure {
                    Path.from(value).createDirectory();
                }
                public fn deleteIfExists(value: String): Boolean raises IoFailure =
                    Path.from(value).deleteIfExists();
                public fn read(value: String): String raises IoFailure =
                    readText(Path.from(value));
                public fn write(value: String, text: String): Unit
                        raises IoFailure, DisposeFailure {
                    writeText(Path.from(value), text);
                }
                public fn readOrMissing(value: String): String =
                    handle (readText(Path.from(value))) with {
                        case IoFailure.cause(_) -> "missing";
                    };
                """.formatted(packageName));

        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(Path.of("alpha", "beta").toString(),
                        main.getMethod("resolved", String.class, String.class)
                                .invoke(null, "alpha", "beta"));
                assertEquals(Path.of("alpha", "beta").normalize().toString(),
                        main.getMethod("normalized", String.class).invoke(null, "alpha/./beta"));
                assertEquals("path=alpha/beta",
                        main.getMethod("displayPath", String.class).invoke(null, "alpha/beta"));
                assertTrue((Boolean) main.getMethod("same", String.class, String.class)
                        .invoke(null, "alpha/beta", "alpha/beta"));
                assertFalse((Boolean) main.getMethod("same", String.class, String.class)
                        .invoke(null, "alpha/beta", "alpha/gamma"));
                assertEquals("missing", main.getMethod("readOrMissing", String.class)
                        .invoke(null, output.resolve("not-found.txt").toString()));

                final var nested = output.resolve("created").resolve("nested");
                final var single = output.resolve("single");
                main.getMethod("createDirectory", String.class).invoke(null, single.toString());
                assertTrue(Files.isDirectory(single));
                main.getMethod("createDirectories", String.class).invoke(null, nested.toString());
                assertTrue(Files.isDirectory(nested));
                assertTrue((Boolean) main.getMethod("exists", String.class).invoke(null, nested.toString()));
                assertTrue((Boolean) main.getMethod("isDirectory", String.class).invoke(null, nested.toString()));

                final var textFile = nested.resolve("sample.txt");
                final var content = "café\r\nsecond line\n";
                main.getMethod("write", String.class, String.class)
                        .invoke(null, textFile.toString(), content);
                assertTrue((Boolean) main.getMethod("isFile", String.class)
                        .invoke(null, textFile.toString()));
                assertEquals(content, Files.readString(textFile));
                assertEquals(content, main.getMethod("read", String.class).invoke(null, textFile.toString()));
                assertTrue((Boolean) main.getMethod("deleteIfExists", String.class)
                        .invoke(null, textFile.toString()));
                assertFalse((Boolean) main.getMethod("deleteIfExists", String.class)
                        .invoke(null, textFile.toString()));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void compiledLibraryPreservesStaticIoFacadeMappings() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "compiledFs" + suffix;
        final var libraryOutput = Path.of("target", "zeron-fs-library-" + suffix);
        final var sourceFile = Path.of("target", "CompiledFsClient" + suffix + ".zn");
        final var outputPath = Files.createTempFile("zeron-compiled-fs-", ".txt");
        final var programName = packageName + "." + sourceFile.getFileName().toString()
                .replaceFirst("\\.zn$", "");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package %s;
                import zeron.fs.Path;
                import zeron.io.IoFailure;
                import zeron.io.text.readText;
                import zeron.io.text.writeText;
                import zeron.resource.DisposeFailure;
                public fn roundTrip(path: String): String raises IoFailure, DisposeFailure {
                    writeText(Path.from(path), "compiled café");
                    return readText(Path.from(path));
                }
                """.formatted(packageName));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--build-stdlib", libraryOutput.toString()));
            final var libraryIndex = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var filesFacade = libraryIndex.declarations().stream()
                    .filter(ZeronLibraryIndex.ExternalClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ExternalClassExport.class::cast)
                    .filter(declaration -> declaration.qualifiedName().equals("java.nio.file.Files"))
                    .findFirst().orElseThrow();
            final var readString = filesFacade.methods().stream()
                    .filter(method -> method.signature().name().equals("readString"))
                    .findFirst().orElseThrow();
            assertTrue(readString.isStatic());
            assertEquals("zeron.io.IoFailure",
                    readString.exceptionMappings().getFirst().effectType());

            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli(sourceFile.toString(), "--stdlib", "compiled",
                    "--library", libraryOutput.toString()));
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var client = loader.loadClass(programName);
                assertEquals("compiled café",
                        client.getMethod("roundTrip", String.class).invoke(null, outputPath.toString()));
            }
            assertEquals("compiled café", Files.readString(outputPath));
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(libraryOutput);
            Files.deleteIfExists(sourceFile);
            Files.deleteIfExists(outputPath);
        }
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens())
                .parseCompilationUnit(sourceName);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
