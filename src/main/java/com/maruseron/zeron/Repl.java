package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeFormatter;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.TokenType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.IO.print;
import static java.lang.IO.println;

final class Repl {
    private static final AtomicInteger SUBMISSION_ID = new AtomicInteger();

    private record Binding(String name, TypeDescriptor type, boolean mutable, Object value) {}

    private Repl() {}

    static void runPrompt() throws IOException {
        final var outputDirectory = Paths.get("dist");
        Files.createDirectories(outputDirectory);
        final var outputUrl = outputDirectory.toAbsolutePath().normalize().toUri().toURL();
        final var bindings = new LinkedHashMap<String, Binding>();
        final var imports = new ArrayList<String>();
        try (final var reader = new BufferedReader(new InputStreamReader(System.in));
             final var classLoader = new URLClassLoader(new java.net.URL[]{outputUrl},
                     Zeron.class.getClassLoader())) {
            final var submission = new StringBuilder();
            for (;;) {
                print(submission.isEmpty() ? "zeron > " : "... ");
                final var line = reader.readLine();
                if (line == null) {
                    if (!submission.isEmpty()) {
                        System.err.println("Incomplete REPL submission discarded at end of input.");
                    }
                    break;
                }
                if (submission.isEmpty()) {
                    final var command = line.trim();
                    if (command.equals(":quit") || command.equals(":q")) break;
                    if (command.equals(":help")) {
                        println("Enter an expression to evaluate. Use :quit to exit and :help for help.");
                        continue;
                    }
                    if (command.startsWith(":")) {
                        System.err.println("Unknown REPL command: " + command);
                        continue;
                    }
                }
                if (!submission.isEmpty()) submission.append(System.lineSeparator());
                submission.append(line);
                if (!isCompleteSubmission(submission.toString())) continue;
                final var expression = submission.toString().trim();
                submission.setLength(0);
                if (!expression.isEmpty()) {
                    evaluateSubmission(expression, imports, bindings, classLoader, outputDirectory);
                }
            }
        }
    }

    private static boolean isCompleteSubmission(final String source) {
        final Deque<Character> delimiters = new ArrayDeque<>();
        var inString = false;
        var inBlockComment = false;
        for (var index = 0; index < source.length(); index++) {
            final var character = source.charAt(index);
            final var next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (inString) {
                if (character == '"') inString = false;
                continue;
            }
            if (inBlockComment) {
                if (character == '*' && next == '/') {
                    inBlockComment = false;
                    index++;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '/' && next == '/') {
                while (index < source.length() && source.charAt(index) != '\n') index++;
            } else if (character == '/' && next == '*') {
                inBlockComment = true;
                index++;
            } else if (character == '(' || character == '{' || character == '[') {
                delimiters.push(character);
            } else if (character == ')' || character == '}' || character == ']') {
                if (delimiters.isEmpty() || !matchesDelimiter(delimiters.peek(), character)) return true;
                delimiters.pop();
            }
        }
        if (inString || inBlockComment || !delimiters.isEmpty()) return false;
        final var scan = Scanner.from(source).scanWithDiagnostics();
        final var tokens = scan.tokens().stream()
                .filter(token -> !token.type().isComment()).toList();
        if (tokens.size() <= 1) return true;
        final var lastToken = tokens.get(tokens.size() - 2).type();
        return !isContinuationToken(lastToken);
    }

    private static boolean matchesDelimiter(final char opening, final char closing) {
        return opening == '(' && closing == ')'
                || opening == '{' && closing == '}'
                || opening == '[' && closing == ']';
    }

    private static boolean isContinuationToken(final TokenType tokenType) {
        return switch (tokenType) {
            case COMMA, DOT, HUH_DOT, HUH_HUH, PLUS, MINUS, STAR, SLASH, PERCENT,
                    EQUAL_EQUAL, EQUAL_EQUAL_EQUAL, BANG_EQUAL, GREATER, GREATER_EQUAL,
                    LESS, LESS_EQUAL, AND, OR, IS, AS, PIPE, ARROW, COLON, EQUAL,
                    SHIFT_LEFT, SHIFT_RIGHT, UNSIGNED_SHIFT_RIGHT, DOT_DOT,
                    AMPERSAND, CARET, HUH, HUH_HUH_EQUAL -> true;
            default -> false;
        };
    }

    private static void evaluateSubmission(final String input,
                                           final List<String> imports,
                                           final LinkedHashMap<String, Binding> bindings,
                                           final URLClassLoader classLoader,
                                           final Path outputDirectory) throws IOException {
        Zeron.beginReplSubmission();
        final var submissionId = SUBMISSION_ID.incrementAndGet();
        final var className = "ZeronRepl" + submissionId;
        final var sourcePath = className + ".zn";
        final var firstToken = Scanner.from(input).scanWithDiagnostics().tokens().stream()
                .filter(token -> !token.type().isComment())
                .findFirst()
                .orElseThrow();
        if (firstToken.type() == TokenType.IMPORT) {
            rememberImports(input, sourcePath, imports);
            return;
        }
        final var isDeclaration = firstToken.type() == TokenType.LET;
        final var declaredName = isDeclaration ? declarationName(input) : null;
        if (declaredName != null && bindings.containsKey(declaredName)) {
            System.err.println("REPL binding '" + declaredName + "' is already declared.");
            return;
        }
        final var functionName = "zeronReplEval";
        final var resultName = "zeronReplResult" + submissionId;
        final var generated = new StringBuilder();
        for (final var importDeclaration : imports) {
            generated.append(importDeclaration).append(System.lineSeparator());
        }
        generated.append("fn ").append(functionName).append('(');
        var parameterIndex = 0;
        for (final var binding : bindings.values()) {
            if (parameterIndex > 0) generated.append(", ");
            generated.append("zeronReplInput").append(parameterIndex)
                    .append(": ").append(TypeFormatter.format(binding.type()));
            parameterIndex++;
        }

        generated.append("): Array<Any?> {\n");
        parameterIndex = 0;
        for (final var binding : bindings.values()) {
            generated.append("let ");
            if (binding.mutable()) generated.append("mut ");
            generated.append(binding.name()).append(": ").append(TypeFormatter.format(binding.type()))
                    .append(" = zeronReplInput").append(parameterIndex++).append(";\n");
        }
        if (isDeclaration) {
            generated.append(input);
            if (!input.stripTrailing().endsWith(";")) generated.append(';');
            generated.append('\n');
            generated.append("let ").append(resultName).append(" = ").append(declaredName).append(";\n");
        } else {
            final var expression = input.stripTrailing();
            final var expressionWithoutTerminator = expression.endsWith(";")
                    ? expression.substring(0, expression.length() - 1).stripTrailing()
                    : expression;
            generated.append("let ").append(resultName).append(" = (\n")
                    .append(expressionWithoutTerminator).append("\n);\n");
        }
        final var boxedResultName = "zeronReplBoxedResult" + submissionId;
        generated.append("let ").append(boxedResultName).append(": Any? = ")
                .append(resultName).append(";\n");
        final var boxedBindingNames = new ArrayList<String>();
        var bindingIndex = 0;
        for (final var binding : bindings.values()) {
            final var boxedBindingName = "zeronReplBoxedBinding" + submissionId + "_" + bindingIndex++;
            generated.append("let ").append(boxedBindingName).append(": Any? = ")
                    .append(binding.name()).append(";\n");
            boxedBindingNames.add(boxedBindingName);
        }
        final String boxedNewBindingName;
        if (declaredName != null) {
            boxedNewBindingName = "zeronReplBoxedNewBinding" + submissionId;
            generated.append("let ").append(boxedNewBindingName).append(": Any? = ")
                    .append(declaredName).append(";\n");
        } else {
            boxedNewBindingName = null;
        }
        generated.append("return [").append(boxedResultName);
        for (final var boxedBindingName : boxedBindingNames) {
            generated.append(", ").append(boxedBindingName);
        }
        if (boxedNewBindingName != null) generated.append(", ").append(boxedNewBindingName);
        generated.append("];\n}");
        final var source = generated.toString();
        Zeron.rememberSource(sourcePath, source);
        final var parsed = Parser.of(Scanner.from(source, sourcePath).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics(sourcePath);
        Zeron.reportParseDiagnostics(parsed.diagnostics());
        if (Zeron.hadError) return;

        final var compiler = CompilationService.forCompilationUnits(List.of(parsed.compilationUnit()),
                className, "", List.of(), true, List.of(), outputDirectory);
        final var resolution = compiler.resolveWithDiagnostics();
        resolution.errors().forEach(Zeron::reportResolutionDiagnostic);
        if (!resolution.errors().isEmpty()) return;

        final Stmt.Var newBinding;
        if (isDeclaration) {
            final var function = (Stmt.Function) parsed.compilationUnit().declarations().getFirst();
            newBinding = function.body().stream()
                    .filter(Stmt.Var.class::isInstance)
                    .map(Stmt.Var.class::cast)
                    .filter(variable -> variable.name().lexeme().equals(declaredName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Parsed REPL declaration was not found."));
        } else {
            newBinding = null;
        }
        final TypeDescriptor newBindingType = newBinding == null ? null
                : newBinding.type() instanceof com.maruseron.zeron.domain.InferDescriptor
                    ? newBinding.initializer().getType()
                    : newBinding.type();
        final var compilation = compiler.compileWithDiagnostics();
        compilation.diagnostics().forEach(Zeron::reportCompilationDiagnostic);
        if (!compilation.successful()) return;
        try {
            final var arguments = bindings.values().stream().map(Binding::value).toArray();
            final var result = Class.forName(className, true, classLoader)
                    .getMethod(functionName, parameterClasses(bindings.values(), classLoader))
                    .invoke(null, arguments);
            if (!(result instanceof Object[] values) || values.length != bindings.size() + 1
                    + (newBinding == null ? 0 : 1)) {
                throw new IOException("REPL generated an invalid binding state result.");
            }
            var index = 1;
            for (final var binding : List.copyOf(bindings.values())) {
                bindings.put(binding.name(), new Binding(binding.name(), binding.type(),
                        binding.mutable(), values[index++]));
            }
            if (newBinding != null) {
                final var binding = new Binding(declaredName, newBindingType,
                        newBinding.mutability().isReassignable(), values[index]);
                bindings.put(declaredName, binding);
            } else {
                println(String.valueOf(values[0]));
            }
        } catch (final InvocationTargetException exception) {
            final var cause = exception.getCause();
            System.err.println("REPL evaluation failed: "
                    + (cause == null ? exception.getMessage() : cause.toString()));
        } catch (final ReflectiveOperationException exception) {
            throw new IOException("Could not invoke the compiled REPL expression.", exception);
        }
    }

    private static void rememberImports(final String input,
                                        final String sourcePath,
                                        final List<String> imports) {
        Zeron.rememberSource(sourcePath, input);
        final var parsed = Parser.of(Scanner.from(input, sourcePath).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics(sourcePath);
        Zeron.reportParseDiagnostics(parsed.diagnostics());
        if (Zeron.hadError) return;
        if (!parsed.compilationUnit().declarations().isEmpty()
                || parsed.compilationUnit().imports().isEmpty()) {
            System.err.println("An import submission may contain only import declarations.");
            return;
        }
        final var resolution = CompilationService.forCompilationUnits(List.of(parsed.compilationUnit()),
                sourcePath.replaceFirst("\\.zn$", ""), "", List.of(), true, List.of())
                .resolveWithDiagnostics();
        resolution.errors().forEach(Zeron::reportResolutionDiagnostic);
        if (!resolution.errors().isEmpty()) return;
        if (!imports.contains(input.strip())) imports.add(input.strip());
    }

    private static Class<?>[] parameterClasses(final java.util.Collection<Binding> bindings,
                                               final ClassLoader classLoader) throws IOException {
        final var parameterTypes = new ArrayList<Class<?>>();
        try {
            for (final var binding : bindings) {
                parameterTypes.add(classForDescriptor(binding.type(), classLoader));
            }
        } catch (final ClassNotFoundException exception) {
            throw new IOException("Could not resolve a persistent REPL binding type.", exception);
        }
        return parameterTypes.toArray(Class<?>[]::new);
    }

    private static Class<?> classForDescriptor(final TypeDescriptor type,
                                               final ClassLoader classLoader) throws ClassNotFoundException {
        final var descriptor = TypeDescriptor.toJavaClassDesc(type).descriptorString();
        return switch (descriptor) {
            case "I" -> int.class;
            case "D" -> double.class;
            case "Z" -> boolean.class;
            default -> Class.forName(descriptor.startsWith("L")
                    ? descriptor.substring(1, descriptor.length() - 1).replace('/', '.')
                    : descriptor.replace('/', '.'), false, classLoader);
        };
    }

    private static String declarationName(final String input) {
        final var tokens = Scanner.from(input).scanWithDiagnostics().tokens().stream()
                .filter(token -> !token.type().isComment()).toList();
        var tokenIndex = 1;
        if (tokens.get(tokenIndex).type() == TokenType.MUT) tokenIndex++;
        return tokens.get(tokenIndex).type() == TokenType.IDENTIFIER
                ? tokens.get(tokenIndex).lexeme()
                : null;
    }
}
