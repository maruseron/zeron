package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public final class FlowTypingTest {
    @Test
    public void compilesIfExpressionsWithBranchFlowAndCommonTypes() throws Exception {
        final var className = "IfExpressionFlow" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn incrementNullable(value: Int?): Int =
                    if (value != null) then value + 1 else 0;
                fn preserveNullable(value: Int?): Int? =
                    if (value != null) then value else null;
                fn incrementAny(value: Any?): Int =
                    if (value is Int) then value + 2 else 0;
                fn incrementGuard(value: Any?): Int =
                    if (value != null and value is Int) then value + 3 else 0;
                fn chooseAny(flag: Boolean): Any = if (flag) then 42 else "text";
                fn nested(flag: Boolean): Int =
                    if (flag) then if (false) then 1 else 2 else 3;
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(5, generated.getMethod("incrementNullable", Integer.class).invoke(null, 4));
                assertEquals(0, generated.getMethod("incrementNullable", Integer.class).invoke(null, new Object[]{null}));
                assertEquals(4, generated.getMethod("preserveNullable", Integer.class).invoke(null, 4));
                assertNull(generated.getMethod("preserveNullable", Integer.class).invoke(null, new Object[]{null}));
                assertEquals(6, generated.getMethod("incrementAny", Object.class).invoke(null, 4));
                assertEquals(0, generated.getMethod("incrementAny", Object.class).invoke(null, "text"));
                assertEquals(7, generated.getMethod("incrementGuard", Object.class).invoke(null, 4));
                assertEquals(0, generated.getMethod("incrementGuard", Object.class).invoke(null, new Object[]{null}));
                assertEquals(42, generated.getMethod("chooseAny", boolean.class).invoke(null, true));
                assertEquals("text", generated.getMethod("chooseAny", boolean.class).invoke(null, false));
                assertEquals(2, generated.getMethod("nested", boolean.class).invoke(null, true));
                assertEquals(3, generated.getMethod("nested", boolean.class).invoke(null, false));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse("""
                fn invalid(value: Int?): Int {
                    let result = if (value != null) then 1 else 0;
                    return value + 1;
                }
                """)));
    }

    @Test
    public void refinesNullableAndAnyValuesAcrossBranches() throws Exception {
        final var className = "FlowNullTypes" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn increment(value: Int?): Int {
                    if (value == null) return 0;
                    return value + 1;
                }
                fn incrementAny(value: Any?): Int {
                    if (value is Int) return value + 1;
                    return 0;
                }
                fn incrementUnlessInt(value: Any?): Int {
                    if (value is not Int) return 0;
                    return value + 1;
                }
                fn isNotInt(value: Any?): Boolean = value is not Int;
                fn incrementGuard(value: Any?): Int {
                    if (value != null and value is Int) return value + 2;
                    return 0;
                }
                fn incrementNotNull(value: Int?): Int {
                    if (not (value == null)) return value + 3;
                    return 0;
                }
                fn incrementAfterBooleanValue(value: Int?): Int {
                    if (value != null) {
                        let flag = true and true;
                        return value + 4;
                    }
                    return 0;
                }
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(0, generated.getMethod("increment", Integer.class).invoke(null, new Object[]{null}));
                assertEquals(5, generated.getMethod("increment", Integer.class).invoke(null, 4));
                assertEquals(0, generated.getMethod("incrementAny", Object.class).invoke(null, "text"));
                assertEquals(5, generated.getMethod("incrementAny", Object.class).invoke(null, 4));
                assertEquals(5, generated.getMethod("incrementUnlessInt", Object.class).invoke(null, 4));
                assertEquals(0, generated.getMethod("incrementUnlessInt", Object.class).invoke(null, "text"));
                assertEquals(false, generated.getMethod("isNotInt", Object.class).invoke(null, 4));
                assertEquals(true, generated.getMethod("isNotInt", Object.class).invoke(null, "text"));
                assertEquals(true, generated.getMethod("isNotInt", Object.class).invoke(null, new Object[]{null}));
                assertEquals(6, generated.getMethod("incrementGuard", Object.class).invoke(null, 4));
                assertEquals(7, generated.getMethod("incrementNotNull", Integer.class).invoke(null, 4));
                assertEquals(8, generated.getMethod("incrementAfterBooleanValue", Integer.class).invoke(null, 4));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void carriesFlowFactsAcrossLoopExitsAndNestedBreaks() throws Exception {
        final var className = "LoopFlowEdges" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn whileExit(value: Int?): Int {
                    while (value == null) {}
                    return value + 1;
                }
                fn untilExit(value: Int?): Int {
                    until (value != null) {}
                    return value + 2;
                }
                fn continueExit(value: Int?): Int {
                    while (value == null) {
                        continue;
                    }
                    return value + 3;
                }
                fn nestedBreakExit(value: Int?): Int {
                    loop {
                        loop { break; }
                        if (value != null) break;
                        return 0;
                    }
                    return value + 4;
                }
                fn keepsOuterFactAcrossShadow(value: Int?, flag: Boolean): Int {
                    let mut current: Int? = value;
                    if (current != null) {
                        while (flag) {
                            {
                                let mut current: Int? = null;
                                current = 1;
                            }
                        }
                        return current + 5;
                    }
                    return 0;
                }
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(7, generated.getMethod("whileExit", Integer.class).invoke(null, 6));
                assertEquals(8, generated.getMethod("untilExit", Integer.class).invoke(null, 6));
                assertEquals(9, generated.getMethod("continueExit", Integer.class).invoke(null, 6));
                assertEquals(10, generated.getMethod("nestedBreakExit", Integer.class).invoke(null, 6));
                assertEquals(11, generated.getMethod("keepsOuterFactAcrossShadow", Integer.class, boolean.class)
                    .invoke(null, 6, false));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsLoopFactsThatAreNotValidOnEveryExit() {
        for (final var source : List.of(
                "fn invalid(value: Int?): Int { loop { if (value != null) break; break; } return value + 1; }",
            "fn invalid(flag: Boolean): Unit { let mut current: Any? = 1; "
                + "if (current is Int) { while (flag) { let next = current + 1; "
                + "current = \"text\"; } } }",
                "fn invalid(values: Array<Int>): Int { let mut current: Int? = null; "
                        + "for (let value in values) { current = value; } return current + 1; }")) {
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
        }
    }

    @Test
    public void fixedPointRefinesLoopExitAfterEstablishingNonNullValue() throws Exception {
        final var className = "LoopFixedPoint" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn increment(value: Int?): Int {
                    let mut current: Int? = value;
                    while (current == null) {
                        current = 1;
                    }
                    return current + 1;
                }
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(2, generated.getMethod("increment", Integer.class).invoke(null, new Object[]{null}));
                assertEquals(5, generated.getMethod("increment", Integer.class).invoke(null, 4));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void joinsIsOrAlternativesThroughTheirSharedContract() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "FlowAlternatives" + suffix;
        final var programFile = Path.of("dist", className + ".class");
        final var firstFile = Path.of("dist", "FirstNamed" + suffix + ".class");
        final var secondFile = Path.of("dist", "SecondNamed" + suffix + ".class");
        final var contractFile = Path.of("dist", "FlowNamed" + suffix + ".class");
        final var statements = parse("""
                public contract FlowNamed%s {
                    name(): String;
                }
                public class FirstNamed%s is FlowNamed%s {
                    value: String;
                    public constructor new;
                    public name(): String = this.value;
                }
                public class SecondNamed%s is FlowNamed%s {
                    value: String;
                    public constructor new;
                    public name(): String = this.value;
                }
                fn readName(value: Any?): String {
                    if (value is FirstNamed%s or value is SecondNamed%s) {
                        return value.name();
                    }
                    return "unknown";
                }
                """.formatted(suffix, suffix, suffix, suffix, suffix, suffix, suffix));

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                final var first = loader.loadClass("FirstNamed" + suffix)
                        .getConstructor(String.class).newInstance("Ada");
                final var second = loader.loadClass("SecondNamed" + suffix)
                        .getConstructor(String.class).newInstance("Grace");
                assertEquals("Ada", generated.getMethod("readName", Object.class).invoke(null, first));
                assertEquals("Grace", generated.getMethod("readName", Object.class).invoke(null, second));
                assertEquals("unknown", generated.getMethod("readName", Object.class).invoke(null, 42));
            }
        } finally {
            Files.deleteIfExists(programFile);
            Files.deleteIfExists(firstFile);
            Files.deleteIfExists(secondFile);
            Files.deleteIfExists(contractFile);
        }
    }

    @Test
    public void rejectsRefinementAfterReassignmentOrShadowing() {
        for (final var source : List.of(
                "fn invalid(value: Int?) { let mut current: Int? = value; "
                        + "if (current != null) { current = null; print(current + 1); } }",
                "fn invalid(value: Int?) { if (value != null) { "
                + "let value: Int? = null; print(value + 1); } }",
            "let mut shared: Int? = null; "
                + "fn invalid() { if (shared != null) print(shared + 1); }")) {
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
        }
    }

    @Test
    public void checkedAndSafeCastsHaveTheirSpecifiedRuntimeBehavior() throws Exception {
        final var className = "FlowCasts" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn checkedInt(value: Any): Int = value as Int;
            fn checkedFloat(value: Any): Float = value as Float;
            fn checkedBoolean(value: Any): Boolean = value as Boolean;
            fn checkedString(value: Any): String = value as String;
            fn checkedUnit(value: Any): Unit = value as Unit;
                fn safeInt(value: Any?): Int? = value as? Int;
            fn safeFloat(value: Any?): Float? = value as? Float;
            fn safeBoolean(value: Any?): Boolean? = value as? Boolean;
            fn safeString(value: Any?): String? = value as? String;
                fn checkedAfterTest(value: Any?): Int {
                    if (value is Int) return value as Int;
                    return 0;
                }
                fn safeUnit(value: Any?): Unit? = value as? Unit;
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                final var unitValueClass = loader.loadClass("com.maruseron.zeron.runtime.UnitValue");
                final var unitValue = unitValueClass.getField("INSTANCE").get(null);
                final var targetNames = List.of("Int", "Float", "Boolean", "String", "Unit");
                final Object[] runtimeValues = {42, 2.5d, true, "text", unitValue};
                for (int target = 0; target < targetNames.size(); target++) {
                    final var targetName = targetNames.get(target);
                    final var checked = generated.getMethod("checked" + targetName, Object.class);
                    final var safe = generated.getMethod("safe" + targetName, Object.class);
                    for (int source = 0; source < runtimeValues.length; source++) {
                        final var sourceIndex = source;
                        if (source == target) {
                            assertEquals(runtimeValues[target], checked.invoke(null, runtimeValues[source]));
                            assertEquals(runtimeValues[target], safe.invoke(null, runtimeValues[source]));
                        } else {
                            final var castFailure = assertThrows(InvocationTargetException.class,
                                    () -> checked.invoke(null, runtimeValues[sourceIndex]));
                            assertEquals(ClassCastException.class, castFailure.getCause().getClass());
                            assertNull(safe.invoke(null, runtimeValues[source]));
                        }
                    }
                    assertNull(safe.invoke(null, new Object[]{null}));
                }
                assertEquals(42, generated.getMethod("checkedAfterTest", Object.class).invoke(null, 42));
                assertEquals(0, generated.getMethod("checkedAfterTest", Object.class).invoke(null, "text"));
                assertSame(unitValue, generated.getMethod("safeUnit", Object.class).invoke(null, unitValue));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void checkedNullableCastNeedsProofAndCastsDoNotRefineTheirSource() {
        for (final var source : List.of(
                "fn invalid(value: Int?): Int = value as Int;",
                "fn invalid(value: Int?): Int { let converted = value as? Int; return value + 1; }")) {
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
        }
    }

    @Test
    public void rejectsCastsToNonReifiableTypes() {
        for (final var source : List.of(
                "fn invalid(value: Any): Array<Int> = value as Array<Int>;",
                "class Box<T> { value: T; } fn invalid(value: Any): Box<Int> = value as Box<Int>;",
                "fn invalid(value: Any): (Int) -> Int = value as (Int) -> Int;",
                "fn invalid<T>(value: Any): T = value as T;")) {
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
        }
    }

    @Test
    public void checksClassAndContractCastPairsAtRuntime() throws Exception {
        final var className = "FlowNominalCasts" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var personFile = Path.of("dist", "Person.class");
        final var contractFile = Path.of("dist", "Named.class");
        final var statements = parse("""
                public contract Named {
                    name(): String;
                }
                public class Person is Named {
                    value: String;
                    public constructor new;
                    public name(): String = this.value;
                }
                fn makePerson(): Person = Person.new("Ada");
                fn checkedPerson(value: Any): Person = value as Person;
                fn safePerson(value: Any?): Person? = value as? Person;
                fn checkedNamed(value: Any): Named = value as Named;
                fn safeNamed(value: Any?): Named? = value as? Named;
                fn personFromNamed(value: Named): Person = value as Person;
                fn nameOrFallback(value: Any?): String {
                    let named = value as? Named;
                    if (named != null) return named.name();
                    return "fallback";
                }
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                final var person = generated.getMethod("makePerson").invoke(null);
                assertSame(person, generated.getMethod("checkedPerson", Object.class).invoke(null, person));
                assertSame(person, generated.getMethod("safePerson", Object.class).invoke(null, person));
                assertNull(generated.getMethod("safePerson", Object.class).invoke(null, "text"));
                assertNull(generated.getMethod("safePerson", Object.class).invoke(null, new Object[]{null}));
                assertSame(person, generated.getMethod("checkedNamed", Object.class).invoke(null, person));
                final var named = generated.getMethod("safeNamed", Object.class).invoke(null, person);
                assertSame(person, generated.getMethod("personFromNamed", named.getClass().getInterfaces()[0])
                        .invoke(null, named));
                assertNull(generated.getMethod("safeNamed", Object.class).invoke(null, "text"));
                assertEquals("Ada", generated.getMethod("nameOrFallback", Object.class).invoke(null, person));
                assertEquals("fallback", generated.getMethod("nameOrFallback", Object.class).invoke(null, "text"));

                for (final var methodName : List.of("checkedPerson", "checkedNamed")) {
                    final var failure = assertThrows(InvocationTargetException.class,
                            () -> generated.getMethod(methodName, Object.class).invoke(null, "text"));
                    assertEquals(ClassCastException.class, failure.getCause().getClass());
                }
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(personFile);
            Files.deleteIfExists(contractFile);
        }
    }

    @Test
    public void shortCircuitsLogicalOperands() throws Exception {
        final var className = "FlowShortCircuit" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                let mut calls = 0;
                fn mark(): Boolean {
                    calls += 1;
                    return true;
                }
                fn test(): Int {
                    calls = 0;
                    let andResult = false and mark();
                    let orResult = true or mark();
                    return calls;
                }
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(0, loader.loadClass(className).getMethod("test").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    private static List<Stmt> parse(final String source) {
        final var statements = Parser.of(Scanner.from(source).scanTokens()).parse();
        for (final var statement : statements) {
            final var nullPath = nullStatementPath(statement, "program");
            if (nullPath != null) {
                throw new AssertionError("Parser recovered with a null statement at " + nullPath
                        + " for source:\n" + source);
            }
        }
        return statements;
    }

    private static String nullStatementPath(final Stmt statement, final String path) {
        if (statement == null) return path;
        return switch (statement) {
            case Stmt.Function function -> findNullInStatements(function.body(),
                    path + ".fn " + function.name().lexeme());
            case Stmt.Block block -> findNullInStatements(block.statements(), path + ".block");
            case Stmt.If iff -> {
                final var thenPath = nullStatementPath(iff.thenBranch(), path + ".then");
                yield thenPath != null ? thenPath
                        : iff.elseBranch() == null ? null
                        : nullStatementPath(iff.elseBranch(), path + ".else");
            }
            case Stmt.For loop -> nullStatementPath(loop.body(), path + ".for");
            case Stmt.While loop -> nullStatementPath(loop.body(), path + ".while");
            default -> null;
        };
    }

    private static String findNullInStatements(final List<Stmt> statements, final String path) {
        for (int index = 0; index < statements.size(); index++) {
            final var nullPath = nullStatementPath(statements.get(index), path + "[" + index + "]");
            if (nullPath != null) return nullPath;
        }
        return null;
    }
}