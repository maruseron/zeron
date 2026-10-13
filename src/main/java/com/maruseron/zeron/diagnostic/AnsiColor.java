package com.maruseron.zeron.diagnostic;

final class AnsiColor {
    static final String RESET = "\u001b[0m";

    private AnsiColor() {}

    static String apply(final String text, final String code) {
        if (text.isEmpty()) return text;
        return "\u001b[" + code + "m" + text + RESET;
    }
}
