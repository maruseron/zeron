package com.maruseron.zeron.domain;

import java.util.List;
import java.util.Objects;

public record QualifiedName(List<String> packageParts, String simpleName) {
    public QualifiedName {
        packageParts = List.copyOf(packageParts);
        Objects.requireNonNull(simpleName);
        if (simpleName.isBlank()) throw new IllegalArgumentException("Qualified name needs a name.");
    }

    public static QualifiedName parse(final String name) {
        final var parts = List.of(name.split("\\."));
        return new QualifiedName(parts.subList(0, parts.size() - 1), parts.getLast());
    }

    public String binaryName() {
        return String.join(".", packageParts.isEmpty()
                ? List.of(simpleName)
                : java.util.stream.Stream.concat(packageParts.stream(), java.util.stream.Stream.of(simpleName)).toList());
    }

    @Override
    public String toString() {
        return binaryName();
    }
}