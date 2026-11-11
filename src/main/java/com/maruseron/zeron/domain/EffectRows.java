package com.maruseron.zeron.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Operations on effect rows. Function {@code raises} clauses are kept as flat lists of fixed effect
 * labels and row variables; this class expands explicit {@link EffectRowDescriptor} values and
 * performs the set operations shared by resolution and inference.
 */
public final class EffectRows {
    private EffectRows() {}

    public static EffectRowDescriptor of(final List<TypeDescriptor> entries) {
        final var effects = new ArrayList<TypeDescriptor>();
        final var variables = new ArrayList<TypeParameterDescriptor>();
        for (final var entry : entries) {
            switch (entry) {
                case EffectRowDescriptor row -> {
                    effects.addAll(row.effects());
                    variables.addAll(row.variables());
                }
                case TypeParameterDescriptor parameter when parameter.isEffectRowKind() ->
                        variables.add(parameter);
                default -> effects.add(entry);
            }
        }
        return new EffectRowDescriptor(effects, variables);
    }

    public static EffectRowDescriptor of(final TypeDescriptor type) {
        return of(List.of(type));
    }

    /**
     * Flattens explicit rows into the individual labels and row variables expected by
     * {@link FunctionDescriptor} raised-effect lists, preserving order and removing duplicates.
     */
    public static List<TypeDescriptor> normalize(final List<TypeDescriptor> entries) {
        final var normalized = new LinkedHashSet<TypeDescriptor>();
        final var row = of(entries);
        row.effects().stream().filter(EffectRows::notNull).forEach(normalized::add);
        normalized.addAll(row.variables());
        return List.copyOf(normalized);
    }

    private static boolean notNull(final TypeDescriptor type) {
        return type != null;
    }

    public static boolean isRowVariable(final TypeDescriptor type) {
        return type instanceof TypeParameterDescriptor parameter && parameter.isEffectRowKind();
    }

    public static boolean containsRowVariable(final List<TypeDescriptor> effects) {
        return effects.stream().anyMatch(EffectRows::isRowVariable)
                || effects.stream().anyMatch(effect -> effect instanceof EffectRowDescriptor row
                        && !row.variables().isEmpty());
    }

    public static Set<TypeDescriptor> fixedLabels(final List<TypeDescriptor> effects) {
        return of(effects).effects().stream().collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public static boolean includedIn(final List<TypeDescriptor> actual,
                                     final List<TypeDescriptor> expected) {
        return includedIn(actual, expected, EffectSubtyping.exact());
    }

    public static boolean includedIn(final List<TypeDescriptor> actual,
                                     final List<TypeDescriptor> expected,
                                     final EffectSubtyping subtyping) {
        return of(actual).includedIn(of(expected), subtyping);
    }

    /** Whether {@code effect} is covered by one of the declared labels. */
    public static boolean coveredBy(final TypeDescriptor effect,
                                    final List<TypeDescriptor> declared,
                                    final EffectSubtyping subtyping) {
        for (final var candidate : declared) {
            if (subtyping.isSubtype(effect, candidate)) return true;
        }
        return false;
    }

    /**
     * Returns the first fixed effect (or row variable) that appears in {@code actual} but not in
     * {@code expected}, or {@code null} when the inclusion holds.
     */
    public static TypeDescriptor firstUnpermitted(final List<TypeDescriptor> actual,
                                                  final List<TypeDescriptor> expected) {
        return firstUnpermitted(actual, expected, EffectSubtyping.exact());
    }

    public static TypeDescriptor firstUnpermitted(final List<TypeDescriptor> actual,
                                                  final List<TypeDescriptor> expected,
                                                  final EffectSubtyping subtyping) {
        final var actualRow = of(actual);
        final var expectedRow = of(expected);
        if (expectedRow.variables().isEmpty()) {
            for (final var effect : actualRow.effects()) {
                if (!coveredBy(effect, expectedRow.effects(), subtyping)) return effect;
            }
        }
        for (final var variable : actualRow.variables()) {
            if (!expectedRow.variables().contains(variable)) return variable;
        }
        return null;
    }

    /**
     * Converts a value used where a row is expected into a row. A single effect becomes a
     * singleton row, and an effect-kinded parameter becomes a singleton row containing it.
     */
    public static EffectRowDescriptor asRow(final TypeDescriptor type) {
        return switch (type) {
            case EffectRowDescriptor row -> row;
            case TypeParameterDescriptor parameter when parameter.isEffectRowKind() ->
                    EffectRowDescriptor.ofVariable(parameter);
            default -> EffectRowDescriptor.of(type);
        };
    }

    public static boolean isEffectRowType(final TypeDescriptor type) {
        return switch (type) {
            case EffectRowDescriptor row -> row.effects().stream().allMatch(effect ->
                    !(effect instanceof TypeParameterDescriptor parameter) || parameter.isEffectKind())
                    ;
            case TypeParameterDescriptor parameter -> parameter.isEffectKind()
                    || parameter.isEffectRowKind();
            default -> false;
        };
    }
}
