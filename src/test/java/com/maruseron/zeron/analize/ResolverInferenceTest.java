package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        let theUnit = ();
        let unit = 42;
        """);
    final var resolver = new ResolutionService();

    final var result = resolver.resolve(statements);

    assertEquals(TypeDescriptor.ofString(), bindingType(result, statements, "s"));
    assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "i"));
    assertEquals(TypeDescriptor.ofFloat(), bindingType(result, statements, "f"));
    assertEquals(TypeDescriptor.ofBoolean(), bindingType(result, statements, "bool"));
    assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "globalCount"));
    assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "unit"));
    assertEquals(TypeDescriptor.ofUnit(), bindingType(result, statements, "theUnit"));
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
                let localDefault: Int? = null;
        """);
    final var resolver = new ResolutionService();

    final var result = resolver.resolve(statements);

    assertEquals(TypeDescriptor.ofInt().toNullable(), bindingType(result, statements, "maybeInt"));
    assertEquals(TypeDescriptor.ofInt().toNullable(), bindingType(result, statements, "emptyInt"));
    assertEquals(TypeDescriptor.ofFloat().toNullable(), bindingType(result, statements, "maybeFloat"));
    assertEquals(TypeDescriptor.ofBoolean().toNullable(), bindingType(result, statements, "maybeBool"));
    assertEquals(TypeDescriptor.ofString().toNullable(), bindingType(result, statements, "maybeText"));
    assertEquals(TypeDescriptor.ofString().toNullable(), bindingType(result, statements, "emptyText"));
    assertEquals(TypeDescriptor.ofInt().toNullable(), bindingType(result, statements, "localDefault"));
    }

    @Test
    public void resolvesNullableCallArgumentsAndResults() {
    final var statements = parse("""
        fn passNullableInt(value: Int?): Int? {
            return value;
        }
        let passed = passNullableInt(null);
        """);
    final var resolver = new ResolutionService();

    final var result = resolver.resolve(statements);

    assertEquals(TypeDescriptor.ofInt().toNullable(), bindingType(result, statements, "passed"));
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
    final var resolver = new ResolutionService();

    final var result = resolver.resolve(statements);

    assertEquals(TypeDescriptor.functionOf("", TypeDescriptor.ofInt(), TypeDescriptor.ofInt()),
        bindingType(result, statements, "addOne"));
    assertEquals(TypeDescriptor.functionOf("", TypeDescriptor.ofInt(),
            TypeDescriptor.ofInt(), TypeDescriptor.ofInt()),
        bindingType(result, statements, "combine"));
    assertEquals(TypeDescriptor.functionOf("", TypeDescriptor.ofInt()),
        bindingType(result, statements, "noParam"));
    assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "combined"));
    assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "incremented"));
    assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "constant"));
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
        final var resolver = new ResolutionService();

        final var result = resolver.resolve(statements);

        assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "appliedInt"));
        assertEquals(TypeDescriptor.ofFloat(), bindingType(result, statements, "appliedFloat"));
        assertEquals(TypeDescriptor.ofString(), bindingType(result, statements, "appliedString"));
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
        final var resolver = new ResolutionService();

        final var result = resolver.resolve(statements);

        assertEquals(TypeDescriptor.functionOf("", TypeDescriptor.ofInt(), TypeDescriptor.ofInt()),
                bindingType(result, statements, "addBase"));
        assertEquals(TypeDescriptor.functionOf("", TypeDescriptor.ofString(), TypeDescriptor.ofString()),
                bindingType(result, statements, "decorate"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "added"));
        assertEquals(TypeDescriptor.ofString(), bindingType(result, statements, "decorated"));
    }

    @Test
    public void rejectsBindingsInferredFromNull() {
        for (final var source : List.of(
                "let value = null;",
                "let value = if (true) then null else null;")) {
            final var resolver = new ResolutionService();
            final var statements = Parser.of(Scanner.from(source).scanTokens()).parse();

            final var error = assertThrows(ResolutionError.class, () -> resolver.resolve(statements));
            assertEquals("Cannot infer a type from a null value.", error.getMessage());
        }
    }

    @Test
    public void formatsTypeMismatchDiagnosticsWithSourceTypeNames() {
        final var compatibility = new TypeCompatibility(Map.of(), Map.of());
        final var typeParameter = new TypeParameterDescriptor(1, "T");
        final var where = new Token(TokenType.IDENTIFIER, "value", null, 1);

        final var error = assertThrows(ResolutionError.class,
                () -> compatibility.ensureAssignable(typeParameter,
                        TypeDescriptor.ofUnit(), where));

        assertEquals("Expected T, found Unit.", error.getMessage());
    }

    @Test
    public void allowsNullInitializerWithNullableType() {
        final var resolver = new ResolutionService();
        final var statements = Parser.of(Scanner.from("let value: Int? = null;").scanTokens()).parse();

        final var result = resolver.resolve(statements);

        assertEquals(TypeDescriptor.ofInt().toNullable(),
                result.globalSymbolTable().getSymbol(((Stmt.Var) statements.getFirst()).name()).type());
    }

    @Test
    public void preservesExplicitReturnTypeForExpressionBody() {
        final var statements = parse("fn count(): Int = 1;");
        final var function = (Stmt.Function) statements.getFirst();

        assertEquals(TypeDescriptor.ofInt(), function.typeDescriptor().returnType());
        new ResolutionService().resolve(statements);
    }

    @Test
    public void keepsResolutionStatePerInvocation() {
        final var resolver = new ResolutionService();
        final var firstStatements = parse("let first = 1;");
        final var secondStatements = parse("let second = 2;");

        final var firstResult = resolver.resolve(firstStatements);
        final var secondResult = resolver.resolve(secondStatements);
        final var firstName = ((Stmt.Var) firstStatements.getFirst()).name();
        final var secondName = ((Stmt.Var) secondStatements.getFirst()).name();

        assertEquals(TypeDescriptor.ofInt(), firstResult.globalSymbolTable().getSymbol(firstName).type());
        assertEquals(TypeDescriptor.ofInt(), secondResult.globalSymbolTable().getSymbol(secondName).type());
        assertFalse(firstResult.globalSymbolTable().containsSymbol(secondName));
        assertFalse(secondResult.globalSymbolTable().containsSymbol(firstName));
    }

    @Test
    public void rejectsExpressionBodyThatViolatesExplicitReturnType() {
        final var statements = parse("fn count(): Int = \"wrong\";");

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(statements));
    }

    @Test
    public void infersReturnTypeWhenExpressionBodyHasNoAnnotation() {
        final var statements = parse("fn count() = 1;");
        final var function = (Stmt.Function) statements.getFirst();
        final var resolver = new ResolutionService();

        final var result = resolver.resolve(statements);

        assertEquals(TypeDescriptor.ofInt(),
            ((FunctionDescriptor) result.globalSymbolTable().getFunction(function.name()).type()).returnType());
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