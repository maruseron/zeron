package com.maruseron.zeron.runtime.parsers.toml;

public final class TomlParseResult {
    private final TomlNode root;
    private final String message;
    private final int line;
    private final int column;

    private TomlParseResult(final TomlNode root, final String message, final int line,
                            final int column) {
        this.root = root;
        this.message = message;
        this.line = line;
        this.column = column;
    }

    static TomlParseResult success(final TomlNode root) {
        return new TomlParseResult(root, null, 0, 0);
    }

    static TomlParseResult failure(final String message, final int line, final int column) {
        return new TomlParseResult(null, message, line, column);
    }

    public boolean isSuccess() { return root != null; }
    public TomlNode root() { return root; }
    public String message() { return message; }
    public int line() { return line; }
    public int column() { return column; }
}
