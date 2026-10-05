package com.maruseron.zeron.compile;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CompilationDiagnosticsTest {
    @Test
    public void reportsInitializerCyclesWithoutWritingClassFiles() throws IOException {
        final var source = parse("src/cycle.zn", """
                let first: Int = second;
                let second: Int = first;
                """);
        final var output = Files.createTempDirectory("zeron-compilation-diagnostics-");
        try {
            final var compiler = compiler(List.of(source), "CycleMain", output);
            compiler.resolveWithDiagnostics();

            final var result = compiler.compileWithDiagnostics();

            assertFalse(result.successful());
            assertEquals(1, result.diagnostics().size());
            assertEquals("src/cycle.zn", result.diagnostics().getFirst().primarySpan().sourcePath());
            assertEquals("ZR2016", result.diagnostics().getFirst().code().toString());
            assertTrue(result.diagnostics().getFirst().message().contains("initialization cycle"));
            try (var files = Files.list(output)) {
                assertEquals(0, files.count());
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void reportsGeneratedProgramNameConflictsWithoutWritingClassFiles() throws IOException {
        final var source = parse("src/app.zn", "class App {}");
        final var output = Files.createTempDirectory("zeron-compilation-diagnostics-");
        try {
            final var compiler = compiler(List.of(source), "App", output);
            compiler.resolveWithDiagnostics();

            final var result = compiler.compileWithDiagnostics();

            assertFalse(result.successful());
            assertEquals(1, result.diagnostics().size());
            assertEquals("src/app.zn", result.diagnostics().getFirst().primarySpan().sourcePath());
            assertEquals("ZR3001", result.diagnostics().getFirst().code().toString());
            assertTrue(result.diagnostics().getFirst().message().contains("conflicts with generated program class"));
            try (var files = Files.list(output)) {
                assertEquals(0, files.count());
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void cliReportsCompilationDiagnosticsWithSourcePathAndCompilationExitCode() throws IOException {
        final var sourceName = "Conflict" + UUID.randomUUID().toString().replace("-", "");
        final var source = Path.of("target", sourceName + ".zn");
        final var previousOutput = System.out;
        final var output = new ByteArrayOutputStream();
        try {
            Files.writeString(source, "class " + sourceName + " {}");
            deleteTree(Path.of("dist"));
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));

            assertEquals(70, Zeron.runCli(source.toString()));

            final var diagnostic = output.toString(StandardCharsets.UTF_8);
            assertTrue(diagnostic.contains(source.toString() + ":1:7: error[ZR3001]"));
            assertTrue(diagnostic.contains(" 1 | class " + sourceName + " {}"));
            assertTrue(diagnostic.contains("^" + "~".repeat(sourceName.length() - 1)));
            assertTrue(diagnostic.contains("conflicts with generated program class"));
            assertFalse(Files.exists(Path.of("dist", sourceName + ".class")));
        } finally {
            System.setOut(previousOutput);
            Files.deleteIfExists(source);
            deleteTree(Path.of("dist"));
        }
    }

    private static CompilationService compiler(final List<CompilationUnit> units,
                                               final String mainClassName,
                                               final Path output) {
        return CompilationService.forCompilationUnits(units, mainClassName, "",
                List.of(), false, List.of(), output);
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }
}
