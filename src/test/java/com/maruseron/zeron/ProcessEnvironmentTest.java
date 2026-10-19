package com.maruseron.zeron;

import com.maruseron.zeron.domain.ZeronLibraryIndex;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ProcessEnvironmentTest {
    @Test
    public void processAndEnvironmentApisWorkFromCompiledStandardLibrary() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "processEnvironment" + suffix;
        final var mainClass = packageName + ".ProcessEnvironmentMain" + suffix;
        final var libraryOutput = Path.of("target", "zeron-process-environment-" + suffix).toAbsolutePath();
        final var sourceFile = Path.of("target", "ProcessEnvironmentMain" + suffix + ".zn");
        final var workDirectory = Files.createTempDirectory(Path.of("target"),
                "zeron-process-environment-cwd-" + suffix).toAbsolutePath();
        Files.writeString(sourceFile, """
                package %s;
                import zeron.env.lookup;
                import zeron.io.println;
                import zeron.process.exit;
                import zeron.process.workingDirectory;
                fn main(args: Array<String>): Unit {
                    println(lookup(args[0]).getOrElse("<missing>"));
                    println(lookup(args[1]).getOrElse("<missing>"));
                    println(workingDirectory().toString());
                    if (args[2] == "exit") exit(37);
                }
                """.formatted(packageName));

        try {
            deleteTree(Path.of("dist"));
            assertEquals(0, Zeron.runCli("--build-stdlib", libraryOutput.toString()));
            final var libraryIndex = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            assertEquals(ZeronVersions.STANDARD_LIBRARY_API_VERSION, libraryIndex.standardLibraryApiVersion());
            final var systemFacade = libraryIndex.declarations().stream()
                    .filter(ZeronLibraryIndex.ExternalClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ExternalClassExport.class::cast)
                    .filter(declaration -> declaration.qualifiedName().equals("java.lang.System"))
                    .findFirst().orElseThrow();
            assertTrue(systemFacade.methods().stream().anyMatch(method ->
                    method.isStatic() && method.signature().name().equals("getenv")));
            assertTrue(systemFacade.methods().stream().anyMatch(method ->
                    method.isStatic() && method.signature().name().equals("exit")));

            deleteTree(Path.of("dist"));
            final var diagnostics = new ByteArrayOutputStream();
            final var originalOutput = System.out;
            final var originalError = System.err;
            final int compileStatus;
            try (final var capture = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
                System.setOut(capture);
                System.setErr(capture);
                compileStatus = Zeron.runCli(sourceFile.toString(), "--stdlib", "compiled",
                        "--library", libraryOutput.toString());
            } finally {
                System.setOut(originalOutput);
                System.setErr(originalError);
            }
            if (compileStatus != 0) throw new AssertionError(diagnostics.toString(StandardCharsets.UTF_8));

            final var environmentName = "ZERON_TEST_" + suffix.toUpperCase(java.util.Locale.ROOT);
            final var missingName = environmentName + "_MISSING";
            final var normalRun = runProgram(mainClass, environmentName, missingName, "normal",
                    workDirectory, libraryOutput, "present value");
            if (normalRun.exitCode() != 0) throw new AssertionError(normalRun.output());
            assertEquals("present value" + System.lineSeparator()
                            + "<missing>" + System.lineSeparator()
                            + workDirectory + System.lineSeparator(),
                    normalRun.output());

            final var exitRun = runProgram(mainClass, environmentName, missingName, "exit",
                    workDirectory, libraryOutput, "present value");
            assertEquals(37, exitRun.exitCode());
            assertEquals("present value" + System.lineSeparator()
                            + "<missing>" + System.lineSeparator()
                            + workDirectory + System.lineSeparator(),
                    exitRun.output());
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(libraryOutput);
            deleteTree(workDirectory);
            Files.deleteIfExists(sourceFile);
        }
    }

    private static ProcessResult runProgram(final String mainClass,
                                            final String environmentName,
                                            final String missingName,
                                            final String exitArgument,
                                            final Path workingDirectory,
                                            final Path libraryOutput,
                                            final String environmentValue) throws Exception {
        final var javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe" : "java");
        final var classpath = Path.of("dist").toAbsolutePath() + java.io.File.pathSeparator
                + libraryOutput.toAbsolutePath();
        final var builder = new ProcessBuilder(javaExecutable.toString(), "-cp", classpath, mainClass,
                environmentName, missingName, exitArgument)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        builder.environment().put(environmentName, environmentValue);
        final var process = builder.start();
        final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(output, process.waitFor());
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private record ProcessResult(String output, int exitCode) {}
}
