package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Shared checking and normalization for `...value` variadic arguments. */
final class SpreadSupport {
    record Spread(int index, Token token) {
        boolean present() { return index >= 0; }
    }

    private SpreadSupport() {}

    static Spread unwrap(final List<Expr> arguments, final Token where) {
        Expr.Spread selected = null;
        var selectedIndex = -1;
        for (int index = 0; index < arguments.size(); index++) {
            if (!(arguments.get(index) instanceof Expr.Spread spread)) continue;
            if (selected != null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        spread.ellipsis, "A call may contain at most one spread argument."));
            }
            if (index != arguments.size() - 1) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        spread.ellipsis, "A spread argument must be the final argument."));
            }
            selected = spread;
            selectedIndex = index;
        }
        if (selected == null) return new Spread(-1, null);
        arguments.set(selectedIndex, selected.expression);
        return new Spread(selectedIndex, selected.ellipsis);
    }

    static void requireVariadic(final Spread spread, final boolean variadic,
                                final int fixedArity, final Token where) {
        if (!spread.present()) return;
        if (!variadic) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                    spread.token(), "Only a variadic parameter can receive a spread argument."));
        }
    }

    static TypeDescriptor elementType(final ResolutionContext context,
                                      final TypeDescriptor actualType,
                                      final Token where) {
        final var actual = strip(actualType);
        if (actual.isNullable() || actual instanceof NullDescriptor) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NULLABLE_VALUE_REQUIRES_HANDLING,
                    where, "A nullable value cannot be spread; check it for null first."));
            return null;
        }
        if (actual instanceof ArrayDescriptor array) return array.elementType();
        if (actual instanceof TypeParameterDescriptor parameter) {
            for (final var bound : parameter.bounds()) {
                final var element = iterableElement(context, bound);
                if (element != null) return element;
            }
            return null;
        }
        return iterableElement(context, actual);
    }

    static boolean accepts(final ResolutionContext context, final TypeDescriptor elementType,
                           final TypeDescriptor actualType, final Token where) {
        final var actual = strip(actualType);
        if (actual instanceof ArrayDescriptor array) {
            return context.typeCompatibility.canAssign(elementType, array.elementType())
                    && context.typeCompatibility.canAssign(array.elementType(), elementType);
        }
        final var element = elementType(context, actualType, where);
        if (element == null) return false;
        return context.typeCompatibility.canAssign(elementType, element);
    }

    private static TypeDescriptor iterableElement(final ResolutionContext context,
                                                  final TypeDescriptor type) {
        final var actual = strip(type);
        final var name = TypeClassEvidence.nominalName(actual);
        if (name == null) return null;
        final var arguments = actual instanceof GenericDescriptor generic
                ? generic.typeParameters() : List.<TypeDescriptor>of();

        final var declaration = context.classes.get(name);
        if (declaration != null) {
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            for (int index = 0; index < declaration.typeParameters().size()
                    && index < arguments.size(); index++) {
                substitutions.put(declaration.typeParameters().get(index), arguments.get(index));
            }
            for (final var applied : ContractComposition.forClass(context, declaration)) {
                final var use = applied.use();
                if (!use.name().lexeme().equals("zeron.collections.Iterable")
                        || use.typeArguments().isEmpty()) continue;
                return TypeSubstitution.substitute(use.typeArguments().getFirst(), substitutions);
            }
            return null;
        }

        final var contract = context.contracts.get(name);
        if (contract == null) return null;
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < contract.typeParameters().size()
                && index < arguments.size(); index++) {
            substitutions.put(contract.typeParameters().get(index), arguments.get(index));
        }
        for (final var required : contract.requiredContracts()) {
            if (!required.name().lexeme().equals("zeron.collections.Iterable")) continue;
            if (required.typeArguments().isEmpty()) continue;
            return TypeSubstitution.substitute(required.typeArguments().getFirst(), substitutions);
        }
        return null;
    }

    private static TypeDescriptor strip(final TypeDescriptor type) {
        return type instanceof ReferenceDescriptor reference ? reference.baseType() : type;
    }
}
