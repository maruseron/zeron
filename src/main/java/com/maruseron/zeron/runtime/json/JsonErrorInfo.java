package com.maruseron.zeron.runtime.json;

public final class JsonErrorInfo {
    private final String message;
    private final int offset;
    private final int line;
    private final int column;

    JsonErrorInfo(final String message, final int offset, final int line, final int column) {
        this.message = message;
        this.offset = offset;
        this.line = line;
        this.column = column;
    }

    public String message() { return message; }
    public int offset() { return offset; }
    public int line() { return line; }
    public int column() { return column; }
}
