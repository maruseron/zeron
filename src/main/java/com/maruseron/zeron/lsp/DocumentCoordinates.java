package com.maruseron.zeron.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.Objects;

final class DocumentCoordinates {
    private DocumentCoordinates() {}

    static int offsetAt(final String text, final Position position) {
        Objects.requireNonNull(text);
        Objects.requireNonNull(position);
        var offset = 0;
        for (var line = 0; line < position.getLine(); line++) {
            while (offset < text.length() && text.charAt(offset) != '\n') offset++;
            if (offset < text.length()) offset++;
        }
        var lineEnd = text.indexOf('\n', offset);
        if (lineEnd < 0) lineEnd = text.length();
        final var end = lineEnd > offset && text.charAt(lineEnd - 1) == '\r'
                ? lineEnd - 1 : lineEnd;
        return Math.min(end, offset + Math.max(0, position.getCharacter()));
    }

    static Position positionAt(final String text, final int requestedOffset) {
        final var offset = Math.max(0, Math.min(text.length(), requestedOffset));
        var line = 0;
        var lineStart = 0;
        for (var index = 0; index < offset; index++) {
            if (text.charAt(index) == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        final var character = offset > lineStart && text.charAt(offset - 1) == '\r'
                ? offset - lineStart - 1 : offset - lineStart;
        return new Position(line, character);
    }

    static Range rangeAt(final String text, final int startOffset, final int endOffset) {
        return new Range(positionAt(text, startOffset), positionAt(text, endOffset));
    }

    static Range rangeOf(final String text, final com.maruseron.zeron.scan.Token token) {
        final var span = token.span();
        final var start = span.start().offset();
        final var end = span.end().offset();
        if (start != null && end != null) return rangeAt(text, start, end);
        return new Range(new Position(span.start().line() - 1,
                Math.max(0, Objects.requireNonNullElse(span.start().column(), 1) - 1)),
                new Position(span.end().line() - 1,
                        Math.max(0, Objects.requireNonNullElse(span.end().column(), 1) - 1)));
    }
}
