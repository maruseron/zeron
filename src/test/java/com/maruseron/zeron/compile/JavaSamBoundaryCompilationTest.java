package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.TestPaths;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/// A Java call that hands a Zeron callback to host code is marked with the suspension boundary, and
/// the marker does not disturb ordinary SAM conversion.
public final class JavaSamBoundaryCompilationTest {
    private static final Path JAVA_FIXTURE_ROOT = TestPaths.projectFile("target", "test-classes");

    @Test
    public void javaCallsReceivingCallbacksInstallASuspensionBoundary() throws Exception {
        final var packageName = "javasam" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-java-sam-");
        final var unit = parse("Main.zn", """
                package %s;
                import com.maruseron.zeron.fixtures.JavaSamFixture;
                public fn apply(value: String): String? =
                    unsafe JavaSamFixture.applyFunction((text) -> text!!.trim(), value);
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), true,
                    List.of(JAVA_FIXTURE_ROOT), output);
            final var resolution = compiler.resolveWithDiagnostics();
            assertTrue(resolution.errors().toString(), resolution.errors().isEmpty());
            final var compilation = compiler.compileWithDiagnostics();
            assertTrue(compilation.diagnostics().toString(), compilation.successful());

            final var classFile = output.resolve(Path.of(packageName, "Main.class"));
            assertTrue(classFile.toString(), Files.exists(classFile));
            final var bytecode = new String(Files.readAllBytes(classFile),
                    StandardCharsets.ISO_8859_1);
            assertTrue(bytecode, bytecode.contains("SuspensionGuard"));
            assertTrue(bytecode, bytecode.contains("host callback"));

            try (final var loader = new java.net.URLClassLoader(new java.net.URL[]{
                    output.toUri().toURL(), JAVA_FIXTURE_ROOT.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals("value", main.getMethod("apply", String.class).invoke(null, "  value  "));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens())
                .parseCompilationUnit(sourceName);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
