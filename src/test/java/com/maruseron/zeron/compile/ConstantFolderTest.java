package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ConstantFolderTest {
    @Test
    public void foldsLiteralAndArithmeticExpressions() {
        assertEquals(1, ConstantFolder.fold(expression("true;")));
        assertEquals(3, ConstantFolder.fold(expression("1 + 2;")));
        assertEquals(-3, ConstantFolder.fold(expression("-3;")));
        assertEquals(-2.5, ConstantFolder.fold(expression("-2.5;")));
        assertEquals(3, ConstantFolder.fold(expression("+3;")));
        assertEquals(6.0, ConstantFolder.fold(expression("2.0 * 3.0;")));
        assertEquals("ab", ConstantFolder.fold(expression("\"a\" + \"b\";")));
        assertNull(ConstantFolder.fold(expression("value;")));
    }

    @Test
    public void classifiesFoldedFieldValuesAndTypes() {
        assertTrue(ConstantFolder.isConstantFieldValue(TypeDescriptor.ofInt(), 3));
        assertTrue(ConstantFolder.isConstantFieldValue(TypeDescriptor.ofString(), "text"));
        assertFalse(ConstantFolder.isConstantFieldValue(TypeDescriptor.ofInt(), null));
        assertEquals(TypeDescriptor.ofInt(), ConstantFolder.typeForConstant(3));
        assertEquals(TypeDescriptor.ofString(), ConstantFolder.typeForConstant("text"));
    }

    private static Expr expression(final String source) {
        final var statements = Parser.of(Scanner.from("let result = " + source).scanTokens()).parse();
        return ((Stmt.Var) statements.getFirst()).initializer();
    }
}