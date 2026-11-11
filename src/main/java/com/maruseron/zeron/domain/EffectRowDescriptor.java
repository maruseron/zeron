package com.maruseron.zeron.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A finite effect row: a set of fixed effect labels plus zero or more row variables.
 *
 * <p>Rows appear as explicit generic type arguments ({@code {}}, {@code {NotFound}}) and as
 * intermediate values during row inference. Function {@code raises} lists are normalized into
 * individual labels and row variables so that handler cases can remove fixed labels without
 * inspecting unresolved rows.</p>
 */
public final class EffectRowDescriptor implements TypeDescriptor {
    private final List<TypeDescriptor> effects;
    private final List<TypeParameterDescriptor> variables;

    public EffectRowDescriptor(final List<TypeDescriptor> effects,
                               final List<TypeParameterDescriptor> variables) {
        this.effects = List.copyOf(new LinkedHashSet<>(effects));
        this.variables = List.copyOf(new LinkedHashSet<>(variables));
    }

    public static EffectRowDescriptor empty() {
        return new EffectRowDescriptor(List.of(), List.of());
    }

    public static EffectRowDescriptor of(final TypeDescriptor effect) {
        return new EffectRowDescriptor(List.of(effect), List.of());
    }

    public static EffectRowDescriptor ofVariable(final TypeParameterDescriptor variable) {
        return new EffectRowDescriptor(List.of(), List.of(variable));
    }

    public List<TypeDescriptor> effects() {
        return effects;
    }

    public List<TypeParameterDescriptor> variables() {
        return variables;
    }

    public boolean isEmpty() {
        return effects.isEmpty() && variables.isEmpty();
    }

    public boolean isClosed() {
        return variables.isEmpty();
    }

    public boolean containsEffect(final TypeDescriptor effect) {
        return effects.contains(effect);
    }

    public boolean containsVariable(final TypeParameterDescriptor variable) {
        return variables.contains(variable);
    }

    public EffectRowDescriptor withoutEffects(final Set<TypeDescriptor> removed) {
        return new EffectRowDescriptor(effects.stream()
                .filter(effect -> !removed.contains(effect)).toList(), variables);
    }

    public EffectRowDescriptor withoutEffects(final Set<TypeDescriptor> removed,
                                             final EffectSubtyping subtyping) {
        return new EffectRowDescriptor(effects.stream()
                .filter(effect -> !EffectRows.coveredBy(effect, List.copyOf(removed), subtyping))
                .toList(), variables);
    }

    public EffectRowDescriptor union(final EffectRowDescriptor other) {
        final var unionEffects = new ArrayList<>(effects);
        unionEffects.addAll(other.effects);
        final var unionVariables = new ArrayList<>(variables);
        unionVariables.addAll(other.variables);
        return new EffectRowDescriptor(unionEffects, unionVariables);
    }

    /**
     * Returns this row with the given variables removed from its variable part. Fixed labels are
     * untouched.
     */
    public EffectRowDescriptor withoutVariables(final Set<TypeParameterDescriptor> removed) {
        return new EffectRowDescriptor(effects, variables.stream()
                .filter(variable -> !removed.contains(variable)).toList());
    }

    /**
     * Inclusion of effect sets: an open expected row may cover additional fixed labels, while row
     * variables must still be preserved by identity.
     */
    public boolean includedIn(final EffectRowDescriptor expected) {
        return includedIn(expected, EffectSubtyping.exact());
    }

    public boolean includedIn(final EffectRowDescriptor expected,
                              final EffectSubtyping subtyping) {
        if (expected.variables.isEmpty()) {
            for (final var effect : effects) {
                if (!EffectRows.coveredBy(effect, expected.effects, subtyping)) return false;
            }
        }
        return expected.variables.containsAll(variables);
    }

    @Override
    public String name() {
        if (isEmpty()) return "{}";
        final var members = new ArrayList<String>();
        effects.stream().map(TypeFormatter::format).forEach(members::add);
        variables.stream().map(TypeFormatter::format).forEach(members::add);
        return members.stream().collect(Collectors.joining(", ", "{", "}"));
    }

    @Override
    public String descriptor() {
        return "@row:" + name();
    }

    @Override
    public boolean equals(final Object other) {
        return this == other
                || other instanceof EffectRowDescriptor that
                && new LinkedHashSet<>(effects).equals(new LinkedHashSet<>(that.effects))
                && new LinkedHashSet<>(variables).equals(new LinkedHashSet<>(that.variables));
    }

    @Override
    public int hashCode() {
        return Objects.hash(Set.copyOf(effects), Set.copyOf(variables));
    }

    @Override
    public String toString() {
        return "EffectRow" + name();
    }
}
