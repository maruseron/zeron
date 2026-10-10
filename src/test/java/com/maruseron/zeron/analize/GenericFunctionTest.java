package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.FunctionShapeNames;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.io.IOException;
import java.net.URLClassLoader;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class GenericFunctionTest {
    @Test
    public void infersGenericArgumentsFromValuesAndCallbacks() {
        final var statements = parse("""
                fn identity<T>(value: T): T = value;
                fn first<T>(values: Array<T>): T = values[0];
                fn apply<T, R>(value: T, transform: (T) -> R): R = transform(value);
                let explicit = identity<Int>(7);
                let inferred = identity("text");
                let firstValue = first([8, 9]);
                let transformed = apply(4, number -> number + 1);
                """);
        final var resolver = new ResolutionService();

        final var result = resolver.resolve(statements);

        assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "explicit"));
        assertEquals(TypeDescriptor.ofString(), bindingType(result, statements, "inferred"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "firstValue"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "transformed"));
    }

    @Test
    public void infersAndExecutesGenericClassAndContractMethods() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "GenericMethods" + suffix;
        final var classFile = Path.of("dist", "Echo.class");
        final var contractFile = Path.of("dist", "Transform.class");
        final var programFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                contract Transform<T> {
                    echo<V>(value: V): V;
                }
                class Echo<T> is Transform<T> {
                    value: T;
                    public constructor new;
                    public echo<U>(value: U): U = value;
                    public choose<U>(ignored: U): T = this.value;
                    public apply<U, R>(value: U, transform: (U) -> R): R = transform(value);
                    public relay<V>(value: V): V = this.echo(value);
                }
                fn inferredCall(): Int = Echo<String>.new("stored").echo(7);
                fn explicitCall(): String = Echo<String>.new("stored").echo<String>("explicit");
                fn substitutedOwner(): String = Echo<String>.new("stored").choose(1);
                fn callbackInference(): Int =
                    Echo<String>.new("stored").apply(3, number -> number + 1);
                fn nestedInference(): Int = Echo<String>.new("stored").relay(9);
                fn contractDispatch(): String {
                    let value: Transform<String> = Echo<String>.new("stored");
                    return value.echo<String>("contract");
                }
                """);
        final var compiler = new CompilationService(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(7, generated.getMethod("inferredCall").invoke(null));
                assertEquals("explicit", generated.getMethod("explicitCall").invoke(null));
                assertEquals("stored", generated.getMethod("substitutedOwner").invoke(null));
                assertEquals(4, generated.getMethod("callbackInference").invoke(null));
                assertEquals(9, generated.getMethod("nestedInference").invoke(null));
                assertEquals("contract", generated.getMethod("contractDispatch").invoke(null));
                final var echoClass = loader.loadClass("Echo");
                assertEquals("T", echoClass.getTypeParameters()[0].getName());
                assertEquals("Transform<T>", echoClass.getGenericInterfaces()[0].getTypeName());
                final var echoMethod = echoClass.getMethod("echo", Object.class);
                assertEquals("U", echoMethod.getTypeParameters()[0].getName());
                assertEquals("U", echoMethod.getGenericParameterTypes()[0].getTypeName());
                assertEquals("U", echoMethod.getGenericReturnType().getTypeName());
                final var chooseMethod = echoClass.getMethod("choose", Object.class);
                assertEquals("T", chooseMethod.getGenericReturnType().getTypeName());
                final var contractMethod = loader.loadClass("Transform")
                        .getMethod("echo", Object.class);
                assertEquals("V", contractMethod.getTypeParameters()[0].getName());
            }
        } finally {
            Files.deleteIfExists(programFile);
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(contractFile);
        }
    }

    @Test
    public void rejectsInvalidGenericMethodCallsAndContractSignatures() {
        for (final var source : List.of(
                "class Box { public constructor new; public make<T>(value: T): T = value; "
                        + "public invalid(): Int = this.make(); }",
                "class Box { public constructor new; public identity<T>(value: T): T = value; "
                        + "public invalid(): Int = this.identity<Int, String>(1); }",
                "contract Copy { copy<T>(value: T): T; } "
                        + "class Invalid is Copy { public constructor new; "
                        + "public copy<T>(value: Int): Int = value; }",
                "class Hidden { public constructor new; private identity<T>(value: T): T = value; } "
                        + "class Other { public constructor new; "
                        + "public call(): Int = Hidden.new().identity(1); }",
                "class Box { public constructor new; public mut update<T>(value: T): T = value; } "
                        + "class Other { public constructor new; "
                        + "public call(value: Box): Int = value.update(1); }")) {
            assertThrows(source, ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
        }
    }

    @Test
    public void rejectsConflictingAndUnresolvedTypeArguments() {
        for (final var source : List.of(
                "fn same<T>(left: T, right: T): T = left; let value = same(1, \"text\");",
                "fn add<T>(left: T, right: T): T = left + right;",
                "fn negate<T>(value: T): T = -value;",
                "fn less<T>(left: T, right: T): Boolean = left < right;",
                "fn narrow(value: Int): Unit = (); "
                        + "fn use(callback: (Any?) -> Unit): Unit = callback(null); "
                        + "fn invalid(): Unit = use(narrow);",
            "fn identity<T>(value: T): T = value; let value = identity<Missing>(1);",
            "fn use<T>(operation: (T) -> Unit): Unit {} "
                    + "fn test(): Unit { use(value -> { print(value); }); }")) {
            assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
        }
    }

    @Test
    public void rejectsOperationsWithoutAValidReadonlyContractBound() {
        for (final var source : List.of(
                "contract Named { name(): String; } "
                        + "fn invalid<T>(value: T): String = value.name();",
                "contract Named { name(): String; } "
                        + "fn display<T: Named>(value: T): String = value.name(); "
                        + "fn invalid(): String = display(42);",
                "contract Mutable { mut update(): Unit; } "
                        + "fn invalid<T: Mutable>(value: T): Unit = value.update();",
                "class NotAContract {} fn invalid<T: NotAContract>(value: T): Unit = ();")) {
            assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
        }
    }

    @Test
    public void resolvesAndExecutesReadonlyContractBounds() throws Exception {
        final var className = "GenericContractBounds" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var personFile = Path.of("dist", "Person.class");
        final var contractFile = Path.of("dist", "Named.class");
        final var source = parse("""
                public contract Named {
                    name(): String;
                }
                public class Person is Named {
                    value: String;
                    public constructor new;
                    public name(): String = this.value;
                }
                fn readName(value: Named): String = value.name();
                fn display<T: Named>(value: T): String = value.name();
                fn inferred(): String = display(Person.new("Ada"));
                fn explicit(): String = display<Person>(Person.new("Grace"));
                fn passesBound<T: Named>(value: T): String = readName(value);
                fn passThrough(): String = passesBound(Person.new("Lin"));
                """);
        final var compiler = new CompilationService(source, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals("Ada", generated.getMethod("inferred").invoke(null));
                assertEquals("Grace", generated.getMethod("explicit").invoke(null));
                assertEquals("Lin", generated.getMethod("passThrough").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(personFile);
            Files.deleteIfExists(contractFile);
        }
    }

    @Test
    public void resolvesMultipleContractBoundsOnFunctionsAndMethods() throws Exception {
        final var className = "MultipleContractBounds" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var itemFile = Path.of("dist", "MultiBoundItem.class");
        final var formatterFile = Path.of("dist", "FormatterImpl.class");
        final var formatterContractFile = Path.of("dist", "Formatter.class");
        final var source = parse("""
                public contract Named {
                    name(): String;
                }
                public contract Encodable {
                    encode(): String;
                }
                public contract Formatter {
                    format<T: Named + Encodable>(value: T): String;
                }
                public class MultiBoundItem is Named, Encodable {
                    label: String;
                    public constructor new;
                    public name(): String = this.label;
                    public encode(): String = "[" + this.label + "]";
                }
                public class FormatterImpl is Formatter {
                    public constructor new;
                    public format<T: Named + Encodable>(value: T): String =
                        value.name() + value.encode();
                }
                fn combine<T: Named + Encodable>(value: T): String =
                    value.name() + value.encode();
                fn inferredFunctionCall(): String = combine(MultiBoundItem.new("A"));
                fn explicitFunctionCall(): String = combine<MultiBoundItem>(MultiBoundItem.new("B"));
                fn specializedFunctionReference(): String {
                    let operation: (MultiBoundItem) -> String = combine;
                    return operation(MultiBoundItem.new("E"));
                }
                fn genericMethodCall(): String =
                    FormatterImpl.new().format(MultiBoundItem.new("C"));
                fn contractMethodCall(): String {
                    let formatter: Formatter = FormatterImpl.new();
                    return formatter.format(MultiBoundItem.new("D"));
                }
                """);
        final var compiler = new CompilationService(source, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals("A[A]", generated.getMethod("inferredFunctionCall").invoke(null));
                assertEquals("B[B]", generated.getMethod("explicitFunctionCall").invoke(null));
                assertEquals("E[E]", generated.getMethod("specializedFunctionReference").invoke(null));
                assertEquals("C[C]", generated.getMethod("genericMethodCall").invoke(null));
                assertEquals("D[D]", generated.getMethod("contractMethodCall").invoke(null));

                final var functionBounds = generated.getMethod("combine", Object.class)
                        .getTypeParameters()[0].getBounds();
                assertEquals(List.of("Object", "Named", "Encodable"),
                        java.util.Arrays.stream(functionBounds)
                                .map(type -> ((Class<?>) type).getSimpleName()).toList());
                final var methodBounds = loader.loadClass("FormatterImpl")
                        .getMethod("format", Object.class).getTypeParameters()[0].getBounds();
                assertEquals(List.of("Object", "Named", "Encodable"),
                        java.util.Arrays.stream(methodBounds)
                                .map(type -> ((Class<?>) type).getSimpleName()).toList());
                final var contractBounds = loader.loadClass("Formatter")
                        .getMethod("format", Object.class).getTypeParameters()[0].getBounds();
                assertEquals(List.of("Object", "Named", "Encodable"),
                        java.util.Arrays.stream(contractBounds)
                                .map(type -> ((Class<?>) type).getSimpleName()).toList());
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(itemFile);
            Files.deleteIfExists(formatterFile);
            Files.deleteIfExists(formatterContractFile);
            Files.deleteIfExists(Path.of("dist", "Named.class"));
            Files.deleteIfExists(Path.of("dist", "Encodable.class"));
        }
    }

    @Test
    public void rejectsInvalidMultipleContractBoundsAndBoundedMethodCalls() {
        for (final var source : List.of(
                "contract Named { name(): String; } contract Encodable { encode(): String; } "
                        + "class NameOnly is Named { value: String; public constructor new; "
                        + "public name(): String = this.value; } "
                        + "fn display<T: Named + Encodable>(value: T): String = value.name(); "
                        + "fn inferredInvalid(): String = display(NameOnly.new(\"x\")); "
                        + "fn explicitInvalid(): String = display<NameOnly>(NameOnly.new(\"x\"));",
                "contract Named { name(): String; } "
                        + "fn invalid<T: Named + String>(value: T): String = value.name();",
                "contract Named { name(): String; } contract Encodable { encode(): String; } "
                        + "contract Formatter { format<T: Named + Encodable>(value: T): String; } "
                        + "class Invalid is Formatter { public constructor new; "
                        + "public format<T: Named>(value: T): String = value.name(); }")) {
            assertThrows(source, ResolutionError.class,
                    () -> new ResolutionService().resolve(parse(source)));
        }
    }

    @Test
    public void resolvesAppliedGenericContractBoundsAndInstanceCalls() {
        final var parsed = Parser.of(Scanner.from("""
                contract Box<T> {
                    unwrap(): T;
                }
                class Holder<T> is Box<T> {
                    value: T;
                    public constructor new;
                    public unwrap(): T = this.value;
                }
                fn fetchBound<E, C: Box<E>>(value: C): E = value.unwrap();
                fn fetchBoundInt(): Int = fetchBound::<Int, Holder<Int>>(Holder<Int>.new(42));
                """).scanTokens()).parseCompilationUnitWithDiagnostics("applied-bounds.zn");
        assertTrue(parsed.diagnostics().toString(), parsed.diagnostics().isEmpty());
        final List<Stmt> statements = parsed.compilationUnit().declarations();
        assertTrue(statements.toString(), statements.stream()
                .anyMatch(statement -> statement instanceof Stmt.Function function
                        && function.name().lexeme().equals("fetchBound")));

        final var result = new ResolutionService().resolve(statements);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void appliedGenericContractBoundsRetainTheirTypeArguments() {
        final var source = """
                contract Box<T> {
                    unwrap(): T;
                }
                class Holder<T> is Box<T> {
                    value: T;
                    public constructor new;
                    public unwrap(): T = this.value;
                }
                fn fetchBound<E, C: Box<E>>(value: C): E = value.unwrap();
                fn invalid(): Int = fetchBound::<Int, Holder<String>>(Holder<String>.new("text"));
                """;
        assertThrows(source, ResolutionError.class,
                () -> new ResolutionService().resolve(parse(source)));
    }

    @Test
    public void rejectsBoundsOnClassAndContractDeclarationTypeParameters() {
        for (final var source : List.of(
                "contract Named { name(): String; } class Box<T: Named> {}",
                "contract Named { name(): String; } contract Box<T: Named> {}")) {
            final var result = Parser.of(Scanner.from(source).scanTokens())
                    .parseCompilationUnitWithDiagnostics("bounds.zn");
            assertTrue(result.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code().toString().equals("ZR1105")));
        }
    }

    @Test
    public void specializesGenericFunctionValuesExplicitlyAndFromExpectedTypes() throws Exception {
        final var className = "GenericFunctionValues" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn identity<T>(value: T): T = value;
                fn makeIdentity<T>(): (T) -> T = value -> value;
                fn consumeInt(operation: (Int) -> Int, value: Int): Int = operation(value);
                fn explicitBinding(): Int {
                    let operation: (Int) -> Int = identity::<Int>;
                    return operation(41);
                }
                fn explicitImmediateCall(): Int = identity::<Int>(49);
                fn inferredExplicitBinding(): Int {
                    let operation = identity::<Int>;
                    return operation(47);
                }
                fn expectedBinding(): String {
                    let operation: (String) -> String = identity;
                    return operation("expected");
                }
                fn explicitArgument(): Int = consumeInt(identity::<Int>, 42);
                fn expectedArgument(): Int = consumeInt(identity, 43);
                fn explicitReturn(): (Boolean) -> Boolean = identity::<Boolean>;
                fn expectedReturn(): (Boolean) -> Boolean = identity;
                fn returnedCallback(): Int {
                    let factory: () -> (Int) -> Int = makeIdentity::<Int>;
                    let operation = factory();
                    return operation(44);
                }
                fn genericCallbackBridge(): Int {
                    let apply: ((Int) -> Int, Int) -> Int = applyGeneric::<Int, Int>;
                    return apply(identity::<Int>, 45);
                }
                fn applyGeneric<T, R>(operation: (T) -> R, value: T): R = operation(value);
                fn inferredGenericCallbackBridge(): Int = applyGeneric(identity, 48);
                fn localShadow(identity: (Int) -> Int): Int {
                    let operation: (Int) -> Int = identity;
                    return operation(46);
                }
                fn localShadowTest(): Int = localShadow(identity::<Int>);
                """);
        final var compiler = new CompilationService(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                assertEquals(41, program.getMethod("explicitBinding").invoke(null));
                assertEquals(49, program.getMethod("explicitImmediateCall").invoke(null));
                assertEquals(47, program.getMethod("inferredExplicitBinding").invoke(null));
                assertEquals("expected", program.getMethod("expectedBinding").invoke(null));
                assertEquals(42, program.getMethod("explicitArgument").invoke(null));
                assertEquals(43, program.getMethod("expectedArgument").invoke(null));
                assertEquals(44, program.getMethod("returnedCallback").invoke(null));
                assertEquals(45, program.getMethod("genericCallbackBridge").invoke(null));
                assertEquals(48, program.getMethod("inferredGenericCallbackBridge").invoke(null));
                assertEquals(46, program.getMethod("localShadowTest").invoke(null));
                final var explicitReturn = program.getMethod("explicitReturn").invoke(null);
                final var expectedReturn = program.getMethod("expectedReturn").invoke(null);
                assertEquals(true, explicitReturn.getClass().getInterfaces()[0]
                        .getMethod("invoke", boolean.class).invoke(explicitReturn, true));
                assertEquals(false, expectedReturn.getClass().getInterfaces()[0]
                        .getMethod("invoke", boolean.class).invoke(expectedReturn, false));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsGenericFunctionValuesWithoutAValidSpecialization() {
        for (final var source : List.of(
                "fn identity<T>(value: T): T = value; let operation = identity;",
                "fn identity<T>(value: T): T = value; "
                        + "fn invalid(): (Int) -> String = identity;",
                "fn identity<T>(value: T): T = value; "
                        + "let operation: (Int) -> Int = identity::<Int, String>;",
                "contract Named { name(): String; } "
                        + "fn display<T: Named>(value: T): String = value.name(); "
                        + "fn invalid(): (String) -> String = display;")) {
            assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
        }
    }

    @Test
    public void generalizesImmutableLambdasAndInstantiatesAtEachUse() throws Exception {
        final var className = "PolymorphicLambdas" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn applyInt(operation: (Int) -> Int, value: Int): Int = operation(value);
                fn polymorphicIdentity(): String {
                    let identity = value -> value;
                    let integer = identity(37);
                    let text = identity("poly");
                    return text;
                }
                fn polymorphicCallbackArgument(): Int {
                    let identity = value -> value;
                    return applyInt(identity, 38);
                }
                fn polymorphicCallbackReturn(): (Int) -> Int {
                    let identity = value -> value;
                    return identity;
                }
                fn polymorphicConstant(): String {
                    let constant = ignored -> "constant";
                    let fromInt = constant(1);
                    return constant("unused");
                }
                """);
        final var compiler = new CompilationService(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                assertEquals("poly", program.getMethod("polymorphicIdentity").invoke(null));
                assertEquals(38, program.getMethod("polymorphicCallbackArgument").invoke(null));
                assertEquals("constant", program.getMethod("polymorphicConstant").invoke(null));
                final var callback = program.getMethod("polymorphicCallbackReturn").invoke(null);
                assertEquals(39, callback.getClass().getInterfaces()[0]
                        .getMethod("invoke", int.class).invoke(callback, 39));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void doesNotGeneralizeMutableLambdaBindings() {
        for (final var source : List.of(
            "fn invalid(): Unit { let mut identity = value -> value; "
                + "let integer = identity(1); let text = identity(\"text\"); }",
            "fn invalid(): Unit { let identity = value -> value; let mut alias = identity; }")) {
            assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
        }
    }

    @Test
    public void erasesGenericValuesAndAdaptsCallbacksAtBothBoundaries() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "GenericFunctions" + suffix;
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn identity<T>(value: T): T = value;
                fn first<T>(values: Array<T>): T = values[0];
                fn apply<T, R>(value: T, transform: (T) -> R): R = transform(value);
                fn applyWide<T, R>(value: T, transform: (Float, T) -> R): R = transform(2.5, value);
                fn identityFunction<T>(): (T) -> T = value -> value;
                fn identityInt(): Int = identity(42);
                fn identityText(): String = identity<String>("zeron");
                fn firstArray(): Int = first([17, 23]);
                fn mappedText(): String = apply("zero", text -> text + "n");
                fn inlineCallback(): Int = apply(5, value -> value + 3);
                fn wideCallback(): Int = applyWide(3, (scale, value) -> value);
                fn storedCallback(): Int {
                    let increment = value -> value + 1;
                    return apply(5, increment);
                }
                fn returnedCallback(): Int {
                    let increment: (Int) -> Int = identityFunction<Int>();
                    return increment(9);
                }
                """);
        final var compiler = new CompilationService(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                final var identity = program.getMethod("identity", Object.class);
                assertEquals("T", identity.getTypeParameters()[0].getName());
                assertEquals("T", identity.getGenericParameterTypes()[0].getTypeName());
                assertEquals("T", identity.getGenericReturnType().getTypeName());
                assertEquals(42, program.getMethod("identityInt").invoke(null));
                assertEquals("zeron", program.getMethod("identityText").invoke(null));
                assertEquals(17, program.getMethod("firstArray").invoke(null));
                assertEquals("zeron", program.getMethod("mappedText").invoke(null));
                assertEquals(8, program.getMethod("inlineCallback").invoke(null));
                assertEquals(3, program.getMethod("wideCallback").invoke(null));
                assertEquals(6, program.getMethod("storedCallback").invoke(null));
                assertEquals(9, program.getMethod("returnedCallback").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void adaptsPrimitiveAndReferenceCallbackMatrix() throws Exception {
        final var className = "CallbackAbiMatrix" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn apply<T, R>(value: T, transform: (T) -> R): R = transform(value);
                fn intToString(): String = apply(7, value -> if (value == 7) then "seven" else "other");
                fn intToInt(): Int = apply(7, value -> value + 1);
                fn intToFloat(): Float = apply(7, value -> 7.5);
                fn intToBoolean(): Boolean = apply(7, value -> value == 7);
                fn stringToInt(): Int = apply("seven", value -> if (value == "seven") then 7 else 0);
                fn stringToString(): String = apply("seven", value -> value + "!");
                fn floatToString(): String = apply(2.5, value -> if (value > 2.0) then "wide" else "small");
                fn stringToFloat(): Float = apply("wide", value -> if (value == "wide") then 4.5 else 1.5);
                fn floatToInt(): Int = apply(2.5, value -> if (value > 2.0) then 2 else 0);
                fn floatToFloat(): Float = apply(2.5, value -> value + 0.5);
                fn floatToBoolean(): Boolean = apply(2.5, value -> value > 2.0);
                fn booleanToString(): String = apply(true, value -> if (value) then "true" else "false");
                fn stringToBoolean(): Boolean = apply("true", value -> value == "true");
                fn booleanToInt(): Int = apply(true, value -> if (value) then 1 else 0);
                fn booleanToFloat(): Float = apply(true, value -> if (value) then 1.5 else 0.5);
                fn booleanToBoolean(): Boolean = apply(true, value -> value == false);
                """);
        final var compiler = new CompilationService(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                assertEquals("seven", program.getMethod("intToString").invoke(null));
                assertEquals(8, program.getMethod("intToInt").invoke(null));
                assertEquals(7.5, (Double) program.getMethod("intToFloat").invoke(null), 0.0);
                assertEquals(true, program.getMethod("intToBoolean").invoke(null));
                assertEquals(7, program.getMethod("stringToInt").invoke(null));
                assertEquals("seven!", program.getMethod("stringToString").invoke(null));
                assertEquals("wide", program.getMethod("floatToString").invoke(null));
                assertEquals(4.5, (Double) program.getMethod("stringToFloat").invoke(null), 0.0);
                assertEquals(2, program.getMethod("floatToInt").invoke(null));
                assertEquals(3.0, (Double) program.getMethod("floatToFloat").invoke(null), 0.0);
                assertEquals(true, program.getMethod("floatToBoolean").invoke(null));
                assertEquals("true", program.getMethod("booleanToString").invoke(null));
                assertEquals(true, program.getMethod("stringToBoolean").invoke(null));
                assertEquals(1, program.getMethod("booleanToInt").invoke(null));
                assertEquals(1.5, (Double) program.getMethod("booleanToFloat").invoke(null), 0.0);
                assertEquals(false, program.getMethod("booleanToBoolean").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void adaptsCapturedCallbacksAcrossReturnedAndStoredGenericBoundaries() throws Exception {
        final var className = "CapturedGenericCallbacks" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var source = """
                fn apply<T, R>(value: T, transform: (T) -> R): R = transform(value);
                fn constant<T, R>(value: R): (T) -> R = ignored -> value;
                fn returnedPrimitiveCallback(): Int {
                    let base = 40;
                    let callback: (Int) -> Int = constant<Int, Int>(base + 1);
                    let stored = callback;
                    return apply(1, stored);
                }
                fn returnedReferenceCallback(): String {
                    let captured = "captured";
                    let callback: (String) -> String = constant<String, String>(captured);
                    let stored: (String) -> String = callback;
                    return apply("ignored", stored);
                }
                fn callerCaptureThroughGenericCallback(): Int {
                    let base = 40;
                    let callback = value -> value + base;
                    let stored: (Int) -> Int = callback;
                    return apply(2, stored);
                }
                """;

        try {
            final var firstCompiler = new CompilationService(parse(source), className);
            firstCompiler.resolve();
            firstCompiler.compile();
            final var firstMainClass = Files.readAllBytes(classFile);
            final var firstLambdaClasses = generatedLambdaClasses();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertCapturedCallbackResults(loader, className);
            }

            final var secondCompiler = new CompilationService(parse(source), className);
            secondCompiler.resolve();
            secondCompiler.compile();
            assertArrayEquals(firstMainClass, Files.readAllBytes(classFile));
            final var secondLambdaClasses = generatedLambdaClasses();
            assertEquals(firstLambdaClasses.keySet(), secondLambdaClasses.keySet());
            for (final var entry : firstLambdaClasses.entrySet()) {
                assertArrayEquals("recompiled lambda interface " + entry.getKey(),
                        entry.getValue(), secondLambdaClasses.get(entry.getKey()));
            }
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertCapturedCallbackResults(loader, className);
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void reusesDirectionalAdapterHelpersAcrossCallSites() throws Exception {
        final var className = "CallbackAdapterReuse" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn apply<T, R>(value: T, transform: (T) -> R): R = transform(value);
                fn identityFunction<T>(): (T) -> T = value -> value;
                fn firstString(): String = apply("first", value -> value);
                fn secondString(): String = apply("second", value -> value);
                fn intToString(): String = apply(3, value -> "integer");
                fn returnedStringCallback(): String {
                    let callback: (String) -> String = identityFunction<String>();
                    return callback("returned");
                }
                """);
        final var compiler = new CompilationService(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                assertEquals("first", program.getMethod("firstString").invoke(null));
                assertEquals("second", program.getMethod("secondString").invoke(null));
                assertEquals("integer", program.getMethod("intToString").invoke(null));
                assertEquals("returned", program.getMethod("returnedStringCallback").invoke(null));

                final var adapterCount = java.util.Arrays.stream(program.getDeclaredMethods())
                        .filter(method -> method.getName().startsWith("$adapter$"))
                        .count();
                assertEquals(4, adapterCount);
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void adaptsNestedNullableAndMutableFunctionCallbacks() throws Exception {
        final var className = "GenericCallbackShapes" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn echoNested<T, R>(operation: ((T) -> R) -> R): ((T) -> R) -> R = operation;
                fn echoNullable<T, R>(operation: ((T) -> R)?): ((T) -> R)? = operation;
                fn echoNullableElements<T, R>(operation: (T?) -> R?): (T?) -> R? = operation;
                fn echoMutable<T, R>(operation: &(T) -> R): &(T) -> R = operation;
                fn nestedCallback(): Int {
                    let operation = echoNested<Int, Int>(inner -> inner(4));
                    return operation(value -> value + 2);
                }
                fn nullableCallback(): ((Int) -> Int)? {
                    let operation: (Int) -> Int = value -> value + 5;
                    return echoNullable<Int, Int>(operation);
                }
                fn nullCallback(): ((Int) -> Int)? = echoNullable<Int, Int>(null);
                fn nullableElementCallback(value: Int?): String? {
                    let transform = echoNullableElements<Int, String>(item ->
                        if (item == null) then null else "present");
                    return transform(value);
                }
                fn mutableCallback(operation: &(Int) -> Int): Int {
                    let echoed = echoMutable<Int, Int>(operation);
                    return echoed(5);
                }
                """);
        final var compiler = new CompilationService(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                assertEquals(6, program.getMethod("nestedCallback").invoke(null));
                final var functionType = TypeDescriptor.functionOf("", TypeDescriptor.ofInt(),
                    TypeDescriptor.ofInt());
                final var samType = loader.loadClass(FunctionShapeNames.interfaceName(functionType));
                final var operation = Proxy.newProxyInstance(loader, new Class<?>[]{samType},
                    (_, _, arguments) -> (int) arguments[0] + 3);
                assertEquals(8, program.getMethod("mutableCallback", samType).invoke(null, operation));
                assertNull(program.getMethod("nullableElementCallback", Integer.class)
                    .invoke(null, new Object[]{null}));
                assertEquals("present", program.getMethod("nullableElementCallback", Integer.class)
                    .invoke(null, 4));

                final var callback = program.getMethod("nullableCallback").invoke(null);
                assertNotNull(callback);
                assertEquals(9, callback.getClass().getInterfaces()[0]
                        .getMethod("invoke", int.class).invoke(callback, 4));
                assertNull(program.getMethod("nullCallback").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void adaptsCallbacksAcrossGenericClassBoundaries() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "GenericNominalCallbacks" + suffix;
        final var boxName = "CallbackBox" + suffix;
        final var contractName = "CallbackSource" + suffix;
        final var sourceName = "IntCallbackSource" + suffix;
        final var classFile = Path.of("dist", className + ".class");
        final var boxFile = Path.of("dist", boxName + ".class");
        final var contractFile = Path.of("dist", contractName + ".class");
        final var sourceFile = Path.of("dist", sourceName + ".class");
        final var statements = parse("""
                class %s<T, R> {
                    transform: (T) -> R;
                    public constructor new;
                    public apply(value: T): R {
                        let callback = this.transform;
                        return callback(value);
                    }
                    public run(transform: (T) -> R, value: T): R = transform(value);
                    public getTransform(): (T) -> R = this.transform;
                    public mut setTransform(transform: (T) -> R): Unit {
                        this.transform = transform;
                    }
                }
                contract %s<T, R> {
                    getTransform(): (T) -> R;
                    apply(transform: (T) -> R, value: T): R;
                    getTextTransform(): (T) -> String;
                    applyText(transform: (T) -> String, value: T): String;
                }
                class %s is %s<Int, Int> {
                    transform: (Int) -> Int;
                    public constructor new;
                    public getTransform(): (Int) -> Int = this.transform;
                    public apply(transform: (Int) -> Int, value: Int): Int = transform(value);
                    public getTextTransform(): (Int) -> String =
                        value -> if (value == 52) then "fifty-two" else "other";
                    public applyText(transform: (Int) -> String, value: Int): String = transform(value);
                }
                fn constructorPath(): Int {
                    let box = %s<Int, Int>.new(value -> value);
                    return box.apply(41);
                }
                fn methodArgumentPath(): Int {
                    let box = %s<Int, Int>.new(value -> value);
                    return box.run(value -> value, 42);
                }
                fn methodResultPath(): Int {
                    let box = %s<Int, Int>.new(value -> value);
                    let callback: (Int) -> Int = box.getTransform();
                    return callback(43);
                }
                fn fieldAssignmentPath(): Int {
                    let box = %s<Int, Int>.new(value -> value + 1);
                    box.setTransform(value -> value);
                    return box.apply(44);
                }
                fn contractBridgePath(): Int {
                    let source: %s<Int, Int> = %s.new(value -> value);
                    let callback: (Int) -> Int = source.getTransform();
                    return callback(45);
                }
                fn contractArgumentBridgePath(): Int {
                    let source: %s<Int, Int> = %s.new(value -> value);
                    return source.apply(value -> value, 46);
                }
                fn contractTextResultBridgePath(): String {
                    let source: %s<Int, Int> = %s.new(value -> value);
                    let callback = source.getTextTransform();
                    return callback(52);
                }
                fn contractTextArgumentBridgePath(): String {
                    let source: %s<Int, Int> = %s.new(value -> value);
                    return source.applyText(value ->
                        if (value == 53) then "fifty-three" else "other", 53);
                }
                """.formatted(boxName, contractName, sourceName, contractName,
                boxName, boxName, boxName, boxName, contractName, sourceName,
                contractName, sourceName, contractName, sourceName, contractName, sourceName));
        final var compiler = new CompilationService(statements, className);
            try {
                compiler.resolve();
            } catch (final ResolutionError error) {
                throw new AssertionError("Resolution failed at '" + error.token.lexeme()
                    + "' on line " + error.token.line(), error);
            }

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                assertEquals(41, program.getMethod("constructorPath").invoke(null));
                assertEquals(42, program.getMethod("methodArgumentPath").invoke(null));
                assertEquals(43, program.getMethod("methodResultPath").invoke(null));
                assertEquals(44, program.getMethod("fieldAssignmentPath").invoke(null));
                assertEquals(45, program.getMethod("contractBridgePath").invoke(null));
                assertEquals(46, program.getMethod("contractArgumentBridgePath").invoke(null));
                assertEquals("fifty-two", program.getMethod("contractTextResultBridgePath").invoke(null));
                assertEquals("fifty-three", program.getMethod("contractTextArgumentBridgePath").invoke(null));

                final var erasedCallbackType = TypeDescriptor.functionOf("",
                    TypeDescriptor.ofName("java.lang.Object"),
                    TypeDescriptor.ofName("java.lang.Object"));
                final var erasedCallbackInterface = FunctionShapeNames.interfaceName(erasedCallbackType);
                final var generatedSource = loader.loadClass(sourceName);
                final var resultBridge = java.util.Arrays.stream(generatedSource.getDeclaredMethods())
                    .filter(method -> method.getName().equals("getTransform") && method.isBridge())
                    .findFirst()
                    .orElseThrow();
                assertTrue(resultBridge.isSynthetic());
                assertTrue(java.lang.reflect.Modifier.isPublic(resultBridge.getModifiers()));
                assertEquals("contract callback result descriptor", erasedCallbackInterface,
                    resultBridge.getReturnType().getName());

                final var argumentBridge = java.util.Arrays.stream(generatedSource.getDeclaredMethods())
                    .filter(method -> method.getName().equals("apply") && method.isBridge())
                    .findFirst()
                    .orElseThrow();
                assertEquals("contract callback parameter descriptor", erasedCallbackInterface,
                    argumentBridge.getParameterTypes()[0].getName());
                assertEquals("contract type parameter descriptor", Object.class,
                    argumentBridge.getParameterTypes()[1]);
                assertEquals("contract generic result descriptor", Object.class,
                    argumentBridge.getReturnType());

                final var erasedTextCallbackType = TypeDescriptor.functionOf("",
                    TypeDescriptor.ofString(), TypeDescriptor.ofName("java.lang.Object"));
                final var erasedTextCallbackInterface = FunctionShapeNames.interfaceName(erasedTextCallbackType);
                final var textResultBridge = java.util.Arrays.stream(generatedSource.getDeclaredMethods())
                    .filter(method -> method.getName().equals("getTextTransform") && method.isBridge())
                    .findFirst()
                    .orElseThrow();
                assertEquals("mixed-shape contract callback result descriptor", erasedTextCallbackInterface,
                    textResultBridge.getReturnType().getName());

                final var textArgumentBridge = java.util.Arrays.stream(generatedSource.getDeclaredMethods())
                    .filter(method -> method.getName().equals("applyText") && method.isBridge())
                    .findFirst()
                    .orElseThrow();
                assertEquals("mixed-shape contract callback parameter descriptor", erasedTextCallbackInterface,
                    textArgumentBridge.getParameterTypes()[0].getName());
                assertEquals(Object.class, textArgumentBridge.getParameterTypes()[1]);
                assertEquals(String.class, textArgumentBridge.getReturnType());

                final var adapterMethods = java.util.Arrays.stream(program.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("$adapter$")
                        || method.getName().startsWith("$nullableAdapter$"))
                    .toList();
                assertTrue(!adapterMethods.isEmpty());
                assertTrue(adapterMethods.stream().allMatch(method -> method.isSynthetic()
                    && java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers())));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(boxFile);
            Files.deleteIfExists(contractFile);
            Files.deleteIfExists(sourceFile);
        }
    }

    @Test
    public void adaptsWrappedCallbacksAcrossGenericClassBoundaries() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "NestedNominalCallbacks" + suffix;
        final var nullableBoxName = "NullableCallbackBox" + suffix;
        final var nestedBoxName = "NestedCallbackBox" + suffix;
        final var referenceBoxName = "ReferenceCallbackBox" + suffix;
        final var classFile = Path.of("dist", className + ".class");
        final var nullableBoxFile = Path.of("dist", nullableBoxName + ".class");
        final var nestedBoxFile = Path.of("dist", nestedBoxName + ".class");
        final var referenceBoxFile = Path.of("dist", referenceBoxName + ".class");
        final var statements = parse("""
                class %s<T, R> {
                    transform: ((T) -> R)?;
                    public constructor new;
                    public getTransform(): ((T) -> R)? = this.transform;
                }
                class %s<T, R> {
                    transform: ((T) -> R) -> R;
                    public constructor new;
                    public apply(value: (T) -> R): R {
                        let callback = this.transform;
                        return callback(value);
                    }
                }
                class %s<T, R> {
                    transform: &(T) -> R;
                    public constructor new;
                    public apply(value: T): R {
                        let callback = this.transform;
                        return callback(value);
                    }
                }
                fn nullableCallbackPath(): ((Int) -> Int)? {
                    let box = %s<Int, Int>.new(value -> value + 1);
                    return box.getTransform();
                }
                fn nullableNullPath(): ((Int) -> Int)? {
                    let box = %s<Int, Int>.new(null);
                    return box.getTransform();
                }
                fn nestedCallbackPath(): Int {
                    let box = %s<Int, Int>.new(callback -> callback(52));
                    return box.apply(value -> value + 1);
                }
                fn referenceCallbackPath(operation: &(Int) -> Int): Int {
                    let box = %s<Int, Int>.new(operation);
                    return box.apply(47);
                }
                """.formatted(nullableBoxName, nestedBoxName, referenceBoxName,
                nullableBoxName, nullableBoxName, nestedBoxName, referenceBoxName));
        final var compiler = new CompilationService(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                final var nullableCallback = program.getMethod("nullableCallbackPath").invoke(null);
                final var callbackType = TypeDescriptor.functionOf("", TypeDescriptor.ofInt(),
                    TypeDescriptor.ofInt());
                final var callbackInterface = loader.loadClass(FunctionShapeNames.interfaceName(callbackType));
                assertEquals(51, callbackInterface.getMethod("invoke", int.class)
                    .invoke(nullableCallback, 50));
                assertNull(program.getMethod("nullableNullPath").invoke(null));
                assertEquals(53, program.getMethod("nestedCallbackPath").invoke(null));
                final var operation = Proxy.newProxyInstance(loader, new Class<?>[]{callbackInterface},
                        (_, _, arguments) -> (int) arguments[0] + 2);
                assertEquals(49, program.getMethod("referenceCallbackPath", callbackInterface)
                        .invoke(null, operation));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(nullableBoxFile);
            Files.deleteIfExists(nestedBoxFile);
            Files.deleteIfExists(referenceBoxFile);
        }
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }

    private static Map<String, byte[]> generatedLambdaClasses() throws IOException {
        final var lambdaClasses = new HashMap<String, byte[]>();
        try (final var files = Files.list(Path.of("dist"))) {
            for (final var file : files
                    .filter(path -> path.getFileName().toString().startsWith("Lambda$V1_"))
                    .toList()) {
                lambdaClasses.put(file.getFileName().toString(), Files.readAllBytes(file));
            }
        }
        return lambdaClasses;
    }

    private static void assertCapturedCallbackResults(final URLClassLoader loader,
                                                      final String className) throws Exception {
        final var program = loader.loadClass(className);
        assertEquals(41, program.getMethod("returnedPrimitiveCallback").invoke(null));
        assertEquals("captured", program.getMethod("returnedReferenceCallback").invoke(null));
        assertEquals(42, program.getMethod("callerCaptureThroughGenericCallback").invoke(null));
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