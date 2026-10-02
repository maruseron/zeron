package com.maruseron.zeron.domain;

import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class FunctionShapeKeyTest {
    @Test
    public void ignoresFunctionNamesAndIsStable() {
        final var first = function("first", TypeDescriptor.ofInt(), TypeDescriptor.ofString());
        final var second = function("second", TypeDescriptor.ofInt(), TypeDescriptor.ofString());

        assertEquals(FunctionShapeKey.of(first), FunctionShapeKey.of(second));
        assertEquals(FunctionShapeNames.interfaceName(first), FunctionShapeNames.interfaceName(second));
        assertFalse(FunctionShapeNames.interfaceName(first).contains("."));
        assertTrue(FunctionShapeKey.of(first).canonicalEncoding().startsWith("zeron-function-shape-v1;"));
    }

    @Test
    public void distinguishesParameterOrderAndReturnType() {
        final var intThenString = function("a", TypeDescriptor.ofBoolean(),
                TypeDescriptor.ofInt(), TypeDescriptor.ofString());
        final var stringThenInt = function("b", TypeDescriptor.ofBoolean(),
                TypeDescriptor.ofString(), TypeDescriptor.ofInt());
        final var differentReturn = function("c", TypeDescriptor.ofString(),
                TypeDescriptor.ofInt(), TypeDescriptor.ofString());

        assertNotEquals(FunctionShapeKey.of(intThenString), FunctionShapeKey.of(stringThenInt));
        assertNotEquals(FunctionShapeKey.of(intThenString), FunctionShapeKey.of(differentReturn));
    }

    @Test
    public void distinguishesNullableReferenceEvenWhenJvmAbiIsErased() {
        final var nonNullable = function("a", TypeDescriptor.ofInt(), TypeDescriptor.ofString());
        final var nullable = function("b", TypeDescriptor.ofInt(), TypeDescriptor.ofString().toNullable());

        assertNotEquals(FunctionShapeKey.of(nonNullable), FunctionShapeKey.of(nullable));
        assertNotEquals(FunctionShapeNames.interfaceName(nonNullable), FunctionShapeNames.interfaceName(nullable));
        assertEquals(methodType(nonNullable), methodType(nullable));
    }

    @Test
    public void distinguishesNullablePrimitiveAndItsBoxedJvmAbi() {
        final var nonNullable = function("a", TypeDescriptor.ofInt(), TypeDescriptor.ofInt());
        final var nullable = function("b", TypeDescriptor.ofInt(), TypeDescriptor.ofInt().toNullable());

        assertNotEquals(FunctionShapeKey.of(nonNullable), FunctionShapeKey.of(nullable));
        assertNotEquals(methodType(nonNullable), methodType(nullable));
    }

    @Test
    public void recursivelyDistinguishesNestedAndGenericTypes() {
        final var nestedA = function("innerA", TypeDescriptor.ofInt(), TypeDescriptor.ofInt());
        final var nestedB = function("innerB", TypeDescriptor.ofFloat(), TypeDescriptor.ofInt());
        final var outerA = function("outerA", nestedA, TypeDescriptor.ofString());
        final var outerB = function("outerB", nestedB, TypeDescriptor.ofString());
        final var listOfInt = TypeDescriptor.genericOf(TypeDescriptor.ofName("List"), TypeDescriptor.ofInt());
        final var listOfString = TypeDescriptor.genericOf(TypeDescriptor.ofName("List"), TypeDescriptor.ofString());

        assertNotEquals(FunctionShapeKey.of(outerA), FunctionShapeKey.of(outerB));
        assertNotEquals(FunctionShapeKey.of(function("listA", TypeDescriptor.ofUnit(), listOfInt)),
                FunctionShapeKey.of(function("listB", TypeDescriptor.ofUnit(), listOfString)));
    }

    @Test
    public void distinguishesNominalIdentity() {
        final var alpha = function("alpha", TypeDescriptor.ofUnit(), TypeDescriptor.ofName("alpha.Widget"));
        final var beta = function("beta", TypeDescriptor.ofUnit(), TypeDescriptor.ofName("beta.Widget"));

        assertNotEquals(FunctionShapeKey.of(alpha), FunctionShapeKey.of(beta));
    }

    @Test
    public void distinguishesAnyFromNominalTypeWithSameName() {
        final var any = function("any", TypeDescriptor.ofAny(), TypeDescriptor.ofAny());
        final var nominal = function("nominal", TypeDescriptor.ofName("Any"), TypeDescriptor.ofName("Any"));

        assertNotEquals(FunctionShapeKey.of(any), FunctionShapeKey.of(nominal));
    }

    @Test
    public void resolvesStandaloneLambdaTypeOnOriginalNode() {
        final var resolver = new Resolver();
        final var statements = Parser.of(Scanner.from("let f = x -> x + 1;").scanTokens()).parse();
        resolver.resolve(statements);

        final var declaration = (Stmt.Var) statements.getFirst();
        final var lambda = (Expr.Lambda) declaration.initializer();
        final var expected = TypeDescriptor.functionOf("", TypeDescriptor.ofInt(), TypeDescriptor.ofInt());

        assertEquals(expected, lambda.getType());
        assertEquals(expected, resolver.symbols.getSymbol(declaration.name()).type());
    }

    @Test
    public void resolvesMultiParameterLambdaTypeOnOriginalNode() {
        final var resolver = new Resolver();
        final var statements = Parser.of(Scanner.from("let pair = (left, right) -> 42;").scanTokens()).parse();
        resolver.resolve(statements);

        final var declaration = (Stmt.Var) statements.getFirst();
        final var lambda = (Expr.Lambda) declaration.initializer();
        final var expected = TypeDescriptor.functionOf("", TypeDescriptor.ofInt(), TypeDescriptor.ofInfer(), TypeDescriptor.ofInfer());

        assertEquals(expected, lambda.getType());
        assertEquals(expected, resolver.symbols.getSymbol(declaration.name()).type());
        assertEquals(2, ((FunctionDescriptor) lambda.getType()).arity());
    }

    @Test
    public void resolvesMultiParameterLambdaBindingAfterCallContext() {
        final var resolver = new Resolver();
        final var statements = Parser.of(Scanner.from("let pair = (left, right) -> left + right; let total = pair(3, 7);").scanTokens()).parse();
        resolver.resolve(statements);

        final var declaration = (Stmt.Var) statements.getFirst();
        final var lambda = (Expr.Lambda) declaration.initializer();
        final var expected = TypeDescriptor.functionOf("", TypeDescriptor.ofInt(), TypeDescriptor.ofInt(), TypeDescriptor.ofInt());

        assertEquals(expected, lambda.getType());
        assertEquals(expected, resolver.symbols.getSymbol(declaration.name()).type());
        assertEquals(2, ((FunctionDescriptor) lambda.getType()).arity());
    }

    @Test
    public void allowsImmutableCaptureInLambda() {
        final var resolver = new Resolver();
        final var statements = Parser.of(Scanner.from("let base = 3; let add = () -> base + 1;").scanTokens()).parse();

        resolver.resolve(statements);

        final var declaration = (Stmt.Var) statements.get(1);
        final var lambda = (Expr.Lambda) declaration.initializer();
        assertEquals(TypeDescriptor.functionOf("", TypeDescriptor.ofInt()), lambda.getType());
    }

    @Test
    public void rejectsMutableCaptureInLambda() {
        final var resolver = new Resolver();
        final var statements = Parser.of(Scanner.from("let mut count = 3; let add = () -> count + 1;").scanTokens()).parse();

        assertThrows(ResolutionError.class, () -> resolver.resolve(statements));
    }

    @Test
    public void rejectsUnresolvedTypes() {
        final var unresolved = function("unresolved", TypeDescriptor.ofInfer(), TypeDescriptor.ofInt());

        assertThrows(IllegalArgumentException.class, () -> FunctionShapeKey.of(unresolved));
    }

    private static FunctionDescriptor function(final String name,
                                               final TypeDescriptor returnType,
                                               final TypeDescriptor... parameterTypes) {
        return TypeDescriptor.functionOf(name, returnType, parameterTypes);
    }

    private static MethodTypeDesc methodType(final FunctionDescriptor functionType) {
        final var parameters = functionType.parameters().stream()
                .map(TypeDescriptor::toJavaClassDesc)
                .toList();
        return MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(functionType.returnType()), parameters);
    }
}