package com.maruseron.zeron.runtime.parsers.toml;

import org.tomlj.Toml;

public final class TomlSupport {
    private TomlSupport() {}

    public static TomlParseResult parse(final String text) {
        try {
            final var result = Toml.parse(text);
            if (result.hasErrors()) {
                final var error = result.errors().get(0);
                return TomlParseResult.failure(error.toString(), error.position().line(),
                        error.position().column());
            }
            return TomlParseResult.success(TomlNode.from(result.toMap()));
        } catch (final RuntimeException exception) {
            return TomlParseResult.failure(exception.getMessage(), 0, 0);
        }
    }
}
