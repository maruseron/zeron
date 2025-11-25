package com.maruseron.zeron;

import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.interpret.Interpreter;
import com.maruseron.zeron.interpret.RuntimeError;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Zeron {
    private static final Interpreter interpreter = new Interpreter();
    static boolean hadError = false;
    static boolean hadRuntimeError = false;

    static void main(final String... args) throws IOException {
        if (args.length > 1) {
            System.out.println("Usage: zeron [script]");
            System.exit(64);
        } else if (args.length == 1) {
            runFile(args[0]);
        } else {
            runPrompt();
        }
    }

    private static void runFile(final String path) throws IOException {
        final var bytes = Files.readAllBytes(Paths.get(path));

        run(new String(bytes, Charset.defaultCharset()));

        if (hadError) System.exit(65);
        if (hadRuntimeError) System.exit(70);
    }

    private static void runPrompt() throws IOException {
        try (final var reader = new BufferedReader(new InputStreamReader(System.in))) {
            for (;;) {
                System.out.println("> ");
                final var line = reader.readLine();
                if (line == null) break;
                run(line);
                hadError = false;
            }
        }
    }

    private static void run(final String source) throws IOException {
        final var scanner = Scanner.from(source);
        final var tokens = scanner.scanTokens();
        final var parser = Parser.of(tokens);
        final var stmts = parser.parse();

        if (hadError) return;

        // final var compiler = new Compiler();
        // compiler.compile(stmts);
        interpreter.interpret(stmts);
    }

    public static void error(final int line, final String message) {
        report(line, "", message);
    }

    private static void report(final int line, final String where, final String message) {
        System.err.println(
                "[line " + line + "] Error" + where + ": " + message);
        hadError = true;
    }

    public static void error(final Token token, final String message) {
        if (token.type() == TokenType.EOF) {
            report(token.line(), " at end", message);
        } else {
            report(token.line(), " at '" + token.lexeme() + "'", message);
        }
    }

    public static void runtimeError(final RuntimeError error) {
        System.err.println(error.getMessage() +
                "\n[line " + error.token.line() + "]");
        hadRuntimeError = true;
    }
}
