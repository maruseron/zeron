package zeron.runtime;

import java.util.ArrayList;
import java.util.List;

/// Test-classpath counterpart of the compiler-emitted runtime carrier.
public final class RaisedEffect extends RuntimeException implements EffectCarrier {
    private final Object payload;
    private final List<Object> suppressedOutcomes = new ArrayList<>();

    public RaisedEffect(final Object payload) {
        this.payload = payload;
    }

    @Override
    public Object payload() {
        return payload;
    }

    public void addSuppressedOutcome(final Object outcome, final Throwable failure) {
        suppressedOutcomes.add(outcome);
        if (outcome != failure) addSuppressed(failure);
    }

    public Object[] suppressedOutcomes() {
        return suppressedOutcomes.toArray();
    }
}
