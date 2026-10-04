package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.domain.FunctionShapeNames;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public final class FunctionReferenceProjectionTest {
    @Test
    public void parsesAndProjectsMutableFunctionType() {
        final var statements = parse("""
                fn project(operation: &(Int) -> Int): (Int) -> Int = operation;
                """);
        final var resolver = new Resolver();

        resolver.resolve(statements);

        final var function = (Stmt.Function) statements.getFirst();
        final var callableType = TypeDescriptor.functionOf("", TypeDescriptor.ofInt(), TypeDescriptor.ofInt());
        assertEquals(new ReferenceDescriptor(callableType), function.typeDescriptor().parameters().getFirst());
        assertEquals(callableType, function.typeDescriptor().returnType());
    }

    @Test
    public void rejectsReadOnlyFunctionToMutableFunctionConversion() {
        final var statements = parse("""
                fn upgrade(operation: (Int) -> Int): &(Int) -> Int = operation;
                """);

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(statements));
    }

    @Test
    public void compilerKeepsTheSameSamAbiForProjection() throws Exception {
        final var className = "FunctionReferenceProjection" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
            fn project(operation: &(Boolean) -> String): (Boolean) -> String = operation;
                """);
        final var compiler = new Compiler(statements, className);
        compiler.resolve();

        try {
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()},
                    getClass().getClassLoader())) {
                final var callableType = TypeDescriptor.functionOf("", TypeDescriptor.ofString(),
                    TypeDescriptor.ofBoolean());
                final var samType = loader.loadClass(FunctionShapeNames.interfaceName(callableType));
                final var callable = Proxy.newProxyInstance(loader, new Class<?>[]{samType},
                    (_, _, _) -> "callable");
                final var project = loader.loadClass(className).getMethod("project", samType);

                assertSame(callable, project.invoke(null, callable));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}