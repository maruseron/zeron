package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RaisedEffectFlow {
    private RaisedEffectFlow() {}

    static Set<TypeDescriptor> beginCallable(final ResolutionContext context) {
        final var enclosing = context.frame.raisedEffects;
        context.frame.raisedEffects = new LinkedHashSet<>();
        return enclosing;
    }

    static void verifyCallable(final ResolutionContext context, final List<TypeDescriptor> declared,
                               final Token where) {
        final var missing = new LinkedHashSet<>(context.frame.raisedEffects);
        missing.removeAll(declared);
        if (!missing.isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    where, "Undeclared raised effects: " + missing + ". Add them to the raises clause or handle them."));
        }
    }

    static void endCallable(final ResolutionContext context, final Set<TypeDescriptor> enclosing) {
        context.frame.raisedEffects = enclosing;
    }

    static Set<TypeDescriptor> beginHandledExpression(final ResolutionContext context) {
        final var enclosing = context.frame.raisedEffects;
        context.frame.raisedEffects = new LinkedHashSet<>();
        return enclosing;
    }

    static void endHandledExpression(final ResolutionContext context,
                                     final Set<TypeDescriptor> enclosing,
                                     final Set<TypeDescriptor> handled,
                                     final Token where) {
        final var remaining = new LinkedHashSet<>(context.frame.raisedEffects);
        remaining.removeAll(handled);
        context.frame.raisedEffects = enclosing;
        add(context, remaining, where);
    }

    static void add(final ResolutionContext context, final TypeDescriptor effect, final Token where) {
        add(context, Set.of(effect), where);
    }

    static void add(final ResolutionContext context, final Set<TypeDescriptor> effects, final Token where) {
        if (effects.isEmpty()) return;
        if (context.frame.raisedEffects == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    where, "Raised effects are not permitted in top-level initializers."));
            return;
        }
        context.frame.raisedEffects.addAll(effects);
    }
}
