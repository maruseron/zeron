package com.maruseron.zeron.runtime.buffer;

public final class TextDecodeResult {
    private final byte[] bytes;
    private final int errorOffset;

    private TextDecodeResult(final byte[] bytes, final int errorOffset) {
        this.bytes = bytes;
        this.errorOffset = errorOffset;
    }

    static TextDecodeResult success(final byte[] bytes) {
        return new TextDecodeResult(bytes, -1);
    }

    static TextDecodeResult failure(final int offset) {
        return new TextDecodeResult(null, offset);
    }

    public boolean isSuccess() { return errorOffset < 0; }
    public byte[] bytes() { return bytes; }
    public int errorOffset() { return errorOffset; }
}
