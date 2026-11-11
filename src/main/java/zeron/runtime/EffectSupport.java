package zeron.runtime;

public final class EffectSupport {
    private EffectSupport() {}

    public static Object requireEffect(final Object value) {
        if (!(value instanceof EffectValue)) {
            throw new ClassCastException("A value raised as an effect must implement EffectValue.");
        }
        return value;
    }

    /// Reports a dispatch that matched incomparable handler cases, which can happen when a later
    /// library adds an effect subtype of both. Dispatch never falls back to source order.
    public static RuntimeException ambiguousEffect(final Object payload,
                                                   final String firstEffect,
                                                   final String secondEffect) {
        final var payloadName = payload == null ? "null" : payload.getClass().getName();
        return new IllegalStateException("Ambiguous effect handler: payload " + payloadName
                + " matches both " + firstEffect + " and " + secondEffect + ".");
    }
}
