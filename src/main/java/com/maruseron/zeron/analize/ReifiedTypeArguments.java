package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Selects the applied types whose descriptors a call must pass for a callee's {@code reified}
 * parameters. Non-exported callables infer missing markers; selections are recomputed after
 * resolution so declaration order does not affect the inferred ABI.
 */
final class ReifiedTypeArguments {
    private ReifiedTypeArguments() {
    }

    static List<TypeDescriptor> select(final ResolutionContext context,
                                       final Expr expression,
                                       final List<TypeParameterDescriptor> typeParameters,
                                       final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                                       final Token where,
                                       final Consumer<List<TypeDescriptor>> setter) {
        final var result = selectValues(context, typeParameters, substitutions, where);
        context.recordPendingReifiedSelection(expression, typeParameters, substitutions, where, setter);
        setter.accept(result);
        return result;
    }

    private static List<TypeDescriptor> selectValues(final ResolutionContext context,
                                                     final List<TypeParameterDescriptor> typeParameters,
                                                     final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                                                     final Token where) {
        final var result = new ArrayList<TypeDescriptor>();
        for (final var parameter : typeParameters) {
            if (!parameter.isReified()) continue;
            final var actual = substitutions.get(parameter);
            if (actual == null) {
                throw new IllegalStateException("A reified type parameter has no substitution: "
                        + parameter.name());
            }
            ensureDescribable(context, actual, where);
            result.add(actual);
        }
        return List.copyOf(result);
    }

    /// Whether a value of this static type can carry an exact ZEP-0015 descriptor in the current
    /// slice. Function types, projections, captures, effect rows, and non-reified variables do
    /// not have an exact descriptor.
    static boolean canDescribe(final TypeDescriptor type) {
        return switch (type) {
            case TypeParameterDescriptor parameter -> parameter.isReified();
            case NullableDescriptor nullable -> canDescribe(nullable.baseType());
            case ReferenceDescriptor reference -> canDescribe(reference.baseType());
            case ArrayDescriptor array -> canDescribe(array.elementType());
            case GenericDescriptor generic -> generic.typeParameters().stream()
                    .allMatch(ReifiedTypeArguments::canDescribe);
            case NeverDescriptor _, AnyDescriptor _, UnitDescriptor _, IntDescriptor _,
                 CharDescriptor _, LongDescriptor _, ByteDescriptor _, FloatDescriptor _,
                 BooleanDescriptor _, StringDescriptor _, ByteArrayDescriptor _,
                 NominalDescriptor _ -> true;
            default -> false;
        };
    }

    static void finalizeInferredMarkers(final ResolutionContext context) {
        boolean changed;
        do {
            changed = false;
            for (final var pending : context.pendingReifiedSelections()) {
                for (final var parameter : pending.typeParameters()) {
                    if (!parameter.isReified()) continue;
                    final var actual = pending.substitutions().get(parameter);
                    if (actual != null && inferDescribable(actual, pending, context)) {
                        changed = true;
                    }
                }
            }
        } while (changed);

        for (final var pending : context.pendingReifiedSelections()) {
            try {
                final var result = selectValuesWithoutInference(pending.typeParameters(),
                        pending.substitutions(), pending.where());
                pending.setter().accept(result);
            } catch (final ResolutionError error) {
                context.diagnostics.record(error, error.sourcePath == null
                        ? context.currentSourcePath : error.sourcePath);
            }
        }
    }

    private static List<TypeDescriptor> selectValuesWithoutInference(
            final List<TypeParameterDescriptor> typeParameters,
            final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
            final Token where) {
        final var result = new ArrayList<TypeDescriptor>();
        for (final var parameter : typeParameters) {
            if (!parameter.isReified()) continue;
            final var actual = substitutions.get(parameter);
            if (actual == null) continue;
            ensureDescribable(null, actual, where);
            result.add(actual);
        }
        return List.copyOf(result);
    }

    private static boolean inferDescribable(final TypeDescriptor type,
                                            final ResolutionContext.PendingReifiedSelection pending,
                                            final ResolutionContext context) {
        return switch (type) {
            case TypeParameterDescriptor parameter -> !parameter.isReified()
                    && context.inferReified(parameter, pending.inferableScopes());
            case NullableDescriptor nullable -> inferDescribable(nullable.baseType(), pending, context);
            case ReferenceDescriptor reference -> inferDescribable(reference.baseType(), pending, context);
            case ArrayDescriptor array -> inferDescribable(array.elementType(), pending, context);
            case GenericDescriptor generic -> {
                var changed = false;
                for (final var argument : generic.typeParameters()) {
                    changed |= inferDescribable(argument, pending, context);
                }
                yield changed;
            }
            default -> false;
        };
    }

    private static void ensureDescribable(final ResolutionContext context, final TypeDescriptor type,
                                          final Token where) {
        switch (type) {
            case TypeParameterDescriptor parameter -> {
                if (!parameter.isReified()
                        && (context == null || !context.inferReified(parameter))) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, where,
                            "Type parameter '" + parameter.name()
                                    + "' is used where a runtime type descriptor is required; "
                                    + "mark it 'reified'."));
                }
            }
            case NullableDescriptor nullable -> ensureDescribable(context, nullable.baseType(), where);
            case ReferenceDescriptor reference -> ensureDescribable(context, reference.baseType(), where);
            case ArrayDescriptor array -> ensureDescribable(context, array.elementType(), where);
            case GenericDescriptor generic -> {
                for (final var argument : generic.typeParameters()) {
                    ensureDescribable(context, argument, where);
                }
            }
            case FunctionDescriptor _ -> Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, where,
                    "Function types cannot be reified in this version."));
            case NeverDescriptor _, AnyDescriptor _, UnitDescriptor _, IntDescriptor _,
                 LongDescriptor _, ByteDescriptor _, FloatDescriptor _, BooleanDescriptor _,
                 StringDescriptor _, CharDescriptor _, ByteArrayDescriptor _,
                 NominalDescriptor _, NullDescriptor _ -> {
            }
            default -> Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, where,
                    "Type '" + TypeFormatter.format(type)
                            + "' has no exact runtime type descriptor."));
        }
    }
}
