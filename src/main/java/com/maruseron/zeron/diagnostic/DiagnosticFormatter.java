package com.maruseron.zeron.diagnostic;

import java.util.Objects;

public final class DiagnosticFormatter {
    @FunctionalInterface
    public interface SourceText {
        String line(String sourcePath, int lineNumber);
    }

    private DiagnosticFormatter() {}

    public static String format(final Diagnostic diagnostic, final SourceText sourceText) {
        return format(diagnostic, sourceText, false);
    }

    public static String format(final Diagnostic diagnostic,
                                final SourceText sourceText,
                                final boolean colorize) {
        Objects.requireNonNull(diagnostic);
        Objects.requireNonNull(sourceText);

        final var span = diagnostic.primarySpan();
        final var location = location(span.sourcePath(), span.start());
        final var severity = diagnostic.severity().name().toLowerCase(java.util.Locale.ROOT);
        final var severityColor = switch (diagnostic.severity()) {
            case ERROR -> "31";
            case WARNING -> "33";
            case INFO -> "36";
        };
        final var output = new StringBuilder(colorize ? AnsiColor.apply(location, "36") : location)
                .append(": ").append(colorize ? AnsiColor.apply(severity, severityColor) : severity)
                .append(colorize ? AnsiColor.apply("[" + diagnostic.code() + "]", "35")
                        : "[" + diagnostic.code() + "]")
                .append(": ")
                .append(diagnostic.message());

        final var sourceLine = span.start().column() == null
                ? null
                : sourceText.line(span.sourcePath(), span.start().line());
        if (sourceLine != null) appendExcerpt(output, span, sourceLine, colorize);

        for (final var label : diagnostic.labels()) {
            if (label.span().equals(span) && label.message().startsWith("at ")) continue;
            output.append(System.lineSeparator()).append("  = ")
                    .append(colorize ? AnsiColor.apply("label", "33") : "label")
                    .append(": ").append(label.message())
                    .append(" (").append(location(label.span().sourcePath(), label.span().start())).append(')');
        }
        for (final var note : diagnostic.notes()) {
            output.append(System.lineSeparator()).append("  = ")
                    .append(colorize ? AnsiColor.apply("note", "36") : "note")
                    .append(": ").append(note.message());
        }
        for (final var help : diagnostic.helps()) {
            output.append(System.lineSeparator()).append("  = ")
                    .append(colorize ? AnsiColor.apply("help", "32") : "help")
                    .append(": ").append(help.message());
            if (help.replacement() != null) {
                output.append(System.lineSeparator()).append("    replacement: ").append(help.replacement());
            }
        }
        return output.toString();
    }

    public static String format(final Diagnostic diagnostic) {
        return format(diagnostic, (path, line) -> null);
    }

    private static void appendExcerpt(final StringBuilder output,
                                      final SourceSpan span,
                                      final String sourceLine,
                                      final boolean colorize) {
        final var lineNumber = span.start().line();
        final var lineNumberWidth = Integer.toString(lineNumber).length();
        final var expandedLine = expandTabs(sourceLine);
        final var startColumn = displayColumn(sourceLine, span.start().column());
        final var endColumn = span.end().line() == lineNumber && span.end().column() != null
                ? displayColumn(sourceLine, span.end().column())
                : expandedLine.length();
        final var markerWidth = Math.max(1, endColumn - startColumn);
        output.append(System.lineSeparator()).append("  --> ")
                .append(location(span.sourcePath(), span.start()))
                .append(System.lineSeparator()).append(" ".repeat(lineNumberWidth + 3)).append('|')
                .append(System.lineSeparator()).append("  ")
                .append(String.format(java.util.Locale.ROOT, "%" + lineNumberWidth + "d", lineNumber))
                .append(" | ").append(colorize ? AnsiSyntaxHighlighter.highlight(expandedLine) : expandedLine)
                .append(System.lineSeparator()).append(" ".repeat(lineNumberWidth + 3)).append("| ")
                .append(" ".repeat(startColumn))
                .append(colorize ? AnsiColor.apply("^" + "~".repeat(markerWidth - 1), "31")
                        : "^" + "~".repeat(markerWidth - 1));
    }

    private static String location(final String sourcePath, final SourcePosition position) {
        final var path = sourcePath == null ? "<input>" : sourcePath;
        return path + ":" + position.line()
                + (position.column() == null ? "" : ":" + position.column());
    }

    private static int displayColumn(final String line, final int oneBasedColumn) {
        var column = 0;
        for (var index = 0; index < Math.min(line.length(), oneBasedColumn - 1); index++) {
            column += line.charAt(index) == '\t' ? 4 - column % 4 : 1;
        }
        return column;
    }

    private static String expandTabs(final String line) {
        final var expanded = new StringBuilder();
        for (var index = 0; index < line.length(); index++) {
            if (line.charAt(index) == '\t') {
                expanded.append(" ".repeat(4 - expanded.length() % 4));
            } else {
                expanded.append(line.charAt(index));
            }
        }
        return expanded.toString();
    }
}
