package com.maruseron.zeron.runtime.json;

public final class JsonTextResult {
    private final String text;
    private final JsonErrorInfo error;

    private JsonTextResult(final String text, final JsonErrorInfo error) {
        this.text = text;
        this.error = error;
    }

    static JsonTextResult success(final String text) {
        return new JsonTextResult(text, null);
    }

    static JsonTextResult failure(final JsonErrorInfo error) {
        return new JsonTextResult(null, error);
    }

    public boolean isSuccess() { return error == null; }
    public String text() { return text; }
    public JsonErrorInfo error() { return error; }
}
