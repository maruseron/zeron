package com.maruseron.zeron.analize;

import java.io.IOException;

public final class CheckedExceptionHost {
    private static IOException lastException;

    public CheckedExceptionHost() {}

    public CheckedExceptionHost(final boolean fail) throws IOException {
        if (fail) throw lastException = new IOException("constructor");
    }

    public String checked() throws IOException {
        throw new IOException("checked");
    }

    public static IOException checkedStatic() throws IOException {
        throw lastException = new IOException("external function");
    }

    public static IOException lastException() {
        return lastException;
    }

    public static <E extends Exception> String genericThrows(final E exception) throws E {
        throw exception;
    }

    public String overlapping() throws Exception {
        throw new IOException("specific checked");
    }

    public String broad() throws Exception {
        throw new IllegalStateException("unchecked");
    }

    public String error() throws Throwable {
        throw new AssertionError("error");
    }
}
