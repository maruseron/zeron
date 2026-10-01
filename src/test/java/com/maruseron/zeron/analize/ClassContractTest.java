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
            && method.methodType().equalsString("()Ljava/lang/Void;"))
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