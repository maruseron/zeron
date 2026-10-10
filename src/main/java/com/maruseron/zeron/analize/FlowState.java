package com.maruseron.zeron.analize;

import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.*;

record FlowFact(boolean mayBeNull, Set<TypeDescriptor> nonNullAlternatives) {
    FlowFact {
        if (nonNullAlternatives != null) nonNullAlternatives = Set.copyOf(nonNullAlternatives);
    }
}

final class FlowState {
    private final IdentityHashMap<Token, FlowFact> facts = new IdentityHashMap<>();
    private final IdentityHashMap<Token, Integer> writeCounts = new IdentityHashMap<>();
    private boolean reachable = true;

    FlowState copy() {
        final var copy = new FlowState();
        copy.facts.putAll(facts);
        copy.writeCounts.putAll(writeCounts);
        copy.reachable = reachable;
        return copy;
    }

    static FlowState unreachable() {
        final var state = new FlowState();
        state.reachable = false;
        return state;
    }

    boolean isReachable() {
        return reachable;
    }

    void markUnreachable() {
        reachable = false;
    }

    FlowFact get(final Token bindingName) {
        return facts.get(bindingName);
    }

    void put(final Token bindingName, final FlowFact fact) {
        facts.put(bindingName, fact);
    }

    void remove(final Token bindingName) {
        facts.remove(bindingName);
    }

    int writeCount(final Token bindingName) {
        return writeCounts.getOrDefault(bindingName, 0);
    }

    void recordWrite(final Token bindingName) {
        final var count = writeCount(bindingName);
        writeCounts.put(bindingName, count < 0 ? -1 : Math.min(2, count + 1));
    }

    static FlowState join(final FlowState left, final FlowState right) {
        if (!left.reachable) return right.copy();
        if (!right.reachable) return left.copy();
        final var joined = new FlowState();
        for (final var entry : left.facts.entrySet()) {
            final var rightFact = right.facts.get(entry.getKey());
            if (rightFact == null) continue;
            final var leftFact = entry.getValue();
            final Set<TypeDescriptor> alternatives;
            if (leftFact.nonNullAlternatives() == null || rightFact.nonNullAlternatives() == null) {
                alternatives = null;
            } else {
                final var union = new LinkedHashSet<>(leftFact.nonNullAlternatives());
                union.addAll(rightFact.nonNullAlternatives());
                alternatives = union;
            }
            joined.put(entry.getKey(), new FlowFact(
                    leftFact.mayBeNull() || rightFact.mayBeNull(), alternatives));
        }
        final var writtenNames = Collections.newSetFromMap(new IdentityHashMap<Token, Boolean>());
        writtenNames.addAll(left.writeCounts.keySet());
        writtenNames.addAll(right.writeCounts.keySet());
        for (final var name : writtenNames) {
            final var leftCount = left.writeCount(name);
            final var rightCount = right.writeCount(name);
            joined.writeCounts.put(name, leftCount == rightCount ? leftCount : -1);
        }
        return joined;
    }

    boolean sameAs(final FlowState other) {
        if (reachable != other.reachable || facts.size() != other.facts.size()
                || writeCounts.size() != other.writeCounts.size()) return false;
        for (final var entry : facts.entrySet()) {
            if (!Objects.equals(entry.getValue(), other.facts.get(entry.getKey()))) return false;
        }
        for (final var entry : writeCounts.entrySet()) {
            if (!Objects.equals(entry.getValue(), other.writeCounts.get(entry.getKey()))) return false;
        }
        return true;
    }
}
