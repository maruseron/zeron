package com.maruseron.zeron.runtime.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JsonArray {
    private final List<JsonValue> values = new ArrayList<>();

    public void add(final JsonValue value) {
        values.add(Objects.requireNonNull(value));
    }

    public int size() { return values.size(); }

    public JsonValue at(final int index) {
        return index < 0 || index >= values.size() ? null : values.get(index);
    }

    List<JsonValue> values() { return List.copyOf(values); }
}
