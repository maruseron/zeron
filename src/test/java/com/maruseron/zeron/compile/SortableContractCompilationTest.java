package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class SortableContractCompilationTest {
    @Test
    public void compilesSortableDefaultMethodsWithComparableEvidence() throws Exception {
        final var units = StandardLibrary.bundledUnits();
        final var outputDirectory = Files.createTempDirectory("zeron-sortable-");
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    units, "SortableCompilation", "", List.of(), false, List.of(), outputDirectory);
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
}
