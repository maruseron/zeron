package com.maruseron.zeron.runtime.text;

public final class RegexCompileResult {
    private final RegexHandle handle;
    private final String message;
    private final int offset;

    private RegexCompileResult(final RegexHandle handle, final String message, final int offset) {
        this.handle = handle;
        this.message = message;
        this.offset = offset;
    }

    static RegexCompileResult success(final RegexHandle handle) {
        return new RegexCompileResult(handle, null, -1);
    }

    static RegexCompileResult failure(final String message, final int offset) {
        return new RegexCompileResult(null, message, offset);
    }

    public boolean isSuccess() { return handle != null; }
    public RegexHandle regex() { return handle; }
    public String message() { return message; }
    public int offset() { return offset; }
}
