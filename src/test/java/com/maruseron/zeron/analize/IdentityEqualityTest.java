package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.TokenType;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public final class IdentityEqualityTest {
    @Test
    public void scansAndParsesIdentityEqualityAtEqualityPrecedence() {
        final var tokens = Scanner.from("left === right").scanTokens();
        assertEquals(List.of(TokenType.IDENTIFIER, TokenType.EQUAL_EQUAL_EQUAL,
                        TokenType.IDENTIFIER, TokenType.EOF),
                tokens.stream().map(token -> token.type()).toList());

        final var statements = parse("let same = left == middle === right;");
        final var comparison = (Expr.Binary) ((Stmt.Var) statements.getFirst()).initializer();
        assertEquals(TokenType.EQUAL_EQUAL_EQUAL, comparison.operator.type());
        assertEquals(Expr.Binary.class, comparison.left.getClass());
    }

    @Test
    public void checksObjectIdentityWithoutChangingValueEquality() throws Exception {
        final var className = "IdentityEquality" + UUID.randomUUID().toString().replace("-", "");
        final var boxName = "IdentityBox" + UUID.randomUUID().toString().replace("-", "");
        final var contractName = "IdentityView" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var boxFile = Path.of("dist", boxName + ".class");
        final var source = """
                contract %s {
                    read(): Int;
                }
                class %s is %s {
                    value: Int;
                    public constructor new;
                    public read(): Int = this.value;
                }
                fn sameBox(): Boolean {
                    let value = %s.new(1);
                    return value === value;
                }
                fn distinctBoxes(): Boolean = %s.new(1) === %s.new(1);
                fn sameThroughContractAndAny(): Boolean {
                    let value = %s.new(1);
                    let view: %s = value;
                    let erased: Any = value;
                    return view === erased;
                }
                fn sameString(left: String, right: String): Boolean = left === right;
                fn equalStrings(left: String, right: String): Boolean = left == right;
                fn sameArray(left: Array<Int>, right: Array<Int>): Boolean = left === right;
                fn nullableIdentity(left: String?, right: String?): Boolean = left === right;
                fn nullIdentity(value: String?): Boolean = value === null;
                fn refined(value: String?): String {
                    if (value === null) return "null";
                    return value;
                }
                """.formatted(contractName, boxName, contractName, boxName, boxName,
                        boxName, boxName, contractName);

        try {
            final var compiler = new CompilationService(parse(source), className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertTrue((Boolean) generated.getMethod("sameBox").invoke(null));
                assertFalse((Boolean) generated.getMethod("distinctBoxes").invoke(null));
                assertTrue((Boolean) generated.getMethod("sameThroughContractAndAny").invoke(null));

                final var equalDistinctStrings = new String("same");
                final var anotherEqualString = new String("same");
                assertFalse((Boolean) generated.getMethod("sameString", String.class, String.class)
                        .invoke(null, equalDistinctStrings, anotherEqualString));
                assertTrue((Boolean) generated.getMethod("sameString", String.class, String.class)
                        .invoke(null, equalDistinctStrings, equalDistinctStrings));
                assertTrue((Boolean) generated.getMethod("equalStrings", String.class, String.class)
                        .invoke(null, equalDistinctStrings, anotherEqualString));
                final var array = new Object[]{1};
                final var anotherArray = new Object[]{1};
                assertTrue((Boolean) generated.getMethod("sameArray", Object[].class, Object[].class)
                        .invoke(null, array, array));
                assertFalse((Boolean) generated.getMethod("sameArray", Object[].class, Object[].class)
                        .invoke(null, array, anotherArray));
                assertTrue((Boolean) generated.getMethod("nullableIdentity", String.class, String.class)
                        .invoke(null, new Object[]{null, null}));
                assertFalse((Boolean) generated.getMethod("nullableIdentity", String.class, String.class)
                        .invoke(null, equalDistinctStrings, anotherEqualString));
                assertTrue((Boolean) generated.getMethod("nullIdentity", String.class)
                        .invoke(null, new Object[]{null}));
                assertFalse((Boolean) generated.getMethod("nullIdentity", String.class)
                        .invoke(null, equalDistinctStrings));
                assertEquals("null", generated.getMethod("refined", String.class)
                        .invoke(null, new Object[]{null}));
                assertEquals("present", generated.getMethod("refined", String.class)
                        .invoke(null, "present"));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(boxFile);
        }
    }

    @Test
    public void rejectsUnsupportedOrIncompatibleIdentityOperands() {
        for (final var source : List.of(
                "fn invalid(): Boolean = 1 === 1;",
                "fn invalid(left: Int?, right: Int?): Boolean = left === right;",
                "fn invalid(left: Unit, right: Unit): Boolean = left === right;",
                "fn invalid(): Boolean = null === null;",
                """
                class Left { value: Int; public constructor new; }
                class Right { value: Int; public constructor new; }
                fn invalid(left: Left, right: Right): Boolean = left === right;
                """,
                "fn invalid<T>(left: T, right: T): Boolean = left === right;")) {
            assertThrows(source, ResolutionError.class,
                    () -> new ResolutionService().resolve(parse(source)));
        }
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}
