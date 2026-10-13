package com.maruseron.zeron.analize;

import java.io.IOException;

public final class CheckedExceptionHost {
    public CheckedExceptionHost() {}

    public String checked() throws IOException {
        throw new IOException("checked");
    }

    public String overlapping() throws Exception {
        throw new IOException("specific checked");
    }

    public String broad() throws Exception {
        throw new IllegalStateException("unchecked");
    }
}
