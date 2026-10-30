package com.maruseron.zeron.runtime.json;

public final class JsonParseResult {
    private final JsonValue value;
    private final JsonErrorInfo error;

    private JsonParseResult(final JsonValue value, final JsonErrorInfo error) {
        this.value = value;
        this.error = error;
    }

    static JsonParseResult success(final JsonValue value) {
        return new JsonParseResult(value, null);
    }

    static JsonParseResult failure(final JsonErrorInfo error) {
        return new JsonParseResult(null, error);
    }

    public boolean isSuccess() { return error == null; }
    public JsonValue value() { return value; }
    public JsonErrorInfo error() { return error; }
}
