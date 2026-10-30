package com.maruseron.zeron.runtime.json;

import java.util.IdentityHashMap;
import java.util.Map;

public final class JsonSupport {
    static final int MAX_TEXT_LENGTH = 1_048_576;
    static final int MAX_NESTING_DEPTH = 256;

    private JsonSupport() {}

    public static JsonParseResult parse(final String text) {
        if (text == null) {
            return JsonParseResult.failure(new JsonErrorInfo("JSON text is null", 0, 1, 1));
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return JsonParseResult.failure(errorAt(text, MAX_TEXT_LENGTH,
                    "JSON text exceeds the maximum length of " + MAX_TEXT_LENGTH + " UTF-16 code units"));
        }
        final var parser = new Parser(text);
        try {
            return JsonParseResult.success(parser.parse());
        } catch (final ParseFailure failure) {
            return JsonParseResult.failure(errorAt(text, failure.offset, failure.getMessage()));
        }
    }

    public static JsonParseResult number(final String lexeme) {
        if (isNumberLexeme(lexeme)) {
            return JsonParseResult.success(JsonValue.numberValue(lexeme));
        }
        final var text = lexeme == null ? "" : lexeme;
        return JsonParseResult.failure(errorAt(text, 0, "Invalid JSON number"));
    }

    public static JsonTextResult stringify(final JsonValue value) {
        if (value == null) {
            return JsonTextResult.failure(new JsonErrorInfo("JSON value is null", 0, 1, 1));
        }
        final var writer = new Writer();
        try {
            writer.writeValue(value, 0);
            return JsonTextResult.success(writer.output.toString());
        } catch (final WriteFailure failure) {
            return JsonTextResult.failure(new JsonErrorInfo(failure.getMessage(),
                    writer.output.length(), 1, writer.output.length() + 1));
        }
    }

    static boolean isNumberLexeme(final String lexeme) {
        if (lexeme == null || lexeme.isEmpty() || lexeme.length() > MAX_TEXT_LENGTH) return false;
        var index = 0;
        if (lexeme.charAt(index) == '-') {
            index++;
            if (index == lexeme.length()) return false;
        }
        if (lexeme.charAt(index) == '0') {
            index++;
            if (index < lexeme.length() && isDigit(lexeme.charAt(index))) return false;
        } else {
            if (!isNonZeroDigit(lexeme.charAt(index))) return false;
            index++;
            while (index < lexeme.length() && isDigit(lexeme.charAt(index))) index++;
        }
        if (index < lexeme.length() && lexeme.charAt(index) == '.') {
            index++;
            final var fractionStart = index;
            while (index < lexeme.length() && isDigit(lexeme.charAt(index))) index++;
            if (index == fractionStart) return false;
        }
        if (index < lexeme.length() && (lexeme.charAt(index) == 'e' || lexeme.charAt(index) == 'E')) {
            index++;
            if (index < lexeme.length() && (lexeme.charAt(index) == '+' || lexeme.charAt(index) == '-')) {
                index++;
            }
            final var exponentStart = index;
            while (index < lexeme.length() && isDigit(lexeme.charAt(index))) index++;
            if (index == exponentStart) return false;
        }
        return index == lexeme.length();
    }

    private static boolean isDigit(final char value) {
        return value >= '0' && value <= '9';
    }

    private static boolean isNonZeroDigit(final char value) {
        return value >= '1' && value <= '9';
    }

    private static JsonErrorInfo errorAt(final String text, final int offset, final String message) {
        var line = 1;
        var column = 1;
        var index = 0;
        while (index < offset && index < text.length()) {
            final var value = text.charAt(index++);
            if (value == '\r') {
                if (index < offset && index < text.length() && text.charAt(index) == '\n') index++;
                line++;
                column = 1;
            } else if (value == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new JsonErrorInfo(message, offset, line, column);
    }

    private static final class Parser {
        private final String text;
        private int offset;

        private Parser(final String text) { this.text = text; }

        private JsonValue parse() {
            skipWhitespace();
            final var value = parseValue(0);
            skipWhitespace();
            if (offset != text.length()) fail("Unexpected trailing input");
            return value;
        }

        private JsonValue parseValue(final int depth) {
            if (offset >= text.length()) fail("Expected a JSON value");
            return switch (text.charAt(offset)) {
                case 'n' -> parseLiteral("null", JsonValue.nullValue());
                case 't' -> parseLiteral("true", JsonValue.booleanValue(true));
                case 'f' -> parseLiteral("false", JsonValue.booleanValue(false));
                case '"' -> JsonValue.stringValue(parseString());
                case '[' -> parseArray(depth);
                case '{' -> parseObject(depth);
                default -> parseNumber();
            };
        }

        private JsonValue parseLiteral(final String literal, final JsonValue value) {
            if (!text.startsWith(literal, offset)) fail("Invalid JSON literal");
            offset += literal.length();
            return value;
        }

        private JsonValue parseArray(final int depth) {
            checkDepth(depth);
            offset++;
            skipWhitespace();
            final var values = new JsonArray();
            if (consume(']')) return JsonValue.arrayValue(values);
            for (;;) {
                skipWhitespace();
                values.add(parseValue(depth + 1));
                skipWhitespace();
                if (consume(']')) return JsonValue.arrayValue(values);
                expect(',');
                skipWhitespace();
                if (peek(']')) fail("Expected a value after ','");
            }
        }

        private JsonValue parseObject(final int depth) {
            checkDepth(depth);
            offset++;
            skipWhitespace();
            final var fields = new JsonObject();
            if (consume('}')) return JsonValue.objectValue(fields);
            for (;;) {
                skipWhitespace();
                if (!peek('"')) fail("Expected a string object key");
                final var keyOffset = offset;
                final var key = parseString();
                if (fields.containsKey(key)) {
                    throw new ParseFailure(keyOffset, "Duplicate object key '" + key + "'");
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                final var value = parseValue(depth + 1);
                fields.add(key, value);
                skipWhitespace();
                if (consume('}')) return JsonValue.objectValue(fields);
                expect(',');
                skipWhitespace();
                if (peek('}')) fail("Expected an object key after ','");
            }
        }

        private JsonValue parseNumber() {
            final var start = offset;
            if (consume('-') && offset >= text.length()) fail("Incomplete JSON number");
            if (consume('0')) {
                if (offset < text.length() && isDigit(text.charAt(offset))) {
                    fail("Leading zero in JSON number");
                }
            } else {
                if (offset >= text.length() || !isNonZeroDigit(text.charAt(offset))) {
                    fail("Expected a JSON value");
                }
                while (offset < text.length() && isDigit(text.charAt(offset))) offset++;
            }
            if (consume('.')) {
                final var fractionStart = offset;
                while (offset < text.length() && isDigit(text.charAt(offset))) offset++;
                if (fractionStart == offset) fail("Expected a digit after decimal point");
            }
            if (consume('e') || consume('E')) {
                if (consume('+') || consume('-')) { }
                final var exponentStart = offset;
                while (offset < text.length() && isDigit(text.charAt(offset))) offset++;
                if (exponentStart == offset) fail("Expected a digit in exponent");
            }
            return JsonValue.numberValue(text.substring(start, offset));
        }

        private String parseString() {
            expect('"');
            final var result = new StringBuilder();
            var segmentStart = offset;
            while (offset < text.length()) {
                final var value = text.charAt(offset++);
                if (value == '"') {
                    result.append(text, segmentStart, offset - 1);
                    return result.toString();
                }
                if (value == '\\') {
                    result.append(text, segmentStart, offset - 1);
                    if (offset >= text.length()) fail("Incomplete escape sequence");
                    final var escape = text.charAt(offset++);
                    switch (escape) {
                        case '"', '\\', '/' -> result.append(escape);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> appendEscapedCodePoint(result);
                        default -> throw new ParseFailure(offset - 1, "Invalid escape sequence");
                    }
                    segmentStart = offset;
                } else if (value < 0x20) {
                    throw new ParseFailure(offset - 1, "Unescaped control character in string");
                } else if (Character.isHighSurrogate(value)) {
                    if (offset >= text.length() || !Character.isLowSurrogate(text.charAt(offset))) {
                        throw new ParseFailure(offset - 1, "Unpaired high surrogate in string");
                    }
                    offset++;
                } else if (Character.isLowSurrogate(value)) {
                    throw new ParseFailure(offset - 1, "Unpaired low surrogate in string");
                }
            }
            fail("Unterminated string");
            throw new AssertionError("Unreachable");
        }

        private void appendEscapedCodePoint(final StringBuilder result) {
            final var firstOffset = offset - 2;
            final var first = readHexCodeUnit();
            if (Character.isLowSurrogate((char) first)) {
                throw new ParseFailure(firstOffset, "Unpaired low surrogate escape");
            }
            if (!Character.isHighSurrogate((char) first)) {
                result.append((char) first);
                return;
            }
            if (offset + 2 > text.length() || text.charAt(offset) != '\\'
                    || text.charAt(offset + 1) != 'u') {
                throw new ParseFailure(firstOffset, "Unpaired high surrogate escape");
            }
            offset += 2;
            final var second = readHexCodeUnit();
            if (!Character.isLowSurrogate((char) second)) {
                throw new ParseFailure(offset - 4, "Invalid surrogate pair escape");
            }
            result.append((char) first).append((char) second);
        }

        private int readHexCodeUnit() {
            if (offset + 4 > text.length()) fail("Incomplete Unicode escape");
            var value = 0;
            for (int index = 0; index < 4; index++) {
                final var character = text.charAt(offset++);
                final var digit = character >= '0' && character <= '9' ? character - '0'
                        : character >= 'a' && character <= 'f' ? character - 'a' + 10
                        : character >= 'A' && character <= 'F' ? character - 'A' + 10 : -1;
                if (digit < 0) {
                    throw new ParseFailure(offset - 1, "Invalid hexadecimal digit in Unicode escape");
                }
                value = (value << 4) | digit;
            }
            return value;
        }

        private void checkDepth(final int depth) {
            if (depth >= MAX_NESTING_DEPTH) {
                fail("JSON nesting exceeds the maximum depth of " + MAX_NESTING_DEPTH);
            }
        }

        private void skipWhitespace() {
            while (offset < text.length()) {
                final var value = text.charAt(offset);
                if (value == ' ' || value == '\t' || value == '\r' || value == '\n') {
                    offset++;
                } else {
                    return;
                }
            }
        }

        private boolean consume(final char value) {
            if (!peek(value)) return false;
            offset++;
            return true;
        }

        private boolean peek(final char value) {
            return offset < text.length() && text.charAt(offset) == value;
        }

        private void expect(final char value) {
            if (!consume(value)) fail("Expected '" + value + "'");
        }

        private void fail(final String message) {
            throw new ParseFailure(offset, message);
        }
    }

    private static final class Writer {
        private final StringBuilder output = new StringBuilder();
        private final IdentityHashMap<Object, Boolean> activeContainers = new IdentityHashMap<>();

        private void writeValue(final JsonValue value, final int depth) {
            switch (value.kind()) {
                case "null" -> output.append("null");
                case "boolean" -> output.append(value.booleanValue() ? "true" : "false");
                case "number" -> output.append(value.numberLexeme());
                case "string" -> writeString(value.stringValue());
                case "array" -> {
                    checkDepth(depth);
                    writeArray(value.arrayValue(), depth);
                }
                case "object" -> {
                    checkDepth(depth);
                    writeObject(value.objectValue(), depth);
                }
                default -> throw new WriteFailure("Unknown JSON value kind");
            }
        }

        private void checkDepth(final int depth) {
            if (depth >= MAX_NESTING_DEPTH) {
                throw new WriteFailure("JSON nesting exceeds the maximum depth of " + MAX_NESTING_DEPTH);
            }
        }

        private void writeArray(final JsonArray array, final int depth) {
            enter(array);
            output.append('[');
            final var values = array.values();
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) output.append(',');
                writeValue(values.get(index), depth + 1);
            }
            output.append(']');
            activeContainers.remove(array);
        }

        private void writeObject(final JsonObject object, final int depth) {
            enter(object);
            output.append('{');
            var first = true;
            for (final Map.Entry<String, JsonValue> field : object.fields().entrySet()) {
                if (!first) output.append(',');
                first = false;
                writeString(field.getKey());
                output.append(':');
                writeValue(field.getValue(), depth + 1);
            }
            output.append('}');
            activeContainers.remove(object);
        }

        private void enter(final Object container) {
            if (activeContainers.put(container, Boolean.TRUE) != null) {
                throw new WriteFailure("Cyclic JSON value");
            }
        }

        private void writeString(final String value) {
            output.append('"');
            for (int index = 0; index < value.length(); index++) {
                final var character = value.charAt(index);
                switch (character) {
                    case '"' -> output.append("\\\"");
                    case '\\' -> output.append("\\\\");
                    case '\b' -> output.append("\\b");
                    case '\f' -> output.append("\\f");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            output.append("\\u00");
                            output.append(Character.forDigit((character >>> 4) & 0xf, 16));
                            output.append(Character.forDigit(character & 0xf, 16));
                        } else if (Character.isHighSurrogate(character)) {
                            if (index + 1 >= value.length()
                                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                                throw new WriteFailure("Unpaired high surrogate in string");
                            }
                            output.append(character).append(value.charAt(++index));
                        } else if (Character.isLowSurrogate(character)) {
                            throw new WriteFailure("Unpaired low surrogate in string");
                        } else {
                            output.append(character);
                        }
                    }
                }
            }
            output.append('"');
        }
    }

    private static final class ParseFailure extends RuntimeException {
        private final int offset;

        private ParseFailure(final int offset, final String message) {
            super(message);
            this.offset = offset;
        }
    }

    private static final class WriteFailure extends RuntimeException {
        private WriteFailure(final String message) { super(message); }
    }
}
