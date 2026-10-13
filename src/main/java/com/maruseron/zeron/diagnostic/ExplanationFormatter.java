package com.maruseron.zeron.diagnostic;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

public final class ExplanationFormatter {
    private static final int LINE_WIDTH = 80;

    private ExplanationFormatter() {}

    public static String format(final String markdown) {
        return format(markdown, false);
    }

    public static String format(final String markdown, final boolean colorizeZeronCode) {
        Objects.requireNonNull(markdown);
        final var output = new StringBuilder();
        final var paragraph = new StringBuilder();
        final var codeBlock = new ArrayList<String>();
        var inCodeBlock = false;
        var codeLanguage = "";

        for (final var line : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (line.startsWith("```")) {
                if (inCodeBlock) {
                    appendCodeBlock(output, codeBlock, codeLanguage, colorizeZeronCode);
                    codeBlock.clear();
                    inCodeBlock = false;
                } else {
                    appendParagraph(output, paragraph);
                    inCodeBlock = true;
                    codeLanguage = line.substring(3).trim().toLowerCase(Locale.ROOT);
                }
                continue;
            }

            if (inCodeBlock) {
                codeBlock.add(line);
            } else if (line.startsWith("# ")) {
                appendParagraph(output, paragraph);
                final var title = line.substring(2).replaceFirst("^Error code\\s+", "");
                appendBlockLine(output, title);
            } else if (line.startsWith("## ")) {
                appendParagraph(output, paragraph);
                appendBlankLine(output);
                appendBlockLine(output, line.substring(3).replaceAll("[*_`]", "") + ":");
            } else if (line.isBlank()) {
                appendParagraph(output, paragraph);
            } else {
                if (!paragraph.isEmpty()) paragraph.append(' ');
                paragraph.append(line.trim());
            }
        }

        appendParagraph(output, paragraph);
        if (inCodeBlock) appendCodeBlock(output, codeBlock, codeLanguage, colorizeZeronCode);
        return output.toString();
    }

    private static void appendParagraph(final StringBuilder output, final StringBuilder paragraph) {
        if (paragraph.isEmpty()) return;
        appendBlankLine(output);
        appendWrapped(output, stripInlineMarkdown(paragraph.toString()));
        paragraph.setLength(0);
    }

    private static void appendWrapped(final StringBuilder output, final String text) {
        var lineLength = 0;
        for (final var word : text.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (lineLength > 0 && lineLength + word.length() + 1 > LINE_WIDTH) {
                output.append(System.lineSeparator());
                lineLength = 0;
            }
            if (lineLength > 0) {
                output.append(' ');
                lineLength++;
            }
            output.append(word);
            lineLength += word.length();
        }
        output.append(System.lineSeparator());
    }

    private static void appendCodeBlock(final StringBuilder output,
                                        final ArrayList<String> lines,
                                        final String language,
                                        final boolean colorizeZeronCode) {
        while (!lines.isEmpty() && lines.getLast().isBlank()) lines.removeLast();
        if (lines.isEmpty()) return;
        appendBlankLine(output);
        final var source = String.join("\n", lines);
        final var rendered = colorizeZeronCode && language.equals("zeron")
                ? AnsiSyntaxHighlighter.highlight(source)
                : source;
        for (final var line : rendered.split("\n", -1)) {
            output.append(line.isEmpty() ? "" : "    " + line)
                    .append(System.lineSeparator());
        }
    }

    private static void appendBlockLine(final StringBuilder output, final String line) {
        output.append(line).append(System.lineSeparator());
    }

    private static void appendBlankLine(final StringBuilder output) {
        final var lineSeparator = System.lineSeparator();
        if (!output.isEmpty() && (output.length() < lineSeparator.length() * 2
                || !output.substring(output.length() - lineSeparator.length() * 2)
                        .equals(lineSeparator + lineSeparator))) {
            output.append(System.lineSeparator());
        }
    }

    private static String stripInlineMarkdown(final String text) {
        return text.replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("\\*(.+?)\\*", "$1")
                .replaceAll("\\[([^\\]]+)]\\(([^)]+)\\)", "$1 ($2)");
    }
}
