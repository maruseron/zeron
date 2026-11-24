package com.maruseron.zeron.runtime.parsers.toml;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/// A host-side TOML value. The public Zeron model converts this tree into `TomlValue`.
public final class TomlNode {
    private final String kind;
    private final String text;
    private final long integer;
    private final double decimal;
    private final boolean flag;
    private final List<TomlNode> items;
    private final List<String> keys;
    private final Map<String, TomlNode> children;

    private TomlNode(final String kind, final String text, final long integer,
                     final double decimal, final boolean flag, final List<TomlNode> items,
                     final List<String> keys, final Map<String, TomlNode> children) {
        this.kind = kind;
        this.text = text;
        this.integer = integer;
        this.decimal = decimal;
        this.flag = flag;
        this.items = items;
        this.keys = keys;
        this.children = children;
    }

    public static TomlNode from(final Object value) {
        if (value instanceof String string) return scalar("string", string, 0, 0, false);
        if (value instanceof Long number) return scalar("integer", null, number, 0, false);
        if (value instanceof Integer number) return scalar("integer", null, number.longValue(), 0, false);
        if (value instanceof Double number) return scalar("float", null, 0, number, false);
        if (value instanceof Float number) return scalar("float", null, 0, number.doubleValue(), false);
        if (value instanceof Boolean bool) return scalar("boolean", null, 0, 0, bool);
        if (value instanceof LocalDate date) return scalar("date", date.toString(), 0, 0, false);
        if (value instanceof LocalTime time) return scalar("time", time.toString(), 0, 0, false);
        if (value instanceof LocalDateTime dateTime)
            return scalar("date-time", dateTime.toString(), 0, 0, false);
        if (value instanceof OffsetDateTime dateTime)
            return scalar("offset-date-time", dateTime.toString(), 0, 0, false);
        if (value instanceof TomlArray array) {
            final var items = new ArrayList<TomlNode>(array.size());
            for (var index = 0; index < array.size(); index++) {
                items.add(from(array.get(index)));
            }
            return new TomlNode("array", null, 0, 0, false, List.copyOf(items),
                    List.of(), Map.of());
        }
        if (value instanceof TomlTable table) return fromTable(table.toMap());
        if (value instanceof Map<?, ?> map) {
            final var converted = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> converted.put(String.valueOf(key), item));
            return fromTable(converted);
        }
        return scalar("string", String.valueOf(value), 0, 0, false);
    }

    private static TomlNode fromTable(final Map<String, Object> table) {
        final var children = new LinkedHashMap<String, TomlNode>();
        table.forEach((key, value) -> children.put(key, from(value)));
        return new TomlNode("table", null, 0, 0, false, List.of(),
                List.copyOf(children.keySet()), Map.copyOf(children));
    }

    private static TomlNode scalar(final String kind, final String text, final long integer,
                                   final double decimal, final boolean flag) {
        return new TomlNode(kind, text, integer, decimal, flag, List.of(), List.of(), Map.of());
    }

    public String kind() { return kind; }
    public String text() { return text; }
    public long integer() { return integer; }
    public double decimal() { return decimal; }
    public boolean flag() { return flag; }
    public int arraySize() { return items.size(); }
    public TomlNode arrayAt(final int index) {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }
    public Object[] tableKeys() { return keys.toArray(); }
    public TomlNode child(final String key) { return children.get(key); }
}
