package com.maruseron.zeron.compile;

import com.maruseron.zeron.TestPaths;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class ListMonadSampleCompilationTest {
    @Test
    public void compilesListMonadSampleWithHigherOrderWitnessCallbacks() throws Exception {
        final var sampleRoot = TestPaths.projectFile("sample_programs", "list_monad");
        final var units = List.of(
                parseUnit(sampleRoot.resolve("src").resolve("main.zn"), "src/main.zn"),
                parseUnit(sampleRoot.resolve("src").resolve("list.zn"), "src/list.zn"));
        final var outputDirectory = Files.createTempDirectory("zeron-list-monad-");
        final var mainClassName = "ListMonadSample" + UUID.randomUUID().toString().replace("-", "");

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    units, mainClassName, "", List.of(), true, List.of(), outputDirectory);
            compiler.resolve();
            compiler.compile();
        } finally {
            try (final var paths = Files.walk(outputDirectory)) {
                for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static CompilationUnit parseUnit(final Path sourceFile, final String sourcePath) throws Exception {
        final var source = Files.readString(sourceFile);
        return Parser.of(Scanner.from(source, sourcePath).scanTokens()).parseCompilationUnit(sourcePath);
    }
}
