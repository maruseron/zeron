package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.domain.FunctionShapeNames;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

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
        final var resolver = new Resolver();

        resolver.resolve(statements);

        assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "explicit"));
        assertEquals(TypeDescriptor.ofString(), bindingType(resolver, statements, "inferred"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "firstValue"));
        assertEquals(TypeDescriptor.ofInt(), bindingType(resolver, statements, "transformed"));
    }

    @Test
    public void rejectsConflictingAndUnresolvedTypeArguments() {
        for (final var source : List.of(
                "fn same<T>(left: T, right: T): T = left; let value = same(1, \"text\");",
                "fn add<T>(left: T, right: T): T = left + right;",
                "fn negate<T>(value: T): T = -value;",
                "fn equal<T>(left: T, right: T): Boolean = left == right;",
            "fn identity<T>(value: T): T = value; let value = identity<Missing>(1);",
            "fn use<T>(operation: (T) -> Unit): Unit {} "
                    + "fn test(): Unit { use(value -> { print(value); }); }")) {
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
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
                "class NotAContract {} fn invalid<T: NotAContract>(value: T): Unit = unit;")) {
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
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
        final var compiler = new Compiler(source, className);
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
        final var compiler = new Compiler(statements, className);
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
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
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
        final var compiler = new Compiler(statements, className);
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
            assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
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
        final var compiler = new Compiler(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
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
                fn stringToInt(): Int = apply("seven", value -> if (value == "seven") then 7 else 0);
                fn floatToString(): String = apply(2.5, value -> if (value > 2.0) then "wide" else "small");
                fn stringToFloat(): Float = apply("wide", value -> if (value == "wide") then 4.5 else 1.5);
                fn booleanToString(): String = apply(true, value -> if (value) then "true" else "false");
                fn stringToBoolean(): Boolean = apply("true", value -> value == "true");
                """);
        final var compiler = new Compiler(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(className);
                assertEquals("seven", program.getMethod("intToString").invoke(null));
                assertEquals(7, program.getMethod("stringToInt").invoke(null));
                assertEquals("wide", program.getMethod("floatToString").invoke(null));
                assertEquals(4.5, (Double) program.getMethod("stringToFloat").invoke(null), 0.0);
                assertEquals("true", program.getMethod("booleanToString").invoke(null));
                assertEquals(true, program.getMethod("stringToBoolean").invoke(null));
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
        final var compiler = new Compiler(statements, className);
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
                assertEquals("same directional conversion shares one helper; reverse and distinct shapes do not",
                        3, adapterCount);
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
        final var compiler = new Compiler(statements, className);
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
        final var compiler = new Compiler(statements, className);
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
        final var compiler = new Compiler(statements, className);
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