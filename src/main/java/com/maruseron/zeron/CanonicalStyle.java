package com.maruseron.zeron;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

record CanonicalStyle(int indentWidth, int maximumLineLength, boolean insertFinalNewline) {
    private static final String RESOURCE = "/style.toml";
    private static final String VERSION = "1.0";

    static CanonicalStyle load() throws IOException {
        final String source;
        try (final var input = CanonicalStyle.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Bundled canonical style is missing: " + RESOURCE);
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        final TomlParseResult document = Toml.parse(source);
        if (document.hasErrors()) {
            throw new IOException("Invalid bundled canonical style:\n"
                    + String.join("\n", document.errors().stream().map(Object::toString).toList()));
        }
        final var meta = requiredTable(document, "meta");
        final var version = meta.getString("version");
        if (!VERSION.equals(version)) {
            throw new IOException("Unsupported canonical style version: " + version);
        }
        final var general = requiredTable(document, "general");
        final var indent = general.getLong("indent_width");
        final var maximum = general.getLong("max_line_length");
        final var lineEnding = requiredTable(document, "line_ending");
        if (!"preserve".equals(lineEnding.getString("end_of_line"))) {
            throw new IOException("Canonical style must preserve source line endings.");
        }
        final var insertFinalNewline = lineEnding.getBoolean("insert_final_newline");
        if (indent == null || indent < 1 || indent > 16) {
            throw new IOException("Canonical style indent_width must be between 1 and 16.");
        }
        if (maximum == null || maximum < 40 || maximum > 1000) {
            throw new IOException("Canonical style max_line_length must be between 40 and 1000.");
        }
        if (insertFinalNewline == null) {
            throw new IOException("Canonical style insert_final_newline must be a boolean.");
        }
        return new CanonicalStyle(indent.intValue(), maximum.intValue(), insertFinalNewline);
    }

    private static TomlTable requiredTable(final TomlTable document, final String key) throws IOException {
        final var value = document.get(key);
        if (!(value instanceof TomlTable table)) {
            throw new IOException("Canonical style must contain a [" + key + "] table.");
        }
        return table;
    }
}
