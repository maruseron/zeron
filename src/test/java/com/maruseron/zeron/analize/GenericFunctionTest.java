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
    public void adaptsNestedNullableAndMutableFunctionCallbacks() throws Exception {
        final var className = "GenericCallbackShapes" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn echoNested<T, R>(operation: ((T) -> R) -> R): ((T) -> R) -> R = operation;
                fn echoNullable<T, R>(operation: ((T) -> R)?): ((T) -> R)? = operation;
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
                }
                class %s is %s<Int, Int> {
                    transform: (Int) -> Int;
                    public constructor new;
                    public getTransform(): (Int) -> Int = this.transform;
                    public apply(transform: (Int) -> Int, value: Int): Int = transform(value);
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
                """.formatted(boxName, contractName, sourceName, contractName,
                boxName, boxName, boxName, boxName, contractName, sourceName,
                contractName, sourceName));
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