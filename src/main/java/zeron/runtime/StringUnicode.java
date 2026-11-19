package zeron.runtime;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class StringUnicode {
    private static final int REPLACEMENT_CHARACTER = 0xFFFD;
    private static final int MAX_CODE_POINT = 0x10FFFF;

    private StringUnicode() {}

    public static int codePointLength(final String value) {
        var count = 0;
        for (var offset = 0; offset < value.length(); count++) {
            offset += codePointWidthAt(value, offset);
        }
        return count;
    }

    public static int codePointAt(final String value, final int index) {
        if (index < 0) return -1;
        var offset = 0;
        var scalarIndex = 0;
        while (offset < value.length()) {
            if (scalarIndex == index) return scalarAt(value, offset);
            offset += codePointWidthAt(value, offset);
            scalarIndex++;
        }
        return -1;
    }

    public static String substringByCodePoints(final String value, final int start, final int end) {
        if (start < 0 || end < start) return null;
        final var result = new StringBuilder();
        var offset = 0;
        var scalarIndex = 0;
        while (offset < value.length() && scalarIndex < end) {
            final var scalar = scalarAt(value, offset);
            if (scalarIndex >= start) result.appendCodePoint(scalar);
            offset += codePointWidthAt(value, offset);
            scalarIndex++;
        }
        if (scalarIndex < end) return null;
        return result.toString();
    }

    public static int indexOfCodePoint(final String value, final int codePoint) {
        var offset = 0;
        var scalarIndex = 0;
        while (offset < value.length()) {
            if (scalarAt(value, offset) == codePoint) return scalarIndex;
            offset += codePointWidthAt(value, offset);
            scalarIndex++;
        }
        return -1;
    }

    public static int firstMalformedOffset(final String value) {
        for (var offset = 0; offset < value.length(); offset++) {
            final var current = value.charAt(offset);
            if (Character.isHighSurrogate(current)) {
                if (offset + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(offset + 1))) {
                    return offset;
                }
                offset++;
            } else if (Character.isLowSurrogate(current)) {
                return offset;
            }
        }
        return -1;
    }

    public static int scalarAtOffset(final String value, final int offset) {
        return scalarAt(value, offset);
    }

    public static int scalarWidthAtOffset(final String value, final int offset) {
        return offset >= 0 && offset < value.length() ? codePointWidthAt(value, offset) : 0;
    }

    public static int charToInt(final int value) {
        return value;
    }

    public static int intToChar(final int value) {
        if (!isScalar(value)) throw new IllegalArgumentException("Not a Unicode scalar value: " + value);
        return value;
    }

    public static String charToString(final int value) {
        if (!isScalar(value)) throw new IllegalArgumentException("Not a Unicode scalar value: " + value);
        return new String(Character.toChars(value));
    }

    public static String toLowerCase(final String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public static String toUpperCase(final String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    public static String trim(final String value) {
        return value.substring(leadingWhitespaceEnd(value), trailingWhitespaceStart(value));
    }

    public static String trimStart(final String value) {
        return value.substring(leadingWhitespaceEnd(value));
    }

    public static String trimEnd(final String value) {
        return value.substring(0, trailingWhitespaceStart(value));
    }

    public static boolean isBlank(final String value) {
        return leadingWhitespaceEnd(value) == value.length();
    }

    public static String replace(final String value, final String target, final String replacement) {
        return value.replace(target, replacement);
    }

    public static Object[] split(final String value, final String separator) {
        if (separator.isEmpty()) {
            final var scalars = new ArrayList<Object>();
            for (var offset = 0; offset < value.length(); ) {
                scalars.add(new String(Character.toChars(scalarAtOffset(value, offset))));
                offset += scalarWidthAtOffset(value, offset);
            }
            return scalars.toArray();
        }
        final var parts = new ArrayList<Object>();
        var start = 0;
        while (true) {
            final var index = value.indexOf(separator, start);
            if (index < 0) {
                parts.add(value.substring(start));
                return parts.toArray();
            }
            parts.add(value.substring(start, index));
            start = index + separator.length();
        }
    }

    public static Object[] splitWhitespace(final String value) {
        final var parts = new ArrayList<Object>();
        var offset = 0;
        while (offset < value.length()) {
            while (offset < value.length()) {
                final var scalar = value.codePointAt(offset);
                if (!Character.isWhitespace(scalar)) break;
                offset += Character.charCount(scalar);
            }
            if (offset >= value.length()) break;
            final var start = offset;
            while (offset < value.length()) {
                final var scalar = value.codePointAt(offset);
                if (Character.isWhitespace(scalar)) break;
                offset += Character.charCount(scalar);
            }
            parts.add(value.substring(start, offset));
        }
        return parts.toArray();
    }

    public static String regexErrorMessage(final String pattern) {
        try {
            Pattern.compile(pattern);
            return null;
        } catch (final PatternSyntaxException exception) {
            return exception.getMessage();
        }
    }

    public static Object[] splitRegex(final String value, final String pattern) {
        return Pattern.compile(pattern).split(value, -1);
    }

    public static Object[] lines(final String value) {
        final var parts = new ArrayList<Object>();
        var start = 0;
        var offset = 0;
        while (offset < value.length()) {
            final var current = value.charAt(offset);
            if (current != '\n' && current != '\r') {
                offset++;
                continue;
            }
            parts.add(value.substring(start, offset));
            if (current == '\r' && offset + 1 < value.length() && value.charAt(offset + 1) == '\n') {
                offset++;
            }
            offset++;
            start = offset;
        }
        if (start < value.length()) parts.add(value.substring(start));
        return parts.toArray();
    }

    public static boolean isDecimalInt(final String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (final NumberFormatException exception) {
            return false;
        }
    }

    public static int parseInt(final String value) {
        return Integer.parseInt(value);
    }

    public static boolean isDecimalFloat(final String value) {
        if (value.isEmpty()) return false;
        var offset = 0;
        if (value.charAt(offset) == '+' || value.charAt(offset) == '-') offset++;
        var digits = 0;
        while (offset < value.length() && isAsciiDigit(value.charAt(offset))) {
            offset++;
            digits++;
        }
        if (offset < value.length() && value.charAt(offset) == '.') {
            offset++;
            while (offset < value.length() && isAsciiDigit(value.charAt(offset))) {
                offset++;
                digits++;
            }
        }
        if (digits == 0) return false;
        if (offset < value.length() && (value.charAt(offset) == 'e' || value.charAt(offset) == 'E')) {
            offset++;
            if (offset < value.length() && (value.charAt(offset) == '+' || value.charAt(offset) == '-')) {
                offset++;
            }
            var exponentDigits = 0;
            while (offset < value.length() && isAsciiDigit(value.charAt(offset))) {
                offset++;
                exponentDigits++;
            }
            if (exponentDigits == 0) return false;
        }
        if (offset != value.length()) return false;
        return Double.isFinite(Double.parseDouble(value));
    }

    public static double parseFloat(final String value) {
        return Double.parseDouble(value);
    }

    public static int charCodePointAt(final String value, final int index) {
        if (index < 0 || index >= value.length()) return -1;
        final var current = value.charAt(index);
        if (Character.isLowSurrogate(current)
                && index > 0 && Character.isHighSurrogate(value.charAt(index - 1))) {
            return -1;
        }
        return scalarAt(value, index);
    }

    private static int leadingWhitespaceEnd(final String value) {
        var offset = 0;
        while (offset < value.length()) {
            final var scalar = value.codePointAt(offset);
            if (!Character.isWhitespace(scalar)) return offset;
            offset += Character.charCount(scalar);
        }
        return offset;
    }

    private static int trailingWhitespaceStart(final String value) {
        var end = value.length();
        while (end > 0) {
            final var scalar = value.codePointBefore(end);
            if (!Character.isWhitespace(scalar)) return end;
            end -= Character.charCount(scalar);
        }
        return end;
    }

    private static boolean isAsciiDigit(final char value) {
        return value >= '0' && value <= '9';
    }

    public static boolean isCharValue(final Object value) {
        return value instanceof Integer integer && isScalar(integer);
    }

    public static int unboxCharValue(final Object value) {
        if (!isCharValue(value)) throw new ClassCastException("Value is not a Unicode scalar Char.");
        return (Integer) value;
    }

    private static int scalarAt(final String value, final int offset) {
        final var current = value.charAt(offset);
        if (Character.isHighSurrogate(current)) {
            if (offset + 1 < value.length() && Character.isLowSurrogate(value.charAt(offset + 1))) {
                return Character.toCodePoint(current, value.charAt(offset + 1));
            }
            return REPLACEMENT_CHARACTER;
        }
        return Character.isLowSurrogate(current) ? REPLACEMENT_CHARACTER : current;
    }

    private static int codePointWidthAt(final String value, final int offset) {
        return Character.isHighSurrogate(value.charAt(offset))
                && offset + 1 < value.length()
                && Character.isLowSurrogate(value.charAt(offset + 1)) ? 2 : 1;
    }

    private static boolean isScalar(final int value) {
        return value >= 0 && value <= MAX_CODE_POINT
                && (value < Character.MIN_SURROGATE || value > Character.MAX_SURROGATE);
    }
}
