package com.maruseron.zeron.runtime.parsers.xml;

public final class XmlParseResult {
    private final XmlNode root;
    private final String message;
    private final int line;
    private final int column;

    private XmlParseResult(final XmlNode root, final String message, final int line,
                           final int column) {
        this.root = root;
        this.message = message;
        this.line = line;
        this.column = column;
    }

    static XmlParseResult success(final XmlNode root) {
        return new XmlParseResult(root, null, 0, 0);
    }

    static XmlParseResult failure(final String message, final int line, final int column) {
        return new XmlParseResult(null, message, line, column);
    }

    public boolean isSuccess() { return root != null; }
    public XmlNode root() { return root; }
    public String message() { return message; }
    public int line() { return line; }
    public int column() { return column; }
}
