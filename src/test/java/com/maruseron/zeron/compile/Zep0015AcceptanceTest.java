package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;
import zeron.runtime.AppliedTypeDescriptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertFalse;

public final class Zep0015AcceptanceTest {
    @Test
    public void nonGenericEffectsCarryNoRuntimeDescriptor() throws Exception {
        final var packageName = "zep0015Effects" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-zep0015-effects-");
        final var unit = parse("Main.zn", """
                package %s;
                public effect Missing {}
                public effect Failure {
                    public property message: String;
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                assertNoDescriptorMembers(loader.loadClass(packageName + ".Failure"));
                assertNoDescriptorMembers(loader.loadClass(packageName + ".Missing$$effect"));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static void assertNoDescriptorMembers(final Class<?> type) {
        for (final Field field : type.getDeclaredFields()) {
            assertFalse(field.getType() == AppliedTypeDescriptor.class);
        }
        for (final Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (final Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(parameter == AppliedTypeDescriptor.class);
            }
        }
        for (final Method method : type.getDeclaredMethods()) {
            for (final Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter == AppliedTypeDescriptor.class);
            }
        }
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
