package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.EffectRowDescriptor;
import com.maruseron.zeron.domain.EffectRows;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeFormatter;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects inclusion constraints for effect-row variables and solves them with a minimal
 * solution. The solution is the union of all lower bounds; upper bounds are verified against it.
 */
final class RowInference {
    private final Map<TypeParameterDescriptor, EffectRowDescriptor> lowerBounds = new LinkedHashMap<>();
    private final Map<TypeParameterDescriptor, List<EffectRowDescriptor>> upperBounds =
            new LinkedHashMap<>();

    void addLowerBound(final TypeParameterDescriptor variable, final EffectRowDescriptor bound) {
        // Keep self-references so recursive calls do not default the enclosing row to empty.
        final var previous = lowerBounds.get(variable);
        lowerBounds.put(variable, previous == null ? bound : previous.union(bound));
    }

    void addUpperBound(final TypeParameterDescriptor variable, final EffectRowDescriptor bound) {
        upperBounds.computeIfAbsent(variable, ignored -> new ArrayList<>()).add(bound);
    }

    boolean contains(final TypeParameterDescriptor variable) {
        return lowerBounds.containsKey(variable) || upperBounds.containsKey(variable);
    }

    void solve(final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
               final Token where) {
        final var variables = new java.util.LinkedHashSet<TypeParameterDescriptor>();
        variables.addAll(lowerBounds.keySet());
        variables.addAll(upperBounds.keySet());
        for (final var variable : variables) {
            final var existing = substitutions.get(variable);
            final var solution = existing == null ? solution(variable) : EffectRows.asRow(existing);
            if (existing == null) substitutions.put(variable, solution);
            checkLowerBounds(variable, solution, where);
            checkUpperBounds(variable, solution, where);
        }
    }

    /**
     * Adds an empty lower bound for a row parameter that did not occur in the inferred signature,
     * so that the minimal solution is the empty row.
     */
    static void defaultEmpty(final TypeParameterDescriptor variable,
                             final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (!substitutions.containsKey(variable)) {
            substitutions.put(variable, EffectRowDescriptor.empty());
        }
    }

    private EffectRowDescriptor solution(final TypeParameterDescriptor variable) {
        final var lower = lowerBounds.get(variable);
        return lower == null ? EffectRowDescriptor.empty() : lower;
    }

    private void checkLowerBounds(final TypeParameterDescriptor variable,
                                  final EffectRowDescriptor solution,
                                  final Token where) {
        final var lower = lowerBounds.get(variable);
        if (lower == null) return;
        final var offender = EffectRows.firstUnpermitted(List.copyOf(lower.effects()),
                List.copyOf(solution.effects()));
        if (offender != null) {
            error(variable, solution, offender, where);
        }
        for (final var lowerVariable : lower.variables()) {
            if (!solution.variables().contains(lowerVariable)) {
                error(variable, solution, lowerVariable, where);
            }
        }
    }

    private void checkUpperBounds(final TypeParameterDescriptor variable,
                                  final EffectRowDescriptor solution,
                                  final Token where) {
        final var upper = upperBounds.get(variable);
        if (upper == null) return;
        for (final var bound : upper) {
            final var offender = EffectRows.firstUnpermitted(List.copyOf(solution.effects()),
                    List.copyOf(bound.effects()));
            if (offender != null) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, where,
                        "Effect '" + TypeFormatter.format(offender)
                                + "' inferred for row '" + variable.name()
                                + "' is not permitted by the enclosing callback type."));
            }
            for (final var solutionVariable : solution.variables()) {
                if (!bound.variables().contains(solutionVariable)) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, where,
                            "Row variable '" + solutionVariable.name()
                                    + "' inferred for row '" + variable.name()
                                    + "' is not permitted by the enclosing callback type."));
                }
            }
        }
    }

    private void error(final TypeParameterDescriptor variable,
                       final EffectRowDescriptor solution,
                       final TypeDescriptor offender,
                       final Token where) {
        Zeron.resolutionError(new ResolutionError(
                DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, where,
                "Effect '" + TypeFormatter.format(offender)
                        + "' does not fit effect row '" + variable.name()
                        + "' (inferred row " + solution.name() + ")."));
    }
}
