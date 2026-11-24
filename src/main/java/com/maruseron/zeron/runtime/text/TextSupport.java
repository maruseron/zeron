package com.maruseron.zeron.runtime.text;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// Host support for regex compilation and locale-neutral numeric formatting.
public final class TextSupport {
    private TextSupport() {}

    public static RegexCompileResult compileRegex(final String pattern) {
        try {
            return RegexCompileResult.success(new RegexHandle(Pattern.compile(pattern)));
        } catch (final PatternSyntaxException exception) {
            return RegexCompileResult.failure(exception.getDescription(),
                    Math.max(exception.getIndex(), 0));
        }
    }

    public static String formatFixed(final double value, final int places) {
        if (places < 0) return Double.toString(value);
        return String.format(Locale.ROOT, "%." + places + "f", value);
    }

    public static String zeroPadded(final long value, final int width) {
        if (width <= 0) return Long.toString(value);
        return String.format(Locale.ROOT, "%0" + width + "d", value);
    }
}
