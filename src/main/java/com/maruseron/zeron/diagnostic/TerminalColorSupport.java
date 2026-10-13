package com.maruseron.zeron.diagnostic;

public final class TerminalColorSupport {
    private TerminalColorSupport() {}

    public static boolean isEnabled() {
        return isEnabled(System.console() != null,
                System.getenv("NO_COLOR"), System.getenv("TERM"));
    }

    static boolean isEnabled(final boolean consoleAvailable,
                             final String noColor,
                             final String terminalType) {
        return consoleAvailable
                && (noColor == null || noColor.isEmpty())
                && (terminalType == null || !terminalType.equalsIgnoreCase("dumb"));
    }
}
