package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.scan.Scanner;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.AccessFlag;
import org.junit.Test;

import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public final class ClassContractTest {
    @Test
    public void sampleBytecodeContainsPrivateFieldsAndContractMethods() throws Exception {
    compileCanonicalSample();
    final var counter = ClassFile.of().parse(Path.of("dist", "TestCounter.class"));
    final var contract = ClassFile.of().parse(Path.of("dist", "TestCounterView.class"));
    final var valueField = counter.fields().stream()
        .filter(field -> field.fieldName().equalsString("value"))
        .findFirst()
        .orElseThrow();
    final var constructor = method(counter, "<init>");
    final var increment = method(counter, "incrementInternal");
    final var contractRead = method(contract, "read");

    assertTrue(valueField.flags().has(AccessFlag.PRIVATE));
    assertTrue(counter.interfaces().stream()
        .anyMatch(interfaceEntry -> interfaceEntry.asInternalName().equals("TestCounterView")));
    assertEquals("(I)V", constructor.methodType().stringValue());
    assertTrue(opcodes(constructor).contains(Opcode.PUTFIELD));
    assertTrue(opcodes(increment).contains(Opcode.GETFIELD));
    assertTrue(opcodes(increment).contains(Opcode.PUTFIELD));
    assertTrue(contract.flags().has(AccessFlag.INTERFACE));
    assertTrue(contractRead.flags().has(AccessFlag.PUBLIC));
    assertTrue(contractRead.flags().has(AccessFlag.ABSTRACT));
    }

    @Test
    public void sampleCallSitesUseConstructorVirtualAndInterfaceDispatch() throws Exception {
    compileCanonicalSample();
    final var program = ClassFile.of().parse(Path.of("dist", "test.class"));
    final var main = program.methods().stream()
        .filter(method -> method.methodName().equalsString("main")
            && method.methodType().equalsString("()Lcom/maruseron/zeron/runtime/UnitValue;"))
        .findFirst()
        .orElseThrow();
    final var invocations = main.code().orElseThrow().elementStream()
        .filter(InvokeInstruction.class::isInstance)
        .map(InvokeInstruction.class::cast)
        .toList();

    assertTrue(invocations.stream().anyMatch(invoke -> invoke.opcode() == Opcode.INVOKESPECIAL
        && invoke.owner().asInternalName().equals("TestCounter")
        && invoke.name().equalsString("<init>")));
    assertTrue(invocations.stream().anyMatch(invoke -> invoke.opcode() == Opcode.INVOKEVIRTUAL
        && invoke.owner().asInternalName().equals("TestCounter")
        && invoke.name().equalsString("increment")));
    assertTrue(invocations.stream().anyMatch(invoke -> invoke.opcode() == Opcode.INVOKEINTERFACE
        && invoke.owner().asInternalName().equals("TestCounterView")
        && invoke.name().equalsString("read")));
    }

    @Test
    public void constructsObjectsAndDispatchesThroughContract() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "Person" + suffix;
        final var contractName = "Named" + suffix;
        final var programName = "ClassContractGenerated" + suffix;
        final var classFile = Path.of("dist", className + ".class");
        final var contractFile = Path.of("dist", contractName + ".class");
        final var programFile = Path.of("dist", programName + ".class");
        final var source = """
                contract %s {
                    name(): String;
                }
                class %s is %s {
                    fullName: String;
                    years: Int;
                    public constructor new;
                    public name(): String = this.fullName;
                    public readYears(): Int = this.years;
                    public mut birthday(): Unit {
                        this.bump();
                    }
                    private mut bump(): Unit {
                        this.years = this.years + 1;
                    }
                }
                fn test(): Int {
                    let person = %s.new("Ada", 37);
                    person.birthday();
                    return person.readYears();
                }
                fn contractTest(): String {
                    let person: %s = %s.new("Ada", 37);
                    return person.name();
                }
                fn capturedContractTest(): String {
                    let person: %s = %s.new("Ada", 37);
                    let readName = () -> person.name();
                    return readName();
                }
                fn capturedMutableReferenceTest(): Int {
                    let person = %s.new("Ada", 37);
                    let birthday = () -> person.birthday();
                    birthday();
                    return person.readYears();
                }
                """.formatted(contractName, className, contractName, className, contractName,
                className, contractName, className, className);

        try {
            final var compiler = new Compiler(parse(source), programName);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals(38, program.getMethod("test").invoke(null));
                assertEquals("Ada", program.getMethod("contractTest").invoke(null));
                assertEquals("Ada", program.getMethod("capturedContractTest").invoke(null));
                assertEquals(38, program.getMethod("capturedMutableReferenceTest").invoke(null));
                assertTrue(Modifier.isPrivate(loader.loadClass(className).getDeclaredField("years").getModifiers()));
                assertTrue(loader.loadClass(className).getInterfaces()[0].isInterface());
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(contractFile);
            Files.deleteIfExists(programFile);
        }
    }

    @Test
    public void rejectsPrivateFieldAccessAndMutationThroughReadOnlyView() {
        final var privateFieldSource = """
                class Vault {
                    value: Int;
                    public constructor new;
                }
                fn read(): Int {
                    let vault = Vault.new(1);
                    return vault.value;
                }
                """;
        final var readonlyMutationSource = """
                class Counter {
                    value: Int;
                    public constructor new;
                    public mut increment(): Unit {
                        this.value = this.value + 1;
                    }
                }
                fn increment(counter: Counter): Unit {
                    counter.increment();
                }
                """;
        final var mutableCaptureSource = """
                class CapturedCounter {
                    value: Int;
                    public constructor new;
                    public mut increment(): Unit {
                        this.value = this.value + 1;
                    }
                }
                fn capture(): Unit {
                    let mut counter = CapturedCounter.new(0);
                    let closure = () -> counter.increment();
                }
                """;

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(privateFieldSource)));
        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(readonlyMutationSource)));
        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(mutableCaptureSource)));
    }

    @Test
    public void rejectsIncompatibleContractImplementation() {
        final var source = """
                contract Named {
                    name(): String;
                }
                class Person is Named {
                    fullName: String;
                    public constructor new;
                    private name(): String = this.fullName;
                }
                """;

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
    }

    @Test
    public void classConformsToMultipleContracts() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "MultiContractValue" + suffix;
        final var firstContract = "MultiNamed" + suffix;
        final var secondContract = "MultiLabelled" + suffix;
        final var thirdContract = "MultiDescribed" + suffix;
        final var programName = "MultiContractProgram" + suffix;
        final var classFile = Path.of("dist", className + ".class");
        final var firstFile = Path.of("dist", firstContract + ".class");
        final var secondFile = Path.of("dist", secondContract + ".class");
        final var thirdFile = Path.of("dist", thirdContract + ".class");
        final var programFile = Path.of("dist", programName + ".class");
        final var source = """
                contract %s {
                    name(): String;
                }
                contract %s {
                    name(): String;
                }
                contract %s {
                    description(): String;
                }
                class %s is %s, %s, %s {
                    value: String;
                    details: String;
                    public constructor new;
                    public name(): String = this.value;
                    public description(): String = this.details;
                }
                fn readName(value: %s): String = value.name();
                fn readLabel(value: %s): String = value.name();
                fn readDescription(value: %s): String = value.description();
                """.formatted(firstContract, secondContract, thirdContract, className,
                firstContract, secondContract, thirdContract, firstContract, secondContract,
                thirdContract);

        try {
            final var compiler = new Compiler(parse(source), programName);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generatedClass = loader.loadClass(className);
                final var value = generatedClass.getConstructor(String.class, String.class)
                        .newInstance("Ada", "compiler engineer");
                assertEquals(3, generatedClass.getInterfaces().length);
                final var program = loader.loadClass(programName);
                assertEquals("Ada", program.getMethod("readName", loader.loadClass(firstContract))
                        .invoke(null, value));
                assertEquals("Ada", program.getMethod("readLabel", loader.loadClass(secondContract))
                        .invoke(null, value));
                assertEquals("compiler engineer", program.getMethod("readDescription",
                        loader.loadClass(thirdContract)).invoke(null, value));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(firstFile);
            Files.deleteIfExists(secondFile);
            Files.deleteIfExists(thirdFile);
            Files.deleteIfExists(programFile);
        }
    }

    @Test
    public void genericClassesSubstituteMembersAndEraseToOneJvmClass() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "Box" + suffix;
        final var programName = "GenericBoxProgram" + suffix;
        final var classFile = Path.of("dist", className + ".class");
        final var programFile = Path.of("dist", programName + ".class");
        final var source = """
                class %s<T> {
                    value: T;
                    public constructor new;
                    public readValue(): T = this.value;
                    public mut writeValue(value: T): Unit {
                        this.value = value;
                    }
                }
                fn intBoxTest(): Int {
                    let box = %s<Int>.new(41);
                    box.writeValue(42);
                    return box.readValue();
                }
                fn stringBoxTest(): String {
                    let box = %s<String>.new("zeron");
                    return box.readValue();
                }
                """.formatted(className, className, className);

        try {
            final var compiler = new Compiler(parse(source), programName);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generatedClass = loader.loadClass(className);
                assertEquals(Object.class, generatedClass.getDeclaredField("value").getType());
                assertEquals(Object.class, generatedClass.getConstructor(Object.class)
                        .getParameterTypes()[0]);
                assertEquals(Object.class, generatedClass.getMethod("readValue").getReturnType());

                final var program = loader.loadClass(programName);
                assertEquals(42, program.getMethod("intBoxTest").invoke(null));
                assertEquals("zeron", program.getMethod("stringBoxTest").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(programFile);
        }
    }

    @Test
    public void genericClassArgumentsAreRequiredAndInvariant() {
        final var source = """
                class Box<T> {
                    value: T;
                    public constructor new;
                }
                fn mismatch(): Unit {
                    let box: Box<String> = Box<Int>.new(1);
                }
                """;
        final var rawConstruction = """
                class Box<T> {
                    value: T;
                    public constructor new;
                }
                fn missingTypeArgument(): Unit {
                    let box = Box.new(1);
                }
                """;

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(rawConstruction)));
    }

    @Test
    public void namedConstructorsAreFactoriesAndCanonicalConstructionDefaultsPublic() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var programName = "NamedConstructors" + suffix;
        final var personName = "FactoryPerson" + suffix;
        final var pointName = "ImplicitPoint" + suffix;
        final var boxName = "FactoryBox" + suffix;
        final var programFile = Path.of("dist", programName + ".class");
        final var personFile = Path.of("dist", personName + ".class");
        final var pointFile = Path.of("dist", pointName + ".class");
        final var boxFile = Path.of("dist", boxName + ".class");
        final var source = """
                class %s {
                    name: String;
                    age: Int;
                    private constructor new;
                    public constructor fromName(name: String) = %s.new(name, 0);
                    public constructor senior(name: String) {
                        let person = %s.new(name, 65);
                        person.age = person.age + 1;
                        return person;
                    }
                    public readName(): String = this.name;
                    public readAge(): Int = this.age;
                }
                class %s {
                    value: Int;
                    public read(): Int = this.value;
                }
                class %s<T> {
                    value: T;
                    public constructor from(value: T) {
                        let box = %s<T>.new(value);
                        return box;
                    }
                    public read(): T = this.value;
                }
                fn expressionFactory(): String = %s.fromName("Ada").readName();
                fn blockFactory(): Int = %s.senior("Grace").readAge();
                fn implicitCanonical(): Int = %s.new(42).read();
                fn genericFactory(): Int = %s<Int>.from(43).read();
                """.formatted(personName, personName, personName, pointName, boxName, boxName,
                personName, personName, pointName, boxName);

        try {
            final var compiler = new Compiler(parse(source), programName);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals("Ada", program.getMethod("expressionFactory").invoke(null));
                assertEquals(66, program.getMethod("blockFactory").invoke(null));
                assertEquals(42, program.getMethod("implicitCanonical").invoke(null));
                assertEquals(43, program.getMethod("genericFactory").invoke(null));
                assertTrue(Modifier.isStatic(loader.loadClass(personName)
                        .getMethod("fromName", String.class).getModifiers()));
                assertEquals(Object.class, loader.loadClass(boxName).getMethod("from", Object.class)
                        .getReturnType().getMethod("read").getReturnType());
            }
        } finally {
            Files.deleteIfExists(programFile);
            Files.deleteIfExists(personFile);
            Files.deleteIfExists(pointFile);
            Files.deleteIfExists(boxFile);
        }
    }

    @Test
    public void namedConstructorBlocksMustReturnClassReferencesAndHaveNoThis() {
        final var missingReturn = """
                class MissingFactoryReturn {
                    public constructor choose(flag: Boolean) {
                        if (flag) return MissingFactoryReturn.new();
                    }
                }
                """;
        final var usesThis = """
                class FactoryThis {
                    value: Int;
                    public constructor invalid() {
                        return this;
                    }
                }
                """;
        final var wrongReturn = """
                class FactoryWrongReturn {
                    public constructor invalid() = 1;
                }
                """;
        final var bypassPrivateCanonical = """
                class FactoryPrivateCanonical {
                    value: Int;
                    private constructor new;
                    public constructor from(value: Int) = FactoryPrivateCanonical.new(value);
                }
                fn bypass(): Unit {
                    let value = FactoryPrivateCanonical.new(1);
                }
                """;

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(missingReturn)));
        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(usesThis)));
        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(wrongReturn)));
        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(bypassPrivateCanonical)));
    }

    @Test
    public void genericContractConformanceUsesSubstitutionAndErasedBridges() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var contractName = "Value" + suffix;
        final var intClassName = "IntValue" + suffix;
        final var stringClassName = "StringValue" + suffix;
        final var genericClassName = "GenericValue" + suffix;
        final var programName = "GenericContractProgram" + suffix;
        final var contractFile = Path.of("dist", contractName + ".class");
        final var intClassFile = Path.of("dist", intClassName + ".class");
        final var stringClassFile = Path.of("dist", stringClassName + ".class");
        final var genericClassFile = Path.of("dist", genericClassName + ".class");
        final var programFile = Path.of("dist", programName + ".class");
        final var source = """
                contract %s<T> {
                    value(input: T): T;
                }
                class %s is %s<Int> {
                    public constructor new;
                    public value(input: Int): Int = input;
                }
                class %s is %s<String> {
                    public constructor new;
                    public value(input: String): String = input;
                }
                class %s<T> is %s<T> {
                    public constructor new;
                    public value(input: T): T = input;
                }
                fn readInt(value: %s<Int>): Int = value.value(42);
                fn readString(value: %s<String>): String = value.value("zeron");
                fn readGeneric(value: %s<Int>): Int = value.value(42);
                fn intTest(): Int {
                    let value = %s.new();
                    return readInt(value);
                }
                fn stringTest(): String {
                    let value = %s.new();
                    return readString(value);
                }
                fn genericTest(): Int {
                    let value = %s<Int>.new();
                    return readGeneric(value);
                }
                """.formatted(contractName, intClassName, contractName, stringClassName,
                contractName, genericClassName, contractName, contractName, contractName,
                genericClassName, intClassName, stringClassName, genericClassName);

        try {
            final var compiler = new Compiler(parse(source), programName);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var intClass = loader.loadClass(intClassName);
                final var bridge = java.util.Arrays.stream(intClass.getDeclaredMethods())
                        .filter(method -> method.getName().equals("value") && method.isBridge())
                        .findFirst()
                        .orElseThrow();
                assertEquals(Object.class, bridge.getReturnType());
                assertEquals(Object.class, bridge.getParameterTypes()[0]);
                assertEquals(42, loader.loadClass(programName).getMethod("intTest").invoke(null));
                assertEquals("zeron", loader.loadClass(programName).getMethod("stringTest").invoke(null));
                assertEquals(42, loader.loadClass(programName).getMethod("genericTest").invoke(null));
            }
        } finally {
            Files.deleteIfExists(contractFile);
            Files.deleteIfExists(intClassFile);
            Files.deleteIfExists(stringClassFile);
            Files.deleteIfExists(genericClassFile);
            Files.deleteIfExists(programFile);
        }
    }

    @Test
    public void genericContractConformanceRejectsSubstitutionMismatch() {
        final var source = """
                contract Value<T> {
                    value(): T;
                }
                class WrongValue is Value<String> {
                    public constructor new;
                    public value(): Int = 1;
                }
                """;

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(source)));
    }

    @Test
    public void rejectsDuplicateAndConflictingContractConformance() {
        final var duplicate = """
                contract Named { name(): String; }
                class Person is Named, Named {
                    public constructor new;
                    public name(): String = "Ada";
                }
                """;
        final var conflicting = """
                contract Named { name(): String; }
                contract Numbered { name(): Int; }
                class Person is Named, Numbered {
                    public constructor new;
                    public name(): String = "Ada";
                }
                """;

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(duplicate)));
        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse(conflicting)));
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }

    private static void compileCanonicalSample() throws Exception {
        final var source = Files.readString(Path.of("src/main/resources/test.zn"));
        final var compiler = new Compiler(parse(source), "test");
        compiler.resolve();
        compiler.compile();
    }

    private static MethodModel method(final ClassModel classModel, final String name) {
        return classModel.methods().stream()
                .filter(candidate -> candidate.methodName().equalsString(name))
                .findFirst()
                .orElseThrow();
    }

    private static List<Opcode> opcodes(final MethodModel method) {
        return method.code().orElseThrow().elementStream()
                .filter(Instruction.class::isInstance)
                .map(Instruction.class::cast)
                .map(Instruction::opcode)
                .toList();
    }
}