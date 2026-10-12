package com.maruseron.zeron;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ReplTest {
    @Test
    public void evaluatesSingleAndMultilineExpressionsUntilQuit() throws Exception {
        final var output = new ByteArrayOutputStream();
        final var originalIn = System.in;
        final var originalOut = System.out;
        try {
            System.setIn(new ByteArrayInputStream(
                    ("1 + 2\n(10 +\n 32)\nlet answer = 40\nanswer + 2\n"
                            + "let greeting = \"hello\"\ngreeting\n:quit\n")
                            .getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            final var exitCode = Zeron.runCli();
            assertEquals(output.toString(StandardCharsets.UTF_8), 0, exitCode);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            deleteTree(Path.of("dist"));
        }

        final var transcript = output.toString(StandardCharsets.UTF_8);
        assertTrue(transcript, transcript.contains("3"));
        assertTrue(transcript, transcript.contains("42"));
        assertTrue(transcript, transcript.contains("hello"));
        assertTrue(transcript, transcript.contains("... "));
    }

    @Test
    public void preservesAndMutatesSessionBindingsAndRejectsDuplicates() throws Exception {
        final var output = new ByteArrayOutputStream();
        final var error = new ByteArrayOutputStream();
        final var originalIn = System.in;
        final var originalOut = System.out;
        final var originalErr = System.err;
        try {
            System.setIn(new ByteArrayInputStream(("""
                    let mut count: Int = 40
                    count = count + 1
                    count
                    let count = 0
                    count
                    :quit
                    """).getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(error, true, StandardCharsets.UTF_8));
            final var exitCode = Zeron.runCli();
            assertEquals(output.toString(StandardCharsets.UTF_8), 0, exitCode);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
            deleteTree(Path.of("dist"));
        }

        final var transcript = output.toString(StandardCharsets.UTF_8)
                + error.toString(StandardCharsets.UTF_8);
        assertTrue(transcript, transcript.contains("41"));
        assertTrue(transcript, transcript.contains("already declared"));
    }

    @Test
    public void reportsAnInvalidExpressionAndContinuesTheSession() throws Exception {
        final var output = new ByteArrayOutputStream();
        final var originalIn = System.in;
        final var originalOut = System.out;
        try {
            System.setIn(new ByteArrayInputStream(
                    "missing\n40 + 2\n:help\n:quit\n".getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            final var exitCode = Zeron.runCli();
            assertEquals(output.toString(StandardCharsets.UTF_8), 0, exitCode);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            deleteTree(Path.of("dist"));
        }

        final var transcript = output.toString(StandardCharsets.UTF_8);
        assertTrue(transcript, transcript.contains("Unknown symbol: 'missing'"));
        assertTrue(transcript, transcript.contains("42"));
        assertTrue(transcript, transcript.contains("Use :quit to exit"));
    }

    @Test
    public void evaluatesCollectedForExpression() throws Exception {
        final var output = new ByteArrayOutputStream();
        final var error = new ByteArrayOutputStream();
        final var originalIn = System.in;
        final var originalOut = System.out;
        final var originalErr = System.err;
        try {
            System.setIn(new ByteArrayInputStream(
                    ("import zeron.collections.List;\n"
                            + "for [1, 2, 3, 4, 5] map i -> i * 2 collect List<Int>.empty();\n"
                            + "let mapped = for [1, 2, 3, 4, 5] map i -> i * 2 "
                            + "collect List<Int>.empty();\nmapped.size\n:quit\n")
                            .getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(error, true, StandardCharsets.UTF_8));
            final var exitCode = Zeron.runCli();
            assertEquals(output.toString(StandardCharsets.UTF_8) + error.toString(StandardCharsets.UTF_8),
                    0, exitCode);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
            deleteTree(Path.of("dist"));
        }
        final var transcript = output.toString(StandardCharsets.UTF_8)
                + error.toString(StandardCharsets.UTF_8);
        assertTrue(transcript, transcript.contains("zeron.collections.List@"));
        assertTrue(transcript, output.toString(StandardCharsets.UTF_8).contains("5"));
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
