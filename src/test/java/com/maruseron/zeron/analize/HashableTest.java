package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.runtime.HashSupport;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HashableTest {
    @Test
    public void synthesizesBuiltInHashableEvidenceForGenericBounds() throws Exception {
        final var className = "Hashable" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var source = """
                import zeron.lang.Hashable;
                fn hashValue<T: nullable + Hashable<T>>(value: T): Long {
                    if (value == null) return 0L;
                    return value.hash();
                }
                fn hashInt(): Long = hashValue(42);
                fn hashFloat(value: Float): Long = hashValue(value);
                fn hashBoolean(value: Boolean): Long = hashValue(value);
                fn hashString(value: String): Long = hashValue(value);
                fn hashUnit(): Long = hashValue(());
                fn hashNullableInt(value: Int?): Long = hashValue(value);
                fn hashNullableNull(): Long = hashValue<Int?>(null);
                fn hashNullableGeneric<T: Hashable<T>>(value: T?): Long = hashValue(value);
                fn hashNullableGenericInt(): Long = hashNullableGeneric(43);
                fn hashNullableGenericNull(): Long = hashNullableGeneric<Int>(null);
                fn hashLong(value: Long): Long = hashValue(value);
                """;

        try {
            final var compiler = new CompilationService(parse(source), className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(42L, generated.getMethod("hashInt").invoke(null));
                assertEquals(Double.doubleToLongBits(Double.NaN),
                        generated.getMethod("hashFloat", double.class).invoke(null, Double.NaN));
                assertEquals(Double.doubleToLongBits(-0.0d),
                        generated.getMethod("hashFloat", double.class).invoke(null, -0.0d));
                assertEquals(Double.doubleToLongBits(0.0d),
                        generated.getMethod("hashFloat", double.class).invoke(null, 0.0d));
                assertEquals(1231L,
                        generated.getMethod("hashBoolean", boolean.class).invoke(null, true));
                assertEquals(HashSupport.hashString("Zeron"),
                        generated.getMethod("hashString", String.class).invoke(null, "Zeron"));
                assertEquals(0L, generated.getMethod("hashUnit").invoke(null));
                assertEquals(42L, generated.getMethod("hashNullableInt", Integer.class).invoke(null, 42));
                assertEquals(0L, generated.getMethod("hashNullableInt", Integer.class)
                        .invoke(null, new Object[]{null}));
                assertEquals(0L, generated.getMethod("hashNullableNull").invoke(null));
                assertEquals(43L, generated.getMethod("hashNullableGenericInt").invoke(null));
                assertEquals(0L, generated.getMethod("hashNullableGenericNull").invoke(null));

                final var high = 0x0000_0001_0000_0002L;
                final var low = 0x0000_0002_0000_0001L;
                assertEquals(high, generated.getMethod("hashLong", long.class).invoke(null, high));
                assertEquals(low, generated.getMethod("hashLong", long.class).invoke(null, low));
                assertNotEquals(generated.getMethod("hashLong", long.class).invoke(null, high),
                        generated.getMethod("hashLong", long.class).invoke(null, low));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsIntHashImplementationsWithADedicatedDiagnostic() {
        final var compiler = new CompilationService(parse("""
                import zeron.lang.Eq;
                import zeron.lang.Hashable;
                class Bad is Eq<Bad> {
                    value: Int;
                    public eq(other: Bad): Boolean = this.value == other.value;
                }
                witness Hashable<Bad> for Bad {
                    fn hash(): Int = this.value;
                }
                """), "BadHashProgram");

        final var error = assertThrows(ResolutionError.class, compiler::resolve);
        assertTrue(error.getMessage(), error.getMessage().contains("Hashable.hash returns Long"));
    }

    private static java.util.List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}
