package com.maruseron.zeron.runtime.text;

import java.util.regex.Pattern;

public final class RegexHandle {
    private final Pattern pattern;

    RegexHandle(final Pattern pattern) {
        this.pattern = pattern;
    }

    public boolean matches(final String text) {
        return pattern.matcher(text).matches();
    }

    public RegexMatchResult find(final String text, final int fromIndex) {
        final var matcher = pattern.matcher(text);
        if (!matcher.find(fromIndex)) return RegexMatchResult.none();
        return RegexMatchResult.found(matcher);
    }

    public String replaceFirst(final String text, final String replacement) {
        return pattern.matcher(text).replaceFirst(replacement);
    }

    public String replaceAll(final String text, final String replacement) {
        return pattern.matcher(text).replaceAll(replacement);
    }
}
