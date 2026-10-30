package com.maruseron.zeron.runtime.json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class JsonObject {
    private final Map<String, JsonValue> fields = new LinkedHashMap<>();

    public boolean add(final String key, final JsonValue value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (fields.containsKey(key)) return false;
        fields.put(key, value);
        return true;
    }

    public int size() { return fields.size(); }
    public boolean containsKey(final String key) { return fields.containsKey(key); }
    public JsonValue get(final String key) { return fields.get(key); }

    public String keyAt(final int index) {
        if (index < 0 || index >= fields.size()) return null;
        return fields.keySet().stream().skip(index).findFirst().orElse(null);
    }

    public JsonValue valueAt(final int index) {
        final var key = keyAt(index);
        return key == null ? null : fields.get(key);
    }

    Map<String, JsonValue> fields() { return fields; }
}
