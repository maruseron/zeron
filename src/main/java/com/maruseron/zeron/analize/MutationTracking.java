package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Expr;

/// Records the local binding at the root of a mutation target so warning analysis can tell whether
/// a mutable projection was actually exercised.
final class MutationTracking {
    private MutationTracking() {}

    static void markRoot(final ResolutionContext context, final Expr expression) {
        var current = expression;
        while (true) {
            if (current instanceof Expr.Variable variable) {
                context.mutableUses.add(variable.name);
                return;
            }
            if (current instanceof Expr.Property property) {
                current = property.receiver;
                continue;
            }
            if (current instanceof Expr.Index index) {
                current = index.array;
                continue;
            }
            return;
        }
    }
}
