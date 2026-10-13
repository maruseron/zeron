package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public final class HashableTest {
    @Test
    public void synthesizesBuiltInHashableEvidenceForGenericBounds() throws Exception {
        final var className = "Hashable" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var source = """
                import zeron.lang.Hashable;
                fn hashValue<T: Hashable<T>>(value: T): Int = value.hash();
                fn hashInt(): Int = hashValue(42);
                fn hashFloat(value: Float): Int = hashValue(value);
                fn hashBoolean(value: Boolean): Int = hashValue(value);
                fn hashString(value: String): Int = hashValue(value);
                fn hashUnit(): Int = hashValue(());
                fn hashNullableInt(value: Int?): Int = hashValue(value);
                fn hashNullableNull(): Int = hashValue<Int?>(null);
                fn hashNullableGeneric<T: Hashable<T>>(value: T?): Int = hashValue(value);
                fn hashNullableGenericInt(): Int = hashNullableGeneric(43);
                fn hashNullableGenericNull(): Int = hashNullableGeneric<Int>(null);
                """;

        try {
            final var compiler = new CompilationService(parse(source), className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(42, generated.getMethod("hashInt").invoke(null));
                assertEquals(Double.hashCode(Double.NaN),
                        generated.getMethod("hashFloat", double.class).invoke(null, Double.NaN));
                assertEquals(Double.hashCode(-0.0d),
                        generated.getMethod("hashFloat", double.class).invoke(null, -0.0d));
                assertEquals(Double.hashCode(0.0d),
                        generated.getMethod("hashFloat", double.class).invoke(null, 0.0d));
                assertEquals(Boolean.hashCode(true),
                        generated.getMethod("hashBoolean", boolean.class).invoke(null, true));
                assertEquals("Zeron".hashCode(),
                        generated.getMethod("hashString", String.class).invoke(null, "Zeron"));
                assertEquals(0, generated.getMethod("hashUnit").invoke(null));
                assertEquals(42, generated.getMethod("hashNullableInt", Integer.class).invoke(null, 42));
                assertEquals(0, generated.getMethod("hashNullableInt", Integer.class)
                        .invoke(null, new Object[]{null}));
                assertEquals(0, generated.getMethod("hashNullableNull").invoke(null));
                assertEquals(43, generated.getMethod("hashNullableGenericInt").invoke(null));
                assertEquals(0, generated.getMethod("hashNullableGenericNull").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static java.util.List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}
