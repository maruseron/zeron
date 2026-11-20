package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.DiagnosticHelp;
import com.maruseron.zeron.domain.IntrinsicFunctions;

/// The standard library declares a few functions whose bodies the compiler replaces with an
/// intrinsic operation. Those declarations are recognized by qualified name, so the same shape
/// outside the standard library becomes ordinary recursion. Report that shape instead of emitting
/// a self-call that inevitably overflows the stack.
final class IntrinsicDeclarationGuard {
    private IntrinsicDeclarationGuard() {}

    static void check(final ResolutionContext context, final Stmt.Function function) {
        final var simpleName = function.name().lexeme();
        if (!IntrinsicFunctions.isIntrinsicDeclarationName(simpleName)) return;
        final var qualifiedName = context.functionNamesByDeclaration.get(function.name());
        if (qualifiedName == null || IntrinsicFunctions.isIntrinsicDeclaration(qualifiedName)) return;
        final var selfCall = bareSelfCall(function);
        if (selfCall == null) return;
        Zeron.resolutionError(ResolutionError.withHelp(
                DiagnosticCatalog.INTRINSIC_HELPER_OUTSIDE_STDLIB,
                selfCall.callee,
                "Function '" + simpleName + "' only calls itself and is not the standard-library "
                        + "intrinsic declaration; it compiles as unbounded recursion.",
                new DiagnosticHelp(
                        "Allocate arrays with 'zeron.internal.generateArray(size, initializer)' "
                                + "or use a collection type such as 'ArrayList<T>'.")));
    }

    /// A body consisting of one call to the enclosing function that forwards exactly its own
    /// parameters in order. This is the standard-library intrinsic convention, so no other body is
    /// specific enough to diagnose.
    private static Expr.Call bareSelfCall(final Stmt.Function function) {
        if (function.body().size() != 1) return null;
        final var statement = function.body().getFirst();
        final Expr value;
        if (statement instanceof Stmt.Return returnStatement) {
            value = returnStatement.value();
        } else if (statement instanceof Stmt.Expression expression) {
            value = expression.expression();
        } else {
            return null;
        }
        if (!(value instanceof Expr.Call call)) return null;
        if (call.resolvedFunctionDeclaration() != function) return null;
        if (call.arguments.size() != function.parameters().size()) return null;
        for (var index = 0; index < call.arguments.size(); index++) {
            if (!(call.arguments.get(index) instanceof Expr.Variable variable)
                    || !variable.name.lexeme().equals(function.parameters().get(index).lexeme())) {
                return null;
            }
        }
        return call;
    }
}
