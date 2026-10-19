package zeron.runtime;

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
