package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.IntrinsicDefinition;
import com.maruseron.zeron.domain.IntrinsicId;
import com.maruseron.zeron.domain.IntrinsicRegistry;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ArrayViewTest {
    @Test
    public void projectsMutableArrayViewToReadOnly() {
        final var statements = parse("""
                fn first(values: Array<Int>): Int = values[0];
                let values: &Array<Int> = [1, 2, 3];
                let readonly: Array<Int> = values;
                let firstValue = first(values);
                let size = readonly.length;
                """);
        final var resolver = new ResolutionService();

        final var result = resolver.resolve(statements);

        assertEquals(new ReferenceDescriptor(TypeDescriptor.arrayOf(TypeDescriptor.ofInt())),
                bindingType(result, statements, "values"));
        assertEquals(TypeDescriptor.arrayOf(TypeDescriptor.ofInt()),
                bindingType(result, statements, "readonly"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "firstValue"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "size"));
    }

    @Test
    public void resolvesContextualEmptyArrayLiteralsAndRejectsUncontextualOnes() {
        final var statements = parse("""
                let values: &Array<Int> = [];
                fn length(values: Array<Int>): Int = values.length;
                fn fromArgument(): Int = length([]);
                """);
        new ResolutionService().resolve(statements);

        final var declaration = (Stmt.Var) statements.getFirst();
        final var literal = (Expr.ArrayLiteral) declaration.initializer();
        assertEquals(new ReferenceDescriptor(TypeDescriptor.arrayOf(TypeDescriptor.ofInt())),
                literal.getType());
        assertEquals(IntrinsicId.ARRAY_LITERAL, literal.intrinsicOperation().id());
        assertTrue(literal.intrinsicOperation().parameterTypes().isEmpty());

        final var fromArgument = (Stmt.Function) statements.get(2);
        final var call = (Expr.Call) ((Stmt.Return) fromArgument.body().getFirst()).value();
        final var argument = (Expr.ArrayLiteral) call.arguments.getFirst();
        assertEquals(new ReferenceDescriptor(TypeDescriptor.arrayOf(TypeDescriptor.ofInt())),
                argument.getType());

        assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolve(parse("let values = [];")));
    }

        @Test
        public void resolvesArraySyntaxToStableTypedIntrinsicOperations() {
        final var statements = parse("""
            let values = [1, 2];
            fn first(values: Array<Int>): Int { return values[0]; }
            fn replace(values: &Array<Int>): Unit { values[0] = 3; }
            fn size(values: Array<Int>): Int { return values.length; }
            """);
        final var resolver = new ResolutionService();
        resolver.resolve(statements);

        final var literal = (Expr.ArrayLiteral) ((Stmt.Var) statements.getFirst()).initializer();
        final var literalOperation = literal.intrinsicOperation();
        assertEquals(IntrinsicId.ARRAY_LITERAL, literalOperation.id());
        assertEquals("zeron.array.literal.v1", literalOperation.id().stableName());
        assertEquals(TypeDescriptor.ofInt(), literalOperation.parameterTypes().getFirst());
        assertEquals(literal.getType(), literalOperation.resultType());

        final var read = (Expr.Index) ((Stmt.Return) ((Stmt.Function) statements.get(1)).body().getFirst()).value();
        assertEquals(IntrinsicId.ARRAY_READ, read.intrinsicOperation().id());
        assertEquals(2, read.intrinsicOperation().parameterTypes().size());
        assertEquals(TypeDescriptor.ofInt(), read.intrinsicOperation().resultType());

        final var write = (Expr.IndexAssignment)
            ((Stmt.Expression) ((Stmt.Function) statements.get(2)).body().getFirst()).expression();
        assertEquals(IntrinsicId.ARRAY_WRITE, write.intrinsicOperation().id());
        assertEquals(TypeDescriptor.ofInt(), write.intrinsicOperation().parameterTypes().get(2));
        assertEquals(TypeDescriptor.ofUnit(), write.intrinsicOperation().resultType());

        final var length = (Expr.Property)
            ((Stmt.Return) ((Stmt.Function) statements.get(3)).body().getFirst()).value();
        assertEquals(IntrinsicId.ARRAY_LENGTH, length.intrinsicOperation().id());
        assertEquals(TypeDescriptor.ofInt(), length.intrinsicOperation().resultType());
        }

        @Test
        public void rejectsDuplicateIntrinsicIdsAndPropertyBindings() {
        final var registry = IntrinsicRegistry.standard();
        final var length = registry.require(IntrinsicId.ARRAY_LENGTH);
        final var read = registry.require(IntrinsicId.ARRAY_READ);

        assertThrows(IllegalArgumentException.class,
            () -> IntrinsicRegistry.of(List.of(length, length)));
        assertThrows(IllegalArgumentException.class,
            () -> IntrinsicRegistry.of(List.of(
                new IntrinsicDefinition(length.id(), length.signature(), "size"),
                new IntrinsicDefinition(read.id(), read.signature(), "size"))));
        }

    @Test
    public void rejectsWritingThroughReadOnlyArrayAndUpgradingItsView() {
        for (final var source : List.of(
            "fn test() { let values: Array<Int> = [1]; values[0] = 2; }",
            "fn test() { let values: Array<Int> = [1]; let writable: &Array<Int> = values; }",
                "fn test() { let mut values: Array<Int> = [1]; values[0] = 2; }",
                "fn test() { let values: &Array<Int> = [1]; values[0] = \"wrong\"; }",
                "fn test() { let values: &Array<Int> = [1]; let wrong: Array<String> = values; }")) {
            assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
        }
    }

    @Test
    public void writesThroughMutableViewAndCompilerExecutesArrayOperations() throws Exception {
        final var className = "ArrayViewGenerated" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn arrayRead(index: Int): Int {
                    let values: &Array<Int> = [1, 2];
                    let readonly: Array<Int> = values;
                    values[1] = 8;
                    return readonly[index] + readonly.length;
                }
                fn nullableRead(): Int? {
                    let values: &Array<Int?> = [1, null];
                    return values[1];
                }
                fn floatRead(): Float {
                    let values: &Array<Float> = [1.5, 2.5];
                    return values[1];
                }
                fn booleanRead(): Boolean {
                    let values: &Array<Boolean> = [true, false];
                    return values[1];
                }
                """);

        try {
            final var compiler = new CompilationService(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()},
                    getClass().getClassLoader())) {
                final var method = loader.loadClass(className).getMethod("arrayRead", int.class);
                assertEquals(10, method.invoke(null, 1));
                final var failure = assertThrows(InvocationTargetException.class,
                        () -> method.invoke(null, 2));
                assertTrue(failure.getCause() instanceof IndexOutOfBoundsException);
                assertNull(loader.loadClass(className).getMethod("nullableRead").invoke(null));
                assertEquals(2.5, loader.loadClass(className).getMethod("floatRead").invoke(null));
                assertEquals(false, loader.loadClass(className).getMethod("booleanRead").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }

    private static TypeDescriptor bindingType(final ResolutionResult result,
                                              final List<Stmt> statements,
                                              final String name) {
        final var declaration = statements.stream()
                .filter(Stmt.Var.class::isInstance)
                .map(Stmt.Var.class::cast)
                .filter(variable -> variable.name().lexeme().equals(name))
                .findFirst()
                .orElseThrow();
        return result.globalSymbolTable().getSymbol(declaration.name()).type();
    }
}