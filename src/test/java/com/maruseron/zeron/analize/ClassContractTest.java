package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
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
    public void autoAndCustomPropertiesSupportContractsAndCompoundAssignment() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var programName = "PropertyProgram" + suffix;
        final var source = """
                contract CounterView {
                    mut property value: Int;
                }
                class Counter is CounterView {
                    raw: Int;
                    public mut property value: Int {
                        get = this.raw;
                        set(next) = this.raw = next;
                    }
                    public mut add(step: Int): Unit {
                        this.value += step;
                    }
                }
                class AutoValues {
                    public property name: String;
                    public mut property count: Int = 1;
                }
                let mut receiverCalls = 0;
                fn countedCounter(): &Counter {
                    receiverCalls += 1;
                    return Counter.new(0);
                }
                fn customResult(): Int {
                    let counter = Counter.new(1);
                    counter.value += 41;
                    return counter.value;
                }
                fn compoundReceiverCount(): Int {
                    countedCounter().value += 1;
                    return receiverCalls;
                }
                fn contractResult(counter: &CounterView): Int {
                    counter.value += 1;
                    return counter.value;
                }
                fn autoResult(): Int = AutoValues.new("zeron").count;
                fn safeResult(counter: Counter?): Int? = counter?.value;
                """;
        final var classFile = Path.of("dist", programName + ".class");
        final var counterFile = Path.of("dist", "Counter.class");
        final var contractFile = Path.of("dist", "CounterView.class");
        final var autoFile = Path.of("dist", "AutoValues.class");

        try {
            final var compiler = new CompilationService(parse(source), programName);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals(42, program.getMethod("customResult").invoke(null));
                assertEquals(1, program.getMethod("compoundReceiverCount").invoke(null));
                final var counterClass = loader.loadClass("Counter");
                final var counterConstructor = counterClass.getDeclaredConstructor(int.class);
                counterConstructor.setAccessible(true);
                assertEquals(11, program.getMethod("contractResult",
                        loader.loadClass("CounterView")).invoke(null, counterConstructor.newInstance(10)));
                assertEquals(1, program.getMethod("autoResult").invoke(null));
                assertEquals(null, program.getMethod("safeResult", counterClass).invoke(null, new Object[]{null}));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(counterFile);
            Files.deleteIfExists(contractFile);
            Files.deleteIfExists(autoFile);
        }
    }

    @Test
    public void propertyAssignmentRequiresMutablePropertyAndReceiver() {
        final var readOnlyPropertyWrite = """
                class ReadOnlyProperty {
                    public property value: Int;
                }
                fn write(value: &ReadOnlyProperty): Unit {
                    value.value = 1;
                }
                """;
        final var immutableReceiverWrite = """
                class MutableProperty {
                    public mut property value: Int;
                }
                fn write(value: MutableProperty): Unit {
                    value.value = 1;
                }
                """;
        assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolve(parse(readOnlyPropertyWrite)));
        assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolve(parse(immutableReceiverWrite)));
    }

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
    public void sealedContractsEmitJvmPermittedSubclassesAndRejectUnlistedClasses() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var contractName = "SealedContract" + suffix;
        final var firstName = "FirstVariant" + suffix;
        final var secondName = "SecondVariant" + suffix;
        final var programName = "SealedProgram" + suffix;
        final var source = """
                public sealed contract %s permits %s, %s {}
                public class %s is %s {}
                public class %s is %s {}
                """.formatted(contractName, firstName, secondName,
                firstName, contractName, secondName, contractName);
        final var contractFile = Path.of("dist", contractName + ".class");
        final var firstFile = Path.of("dist", firstName + ".class");
        final var secondFile = Path.of("dist", secondName + ".class");
        final var programFile = Path.of("dist", programName + ".class");

        try {
            final var compiler = new CompilationService(parse(source), programName);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var contract = loader.loadClass(contractName);
                assertTrue(contract.isSealed());
                assertEquals(List.of(firstName, secondName),
                        java.util.Arrays.stream(contract.getPermittedSubclasses())
                                .map(Class::getName).toList());
            }
        } finally {
            Files.deleteIfExists(contractFile);
            Files.deleteIfExists(firstFile);
            Files.deleteIfExists(secondFile);
            Files.deleteIfExists(programFile);
        }

        final var invalidSource = parse("""
                sealed contract Closed permits Allowed {}
                class Allowed is Closed {}
                class Intruder is Closed {}
                """);
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(invalidSource));
    }

    @Test
    public void sampleCallSitesUseConstructorVirtualAndInterfaceDispatch() throws Exception {
    compileCanonicalSample();
    final var program = ClassFile.of().parse(Path.of("dist", "test.class"));
    final var main = program.methods().stream()
        .filter(method -> method.methodName().equalsString("main")
            && method.methodType().equalsString("()Lzeron/lang/Unit;"))
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
    public void initializesFixedFieldsInDeclarationOrderWithoutConstructorArguments() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "InitializedFields" + suffix;
        final var programName = "InitializedFieldsProgram" + suffix;
        final var classFile = Path.of("dist", className + ".class");
        final var programFile = Path.of("dist", programName + ".class");
        final var source = """
                public class %s {
                    seed: Int;
                    doubled: Int = seed * 2;
                    result: Int = doubled + seed;
                    ratio: Float = 2.5;
                    enabled: Boolean = true;
                    public constructor new;
                    public resultValue(): Int = result;
                    public ratioValue(): Float = ratio;
                    public enabledValue(): Boolean = enabled;
                }
                class Snapshot<T> {
                    value: T;
                    copy: T = value;
                    public constructor new;
                    public read(): T = copy;
                }
                fn run(): Int {
                    let state = %s.new(7);
                    let snapshot = Snapshot<Int>.new(19);
                    return state.resultValue() + snapshot.read();
                }
                """.formatted(className, className);

        try {
            final var compiler = new CompilationService(parse(source), programName);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generatedClass = loader.loadClass(className);
                final var instance = generatedClass.getConstructor(int.class).newInstance(7);
                assertEquals(21, generatedClass.getMethod("resultValue").invoke(instance));
                assertEquals(2.5, generatedClass.getMethod("ratioValue").invoke(instance));
                assertEquals(true, generatedClass.getMethod("enabledValue").invoke(instance));
                assertEquals(40, loader.loadClass(programName).getMethod("run").invoke(null));
                assertEquals(1, generatedClass.getDeclaredConstructors().length);
                assertEquals(1, generatedClass.getDeclaredConstructors()[0].getParameterCount());
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(programFile);
        }
    }

    @Test
    public void rejectsInvalidFieldInitializersAndArgumentsForInitializedFields() {
        final var laterFieldRead = """
                class LaterField {
                    first: Int = second;
                    second: Int = 2;
                    public constructor new;
                }
                """;
        final var initializerCall = """
                fn value(): Int = 1;
                class CalledInitializer {
                    field: Int = value();
                    public constructor new;
                }
                """;
        final var wrongConstructorArity = """
                class FixedField {
                    required: Int;
                    fixed: Int = 2;
                    public constructor new;
                }
                fn invalid(): &FixedField = FixedField.new(1, 2);
                """;

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(laterFieldRead)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(initializerCall)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(wrongConstructorArity)));
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
            final var compiler = new CompilationService(parse(source), programName);
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

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(privateFieldSource)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(readonlyMutationSource)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(mutableCaptureSource)));
    }

    @Test
    public void resolvesImplicitThisReadsCallsAndLambdaCaptures() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "ImplicitThis" + suffix;
        final var programName = "ImplicitThisProgram" + suffix;
        final var classFile = Path.of("dist", className + ".class");
        final var programFile = Path.of("dist", programName + ".class");
        final var source = """
                class %s {
                    value: Int;
                    public constructor new;
                    private readValue(): Int = value;
                    private priority(): Int = value;
                    public readPublic(): Int = value;
                    public mut increment(): Unit {
                        this.value = value + 1;
                    }
                    public mut incrementTwice(): Unit {
                        increment();
                        increment();
                    }
                    public localShadow(): Int {
                        let value = 90;
                        return value;
                    }
                    public parameterShadow(value: Int): Int = value;
                    public callShadow(): Int = readValue();
                    public fieldClosure(): Int {
                        let read = () -> value;
                        return read();
                    }
                    public methodClosure(): Int {
                        let read = () -> readValue();
                        return read();
                    }
                    public globalPriority(): Int = priority();
                }
                fn priority(): Int = 100;
                fn run(): Int {
                    let mut value = %s.new(2);
                    value.incrementTwice();
                    return value.readPublic() + value.localShadow() + value.parameterShadow(3)
                        + value.callShadow() + value.fieldClosure() + value.methodClosure()
                        + value.globalPriority();
                }
                """.formatted(className, className);

        try {
            final var compiler = new CompilationService(parse(source), programName);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(209, loader.loadClass(programName).getMethod("run").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(programFile);
        }
    }

    @Test
    public void requiresExplicitThisForFieldWritesAndMutableReceiverForImplicitCalls() {
        final var implicitFieldWrite = """
                class ImplicitWrite {
                    value: Int;
                    public constructor new;
                    public mut increment(): Unit {
                        value = value + 1;
                    }
                }
                """;
        final var readonlyMutatingCall = """
                class ImplicitMutation {
                    value: Int;
                    public constructor new;
                    private mut increment(): Unit {
                        this.value = value + 1;
                    }
                    public invalid(): Unit {
                        increment();
                    }
                }
                """;

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(implicitFieldWrite)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(readonlyMutatingCall)));
    }

    @Test
    public void implicitThisWorksInLambdasAcrossPackages() throws Exception {
        final var programName = "app.ImplicitThisPackageProgram";
        final var classFile = Path.of("dist", "model", "ImplicitThisPackageBox.class");
        final var programFile = Path.of("dist", "app", "ImplicitThisPackageProgram.class");
        final var boxUnit = Parser.of(Scanner.from("""
                package model;
                public class ImplicitThisPackageBox {
                    value: Int;
                    public constructor new;
                    private readValue(): Int = value;
                    public fieldReader(): () -> Int = () -> value;
                    public methodReader(): () -> Int = () -> readValue();
                    public mut fieldIncrementer(): () -> Unit = () -> this.value = value + 1;
                }
                """).scanTokens()).parseCompilationUnit("ImplicitThisPackageBox.zn");
        final var appUnit = Parser.of(Scanner.from("""
                package app;
                import model.ImplicitThisPackageBox;
                fn result(): Int {
                    let box = ImplicitThisPackageBox.new(7);
                    let readField = box.fieldReader();
                    let readMethod = box.methodReader();
                    let mut mutableBox = box;
                    let increment = mutableBox.fieldIncrementer();
                    increment();
                    return readField() + readMethod();
                }
                """).scanTokens()).parseCompilationUnit("ImplicitThisPackageMain.zn");

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(appUnit, boxUnit), programName, "app");
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(16, loader.loadClass(programName).getMethod("result").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(programFile);
        }
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

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
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
                public contract %s {
                    name(): String;
                }
                public contract %s {
                    name(): String;
                }
                public contract %s {
                    description(): String;
                }
                public class %s is %s, %s, %s {
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
            final var compiler = new CompilationService(parse(source), programName);
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
            final var compiler = new CompilationService(parse(source), programName);
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
    public void genericClassArgumentsInferAndRemainInvariant() {
        final var source = """
                class Box<T> {
                    value: T;
                    public constructor new;
                }
                fn mismatch(): Unit {
                    let box: Box<String> = Box<Int>.new(1);
                }
                """;
        final var unconstrainedConstruction = """
                class Empty<T> {
                    public constructor new;
                }
                fn missingTypeArgument(): Unit {
                    let empty = Empty.new();
                }
                """;

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
        assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolve(parse(unconstrainedConstruction)));
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
            final var compiler = new CompilationService(parse(source), programName);
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

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(missingReturn)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(usesThis)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(wrongReturn)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(bypassPrivateCanonical)));
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
            final var compiler = new CompilationService(parse(source), programName);
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

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
    }

    @Test
    public void covariantContractReturnsUseNominalCompatibilityAndErasedBridges() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var programName = "CovariantContractProgram" + suffix;
        final var entityName = "CovariantEntity" + suffix;
        final var concreteName = "CovariantConcrete" + suffix;
        final var factoryName = "CovariantFactory" + suffix;
        final var genericFactoryName = "CovariantGenericFactory" + suffix;
        final var implementationName = "CovariantDefaultFactory" + suffix;
        final var source = """
                contract %s {
                    name(): String;
                }
                class %s is %s {
                    public constructor new;
                    public name(): String = "zeron";
                }
                contract %s {
                    create(): %s;
                }
                contract %s<T> {
                    create(): T;
                }
                class %s is %s, %s<%s> {
                    public constructor new;
                    public create(): %s = %s.new();
                }
                fn readNamed(factory: %s): String = factory.create().name();
                fn readGeneric(factory: %s<%s>): String = factory.create().name();
                fn namedTest(): String = readNamed(%s.new());
                fn genericTest(): String = readGeneric(%s.new());
                """.formatted(entityName, concreteName, entityName, factoryName, entityName,
                genericFactoryName, implementationName, factoryName, genericFactoryName, entityName,
                concreteName, concreteName, factoryName, genericFactoryName, entityName,
                implementationName, implementationName);
        final var programFile = Path.of("dist", programName + ".class");
        final var entityFile = Path.of("dist", entityName + ".class");
        final var concreteFile = Path.of("dist", concreteName + ".class");
        final var factoryFile = Path.of("dist", factoryName + ".class");
        final var genericFactoryFile = Path.of("dist", genericFactoryName + ".class");
        final var implementationFile = Path.of("dist", implementationName + ".class");

        try {
            final var compiler = new CompilationService(parse(source), programName);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var implementation = loader.loadClass(implementationName);
                final var entityClass = loader.loadClass(entityName);
                final var bridges = java.util.Arrays.stream(implementation.getDeclaredMethods())
                        .filter(method -> method.getName().equals("create") && method.isBridge())
                        .toList();
                assertEquals(2, bridges.size());
                assertTrue(bridges.stream().anyMatch(method -> method.getReturnType().equals(entityClass)));
                assertTrue(bridges.stream().anyMatch(method -> method.getReturnType().equals(Object.class)));

                final var program = loader.loadClass(programName);
                assertEquals("zeron", program.getMethod("namedTest").invoke(null));
                assertEquals("zeron", program.getMethod("genericTest").invoke(null));
            }
        } finally {
            Files.deleteIfExists(programFile);
            Files.deleteIfExists(entityFile);
            Files.deleteIfExists(concreteFile);
            Files.deleteIfExists(factoryFile);
            Files.deleteIfExists(genericFactoryFile);
            Files.deleteIfExists(implementationFile);
        }
    }

    @Test
    public void contractReturnCovarianceFollowsNullableAndInvariantParameterRules() {
        final var nullableRequirement = """
                contract Entity { name(): String; }
                contract Factory { create(): Entity?; }
                class DefaultFactory is Entity, Factory {
                    public constructor new;
                    public name(): String = "zeron";
                    public create(): Entity = this;
                }
                """;
        final var nullableImplementation = """
                contract Entity { name(): String; }
                contract Factory { create(): Entity; }
                class DefaultFactory is Entity, Factory {
                    public constructor new;
                    public name(): String = "zeron";
                    public create(): Entity? = null;
                }
                """;
        final var parameterMismatch = """
                contract Factory { create(value: Any): Any; }
                class DefaultFactory is Factory {
                    public constructor new;
                    public create(value: String): String = value;
                }
                """;
        final var primitiveMismatch = """
                contract NumericFactory { create(): Float; }
                class IntegerFactory is NumericFactory {
                    public constructor new;
                    public create(): Int = 1;
                }
                """;

        new ResolutionService().resolve(parse(nullableRequirement));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(nullableImplementation)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(parameterMismatch)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(primitiveMismatch)));
    }

    @Test
    public void defaultContractMethodsDispatchThroughInterfacesAndCanSatisfyRequirements() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var programName = "DefaultContractProgram" + suffix;
        final var namedName = "DefaultNamed" + suffix;
        final var personName = "DefaultPerson" + suffix;
        final var overrideName = "DefaultOverride" + suffix;
        final var productName = "DefaultProduct" + suffix;
        final var fancyProductName = "DefaultFancyProduct" + suffix;
        final var makerName = "DefaultMaker" + suffix;
        final var productMakerName = "DefaultProductMaker" + suffix;
        final var bothMakerName = "DefaultBothMaker" + suffix;
        final var counterViewName = "DefaultCounterView" + suffix;
        final var counterName = "DefaultCounter" + suffix;
        final var source = """
                contract %s {
                    property name: String;
                    default label(): String = this.name;
                }
                class %s is %s {
                    public property name: String;
                    public constructor new;
                }
                class %s is %s {
                    public property name: String;
                    public constructor new;
                    public label(): String = "override";
                }
                contract %s { name(): String; }
                class %s is %s {
                    public constructor new;
                    public name(): String = "fancy";
                }
                contract %s {
                    default create(): %s = %s.new();
                }
                contract %s { create(): %s; }
                class %s is %s, %s { public constructor new; }
                contract %s {
                    mut property count: Int;
                    default mut increment(): Unit { this.count += 1; }
                }
                class %s is %s {
                    public mut property count: Int;
                    public constructor new;
                }
                fn readLabel(value: %s): String = value.label();
                fn readProduct(value: %s): String = value.create().name();
                fn increment(value: &%s): Int {
                    value.increment();
                    return value.count;
                }
                fn defaultTest(): String = readLabel(%s.new("Ada"));
                fn overrideTest(): String = readLabel(%s.new("Ada"));
                fn crossContractDefaultTest(): String = readProduct(%s.new());
                fn mutatingDefaultTest(): Int {
                    let mut value = %s.new(4);
                    value.increment();
                    return value.count;
                }
                """.formatted(namedName, personName, namedName, overrideName, namedName,
                productName, fancyProductName, productName, makerName, fancyProductName, fancyProductName,
                productMakerName, productName, bothMakerName, makerName, productMakerName,
                counterViewName, counterName, counterViewName, namedName, productMakerName,
                counterViewName, personName, overrideName, bothMakerName, counterName);
        final var generatedNames = List.of(programName, namedName, personName, overrideName, productName,
                fancyProductName, makerName, productMakerName, bothMakerName, counterViewName, counterName);
        try {
            final var compiler = new CompilationService(parse(source), programName);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertEquals("Ada", program.getMethod("defaultTest").invoke(null));
                assertEquals("override", program.getMethod("overrideTest").invoke(null));
                assertEquals("fancy", program.getMethod("crossContractDefaultTest").invoke(null));
                assertEquals(5, program.getMethod("mutatingDefaultTest").invoke(null));
                final var bothMaker = loader.loadClass(bothMakerName);
                final var bridge = java.util.Arrays.stream(bothMaker.getDeclaredMethods())
                        .filter(method -> method.getName().equals("create") && method.isBridge())
                        .findFirst()
                        .orElseThrow();
                assertEquals(loader.loadClass(productName), bridge.getReturnType());
            }
        } finally {
            for (final var name : generatedNames) Files.deleteIfExists(Path.of("dist", name + ".class"));
        }
    }

    @Test
    public void competingDefaultMethodsRequireClassOverride() {
        final var source = """
                contract First { default value(): String = "first"; }
                contract Second { default value(): String = "second"; }
                class Ambiguous is First, Second { public constructor new; }
                """;
        final var differingMutability = """
                contract MutableFirst { default mut value(): Int = 1; }
                contract ReadOnlySecond { default value(): Int = 2; }
                class Ambiguous is MutableFirst, ReadOnlySecond { public constructor new; }
                """;
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(source)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(differingMutability)));
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

        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(duplicate)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse(conflicting)));
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }

    private static void compileCanonicalSample() throws Exception {
        final var source = Files.readString(Path.of("src/main/resources/test.zn"));
        final var unit = Parser.of(Scanner.from(source).scanTokens())
            .parseCompilationUnit("src/main/resources/test.zn");
        final var compiler = CompilationService.forCompilationUnits(List.of(unit), "test", "");
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