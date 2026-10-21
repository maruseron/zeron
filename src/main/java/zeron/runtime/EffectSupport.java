package zeron.runtime;

public final class EffectSupport {
    private EffectSupport() {}

    public static Object requireEffect(final Object value) {
        if (!(value instanceof EffectValue)) {
            throw new ClassCastException("A value raised as an effect must implement EffectValue.");
        }
        return value;
    }
}
