package com.maruseron.zeron;

import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

import static java.lang.IO.println;

public class Zeron {
    static boolean hadError = false;
    static boolean hadResolutionError = false;
    private static boolean debugEnabled;

    static void main(final String... args) throws IOException {
        debugEnabled = false;
        String scriptPath = null;
        for (final var argument : args) {
            if (argument.equals("--debug") || argument.equals("-d")) {
                debugEnabled = true;
            } else if (scriptPath == null) {
                scriptPath = argument;
            } else {
                println("Usage: zeron [--debug] [script]");
                System.exit(64);
                return;
            }
        }
        if (scriptPath == null) runPrompt();
        else runFile(scriptPath);
    }

    public static void debug(final String message) {
        if (debugEnabled) System.err.println(message);
    }

    private static void runFile(final String path) throws IOException {
        final var sourcePath = Paths.get(path);
        final var bytes = Files.readAllBytes(sourcePath);

        run(new String(bytes, Charset.defaultCharset()), sourceClassName(sourcePath));

        if (hadError) System.exit(65);
        if (hadResolutionError) System.exit(71);
    }

    private static void runPrompt() throws IOException {
        try (final var reader = new BufferedReader(new InputStreamReader(System.in))) {
            for (;;) {
                println("> ");
                final var line = reader.readLine();
                if (line == null) break;
                run(line);
                hadError = false;
            }
        }
    }

    private static void run(final String source) throws IOException {
        run(source, "ZeronMain");
    }

    private static void run(final String source, final String outputClassName) throws IOException {
        final var scanner = Scanner.from(source);
        final var tokens = scanner.scanTokens();
        final var parser = Parser.of(tokens);
        final var stmts = parser.parse();

        if (hadError) return;

        final var compiler = new Compiler(stmts, outputClassName);
        compiler.resolve();

        if (hadResolutionError) return;

        compiler.compile();
    }

    private static String sourceClassName(final java.nio.file.Path sourcePath) {
        final var fileName = sourcePath.getFileName().toString();
        final var extensionStart = fileName.lastIndexOf('.');
        final var className = extensionStart > 0 ? fileName.substring(0, extensionStart) : fileName;
        if (className.isBlank() || className.contains(".")) {
            throw new IllegalArgumentException(
                    "Source filename must produce a valid default-package class name: " + fileName);
        }
        return className;
    }

    public static void error(final int line, final String message) {
        report(line, "", message);
    }

    private static void report(final int line, final String where, final String message) {
        println("[line " + line + "] Error" + where + ": " + message);
        hadError = true;
    }

    public static void error(final Token token, final String message) {
        if (token.type() == TokenType.EOF) {
            report(token.line(), " at end", message);
        } else {
            report(token.line(), " at '" + token.lexeme() + "'", message);
        }
    }

    public static void resolutionError(final ResolutionError error) {
        println(error.getMessage() + "\n[line " + error.token.line() + "]");
        hadResolutionError = true;
        throw error;
    }
}
