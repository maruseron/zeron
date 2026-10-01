package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class ResolverInferenceTest {
    @Test
    public void infersPrimitiveAndUnitBindingsFromSample() {
    final var statements = parse("""
        let s = "hello " + "world";
        let i = 5 + 1;
        let f = 5.3;
        let bool = true;
        let mut globalCount = 1;
        let theUnit = unit;
        """);
    final var resolver = new Resolver();

    resolver.resolve(statements);

    assertEquals(TypeDescriptor.ofString(), bindingType(resolver, statements, "s"));
    assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "i"));
    assertEquals(TypeDescriptor.ofFloat(), bindingType(resolver, statements, "f"));
    assertEquals(TypeDescriptor.ofBoolean(), bindingType(resolver, statements, "bool"));
    assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "globalCount"));
    assertEquals(TypeDescriptor.ofUnit(), bindingType(resolver, statements, "theUnit"));
    }

    @Test
    public void resolvesNullableBindingsInitializedWithValuesAndNull() {
    final var statements = parse("""
        let maybeInt: Int? = 7;
        let emptyInt: Int? = null;
        let maybeFloat: Float? = 2.5;
        let maybeBool: Boolean? = true;
        let maybeText: String? = "present";
        let emptyText: String? = null;
                let localDefault: Int?;
        """);
    final var resolver = new Resolver();

    resolver.resolve(statements);

    assertEquals(TypeDescriptor.ofInt().toNullable(), bindingType(resolver, statements, "maybeInt"));
    assertEquals(TypeDescriptor.ofInt().toNullable(), bindingType(resolver, statements, "emptyInt"));
    assertEquals(TypeDescriptor.ofFloat().toNullable(), bindingType(resolver, statements, "maybeFloat"));
    assertEquals(TypeDescriptor.ofBoolean().toNullable(), bindingType(resolver, statements, "maybeBool"));
    assertEquals(TypeDescriptor.ofString().toNullable(), bindingType(resolver, statements, "maybeText"));
    assertEquals(TypeDescriptor.ofString().toNullable(), bindingType(resolver, statements, "emptyText"));
    assertEquals(TypeDescriptor.ofInt().toNullable(), bindingType(resolver, statements, "localDefault"));
    }

    @Test
    public void resolvesNullableCallArgumentsAndResults() {
    final var statements = parse("""
        fn passNullableInt(value: Int?): Int? {
            return value;
        }
        let passed = passNullableInt(null);
        """);
    final var resolver = new Resolver();

    resolver.resolve(statements);

    assertEquals(TypeDescriptor.ofInt().toNullable(), bindingType(resolver, statements, "passed"));
    }

    @Test
    public void infersLambdaBindingsFromSampleCalls() {
    final var statements = parse("""
        let addOne = x -> x + 1;
        let combine = (left, right) -> left + right;
        let noParam = () -> 99;
        let combined = combine(3, 7);
        let incremented = addOne(10);
        let constant = noParam();
        """);
    final var resolver = new Resolver();

    resolver.resolve(statements);

    assertEquals(TypeDescriptor.functionOf("", TypeDescriptor.ofInt(), TypeDescriptor.ofInt()),
        bindingType(resolver, statements, "addOne"));
    assertEquals(TypeDescriptor.functionOf("", TypeDescriptor.ofInt(),
            TypeDescriptor.ofInt(), TypeDescriptor.ofInt()),
        bindingType(resolver, statements, "combine"));
    assertEquals(TypeDescriptor.functionOf("", TypeDescriptor.ofInt()),
        bindingType(resolver, statements, "noParam"));
    assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "combined"));
    assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "incremented"));
    assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "constant"));
    }

    @Test
    public void infersInlineLambdasFromFunctionParameterTypes() {
        final var statements = parse("""
                fn apply(value: Int, operation: (Int) -> Int): Int {
                    return operation(value);
                }
                fn applyFloat(value: Float, operation: (Float) -> Float): Float {
                    return operation(value);
                }
                fn applyString(value: String, operation: (String) -> String): String {
                    return operation(value);
                }
                let appliedInt = apply(21, y -> y * 2);
                let appliedFloat = applyFloat(2.5, number -> number * 2.0);
                let appliedString = applyString("zeron", text -> text + "!");
                """);
        final var resolver = new Resolver();

        resolver.resolve(statements);

        assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "appliedInt"));
        assertEquals(TypeDescriptor.ofFloat(), bindingType(resolver, statements, "appliedFloat"));
        assertEquals(TypeDescriptor.ofString(), bindingType(resolver, statements, "appliedString"));
    }

    @Test
    public void infersCapturedLambdaBindingsFromSample() {
        final var statements = parse("""
                let base = 7;
                let addBase = value -> value + base;
                let greeting = "hello";
                let decorate = text -> greeting + " " + text;
                let added = addBase(5);
                let decorated = decorate("world");
                """);
        final var resolver = new Resolver();

        resolver.resolve(statements);

        assertEquals(TypeDescriptor.functionOf("", TypeDescriptor.ofInt(), TypeDescriptor.ofInt()),
                bindingType(resolver, statements, "addBase"));
        assertEquals(TypeDescriptor.functionOf("", TypeDescriptor.ofString(), TypeDescriptor.ofString()),
                bindingType(resolver, statements, "decorate"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "added"));
        assertEquals(TypeDescriptor.ofString(), bindingType(resolver, statements, "decorated"));
    }

    @Test
    public void rejectsBindingsInferredFromNull() {
        for (final var source : List.of(
                "let value = null;",
                "let value = if (true) then null else null;")) {
            final var resolver = new Resolver();
            final var statements = Parser.of(Scanner.from(source).scanTokens()).parse();

            final var error = assertThrows(ResolutionError.class, () -> resolver.resolve(statements));
            assertEquals("Cannot infer a type from a null value; add an explicit nullable type annotation.",
                    error.getMessage());
        }
    }

    @Test
    public void allowsNullInitializerWithNullableType() {
        final var resolver = new Resolver();
        final var statements = Parser.of(Scanner.from("let value: Int? = null;").scanTokens()).parse();

        resolver.resolve(statements);

        assertEquals(TypeDescriptor.ofInt().toNullable(),
                resolver.symbols.getSymbol(((Stmt.Var) statements.getFirst()).name()).type());
    }

    @Test
    public void preservesExplicitReturnTypeForExpressionBody() {
        final var statements = parse("fn count(): Int = 1;");
        final var function = (Stmt.Function) statements.getFirst();

        assertEquals(TypeDescriptor.ofInt(), function.typeDescriptor().returnType());
        new Resolver().resolve(statements);
    }

    @Test
    public void rejectsExpressionBodyThatViolatesExplicitReturnType() {
        final var statements = parse("fn count(): Int = \"wrong\";");

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(statements));
    }

    @Test
    public void infersReturnTypeWhenExpressionBodyHasNoAnnotation() {
        final var statements = parse("fn count() = 1;");
        final var function = (Stmt.Function) statements.getFirst();
        final var resolver = new Resolver();

        resolver.resolve(statements);

        assertEquals(TypeDescriptor.ofInt(),
            ((FunctionDescriptor) resolver.symbols.getFunction(function.name()).type()).returnType());
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