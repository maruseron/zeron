package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class UniqueCallableNamesTest {
    @Test
    public void supportsOptionalArgumentsAndGenericInferenceWithoutOverloads() throws Exception {
        final var programName = "UniqueCalls" + UUID.randomUUID().toString().replace("-", "");
        final var programFile = Path.of("dist", programName + ".class");
        final var statements = parse("""
                fn identity<T>(value: T): T = value;
                fn append(value: String, suffix: String = "!"): String = value + suffix;
                fn call() = append(identity("ok"));
                """);
        final var compiler = new CompilationService(statements, programName);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals("ok!", loader.loadClass(programName).getMethod("call").invoke(null));
            }
        } finally {
            Files.deleteIfExists(programFile);
        }
    }

    @Test
    public void rejectsSameNamedTopLevelFunctionsRegardlessOfSignature() {
        final var result = new ResolutionService().resolveWithDiagnostics(parse("""
                fn choose(value: Int): String = "int";
                fn choose<T>(value: T, suffix: String = ""): String = "generic";
                """));
        assertTrue(result.errors().toString(),
                result.errors().stream().anyMatch(error -> error.message().contains("Function name")));
    }

    @Test
    public void rejectsSameNamedClassAndContractMethods() {
        final var classResult = new ResolutionService().resolveWithDiagnostics(parse("""
                class Picker {
                    public choose(value: Int): String = "int";
                    public choose(value: String): String = "string";
                }
                """));
        assertTrue(classResult.errors().toString(),
                classResult.errors().stream().anyMatch(error -> error.message().contains("Duplicate class member")));

        final var contractResult = new ResolutionService().resolveWithDiagnostics(parse("""
                contract Picker {
                    choose(value: Int): String;
                    choose(value: String): String;
                }
                """));
        assertTrue(contractResult.errors().toString(),
                contractResult.errors().stream()
                        .anyMatch(error -> error.message().contains("Contract method name")));
    }

    private List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}
