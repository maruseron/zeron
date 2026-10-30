package com.maruseron.zeron.runtime.json;

import java.math.BigDecimal;
import java.util.Objects;

public final class JsonValue {
    private final String kind;
    private final Object value;

    private JsonValue(final String kind, final Object value) {
        this.kind = kind;
        this.value = value;
    }

    public static JsonValue nullValue() { return new JsonValue("null", null); }
    public static JsonValue booleanValue(final boolean value) {
        return new JsonValue("boolean", value);
    }
    public static JsonValue numberValue(final String lexeme) {
        if (!JsonSupport.isNumberLexeme(lexeme)) return null;
        return new JsonValue("number", lexeme);
    }
    public static JsonValue stringValue(final String value) {
        return new JsonValue("string", Objects.requireNonNull(value));
    }
    public static JsonValue arrayValue(final JsonArray value) {
        return new JsonValue("array", Objects.requireNonNull(value));
    }
    public static JsonValue objectValue(final JsonObject value) {
        return new JsonValue("object", Objects.requireNonNull(value));
    }

    public String kind() { return kind; }

    public Boolean booleanValue() {
        return "boolean".equals(kind) ? (Boolean) value : null;
    }

    public String stringValue() {
        return "string".equals(kind) ? (String) value : null;
    }

    public String numberLexeme() {
        return "number".equals(kind) ? (String) value : null;
    }

    public JsonArray arrayValue() {
        return "array".equals(kind) ? (JsonArray) value : null;
    }

    public JsonObject objectValue() {
        return "object".equals(kind) ? (JsonObject) value : null;
    }

    public Integer intValue() {
        if (!"number".equals(kind)) return null;
        try {
            return new BigDecimal((String) value).intValueExact();
        } catch (final NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    public Long longValue() {
        if (!"number".equals(kind)) return null;
        try {
            return new BigDecimal((String) value).longValueExact();
        } catch (final NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }
}
