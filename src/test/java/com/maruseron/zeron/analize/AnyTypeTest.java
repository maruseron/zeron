package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public final class AnyTypeTest {
    @Test
    public void widensValuesAndInfersAnyForUnrelatedBranches() {
        final var statements = parse("""
                let integer: Any = 42;
                let text: Any = "text";
                let nullable: Any? = null;
                let maybeInt: Int? = null;
                let nullableValue: Any? = maybeInt;
                let mixed = if (true) then 1 else "text";
                let mixedWithNull = if (true) then null else 1;
                """);
        final var resolver = new Resolver();
        resolver.resolve(statements);

        assertEquals(TypeDescriptor.ofAny(), bindingType(resolver, statements, "integer"));
        assertEquals(TypeDescriptor.ofAny(), bindingType(resolver, statements, "text"));
        assertEquals(TypeDescriptor.ofAny().toNullable(), bindingType(resolver, statements, "nullable"));
        assertEquals(TypeDescriptor.ofAny().toNullable(), bindingType(resolver, statements, "nullableValue"));
        assertEquals(TypeDescriptor.ofAny(), bindingType(resolver, statements, "mixed"));
        assertEquals(TypeDescriptor.ofInt().toNullable(), bindingType(resolver, statements, "mixedWithNull"));
    }

    @Test
    public void rejectsNullToAnyAndImplicitNarrowing() {
        for (final var source : List.of(
                "let value: Any = null;",
                "let value: Any? = null; let narrowed: Int = value;")) {
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
        }
    }

    @Test
    public void reservesAnyAsABuiltInType() {
        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse("""
                class Any {
                    public constructor new;
                }
                """)));
    }

    @Test
    public void widensJvmValuesAndKeepsUnitDistinctFromNull() throws Exception {
        final var className = "AnyValues" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn identity<T>(value: T): T = value;
            let globalUnit: Any = unit;
                fn echoAny(value: Any): Any = value;
                fn anyInt(): Any = 42;
                fn anyIntThroughParameter(): Any = echoAny(43);
                fn anyFloat(): Any = 2.5;
                fn anyBoolean(): Any = true;
                fn anyString(): Any = "value";
                fn anyArray(): Any = [1, 2];
                fn anyLambda(): Any = () -> 7;
                fn anyUnit(): Any = unit;
                fn anyGlobalUnit(): Any = globalUnit;
                fn anyUnitViaGeneric(): Any = identity<Any>(unit);
                fn nullableUnit(value: Unit?): Any? = value;
                fn nullableInt(value: Int?): Any? = value;
                fn anyNull(): Any? = null;
                fn anyNullViaGeneric(): Any? = identity<Any?>(null);
                fn sameUnit(): Boolean {
                    let first: Any = unit;
                    let second: Any = unit;
                    return first == second;
                }
                """);
        final var compiler = new Compiler(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                assertEquals(42, program.getMethod("anyInt").invoke(null));
                assertEquals(43, program.getMethod("anyIntThroughParameter").invoke(null));
                assertEquals(2.5, program.getMethod("anyFloat").invoke(null));
                assertEquals(true, program.getMethod("anyBoolean").invoke(null));
                assertEquals("value", program.getMethod("anyString").invoke(null));
                assertNotNull(program.getMethod("anyArray").invoke(null));
                assertNotNull(program.getMethod("anyLambda").invoke(null));

                final var unit = program.getMethod("anyUnit").invoke(null);
                assertNotNull(unit);
                assertSame(unit, program.getMethod("anyGlobalUnit").invoke(null));
                assertSame(unit, program.getMethod("anyUnitViaGeneric").invoke(null));
                final var unitValueClass = loader.loadClass("com.maruseron.zeron.runtime.UnitValue");
                assertNull(program.getMethod("nullableUnit", unitValueClass).invoke(null, new Object[]{null}));
                assertSame(unit, program.getMethod("nullableUnit", unitValueClass).invoke(null, unit));
                assertNull(program.getMethod("nullableInt", Integer.class).invoke(null, new Object[]{null}));
                assertEquals(12, program.getMethod("nullableInt", Integer.class).invoke(null, 12));
                assertNull(program.getMethod("anyNull").invoke(null));
                assertNull(program.getMethod("anyNullViaGeneric").invoke(null));
                assertEquals(true, program.getMethod("sameUnit").invoke(null));
                assertFalse(unit.equals(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }

    private static TypeDescriptor bindingType(final Resolver resolver,
                                              final List<Stmt> statements,
                                              final String name) {
        final var declaration = statements.stream()
                .filter(Stmt.Var.class::isInstance)
                .map(Stmt.Var.class::cast)
                .filter(variable -> variable.name().lexeme().equals(name))
                .findFirst()
                .orElseThrow();
        return resolver.symbols.getSymbol(declaration.name()).type();
    }
}