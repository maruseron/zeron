package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.EffectSubtyping;
import com.maruseron.zeron.domain.NominalDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The declared effect hierarchy. Effects have explicit, effect-only supertypes named by `under`;
 * every declared effect is implicitly a subtype of the built-in top `Effect`. The hierarchy reads
 * the live class registry, so declarations registered during resolution are visible immediately.
 */
final class EffectHierarchy implements EffectSubtyping {
    static final String TOP_EFFECT = EffectSubtyping.TOP_EFFECT;

    private final Map<String, Stmt.ClassDecl> classes;

    EffectHierarchy(final Map<String, Stmt.ClassDecl> classes) {
        this.classes = classes;
    }

    Stmt.ClassDecl declaration(final String name) {
        return classes.get(name);
    }

    boolean isEffect(final String name) {
        final var declaration = classes.get(name);
        return declaration != null && declaration.isEffect();
    }

    List<String> directParents(final String name) {
        final var declaration = classes.get(name);
        if (declaration == null) return List.of();
        return declaration.underEffects().stream().map(use -> use.name().lexeme()).toList();
    }

    @Override
    public boolean isSubtype(final TypeDescriptor effect, final TypeDescriptor superEffect) {
        if (effect.equals(superEffect)) return true;
        if (!(effect instanceof NominalDescriptor subtype)
                || !(superEffect instanceof NominalDescriptor supertype)) return false;
        if (!isEffect(subtype.name()) || !isEffect(supertype.name())) return false;
        if (supertype.name().equals(TOP_EFFECT)) return true;
        return !subtype.name().equals(TOP_EFFECT) && ancestors(subtype.name()).contains(supertype.name());
    }

    /** Transitive {@code under} ancestors, excluding the effect itself. */
    Set<String> ancestors(final String name) {
        final var result = new LinkedHashSet<String>();
        collectAncestors(name, result, new LinkedHashSet<>());
        result.remove(name);
        return result;
    }

    private void collectAncestors(final String name,
                                  final Set<String> result,
                                  final Set<String> visiting) {
        for (final var parent : directParents(name)) {
            if (!result.add(parent)) continue;
            if (!visiting.add(parent)) continue;
            collectAncestors(parent, result, visiting);
        }
    }

    /** Whether the effect contributes payload state of its own or through a payload-bearing parent. */
    boolean hasState(final String name) {
        return hasState(name, new LinkedHashSet<>());
    }

    private boolean hasState(final String name, final Set<String> visiting) {
        final var declaration = classes.get(name);
        if (declaration == null) return false;
        if (!declaration.fields().isEmpty()
                || declaration.properties().stream().anyMatch(property -> !property.isCustom())) {
            return true;
        }
        if (!visiting.add(name)) return false;
        for (final var parent : directParents(name)) {
            if (hasState(parent, visiting)) return true;
        }
        return false;
    }

    /** The single direct parent whose chain carries payload state, or {@code null}. */
    String payloadParent(final String name) {
        String selected = null;
        for (final var parent : directParents(name)) {
            if (!hasState(parent)) continue;
            if (selected != null) return null;
            selected = parent;
        }
        return selected;
    }

    /** A stateless effect is emitted as an interface; anything carrying payload state is a class. */
    boolean isStateless(final String name) {
        return isEffect(name) && !hasState(name);
    }

    /** Every declared effect in the visible hierarchy. */
    List<String> allEffects() {
        final var result = new ArrayList<String>();
        for (final var candidate : classes.values()) {
            if (candidate.isEffect()) result.add(candidate.name().lexeme());
        }
        return result;
    }

    /** All declared effects whose type is {@code name} or a subtype of it. */
    List<String> visibleSubtypes(final String name) {
        final var result = new ArrayList<String>();
        for (final var candidate : classes.values()) {
            if (!candidate.isEffect()) continue;
            final var qualifiedName = candidate.name().lexeme();
            if (qualifiedName.equals(name) || ancestors(qualifiedName).contains(name)) {
                result.add(qualifiedName);
            }
        }
        return result;
    }
}
