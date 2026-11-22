package zeron.runtime;

import java.lang.invoke.MethodHandle;
import java.util.ArrayDeque;
import java.util.Deque;

public final class EffectSupport {
    private record Frame(Object handler, MethodHandle invoke) {}

    private static final ThreadLocal<Deque<Frame>> FRAMES =
            ThreadLocal.withInitial(ArrayDeque::new);

    private EffectSupport() {}

    public static Object requireEffect(final Object value) {
        if (!(value instanceof EffectValue)) {
            throw new ClassCastException("A value raised as an effect must implement EffectValue.");
        }
        return value;
    }

    /// Installs a resuming handler around the protected expression.
    public static void push(final Object handler, final MethodHandle invoke) {
        FRAMES.get().addLast(new Frame(handler, invoke));
    }

    /// Removes the innermost resuming handler. A no-op for an already empty stack.
    public static void pop() {
        final var frames = FRAMES.get();
        if (!frames.isEmpty()) frames.removeLast();
    }

    /// Performs a responding perform. The innermost active resuming handler runs with the frame
    /// temporarily inactive, so effects raised while evaluating its response reach the next
    /// enclosing handler. When a handler does not match, it rethrows the same carrier and the
    /// search continues outward.
    public static Object perform(final Object payload) {
        final var frames = FRAMES.get();
        while (!frames.isEmpty()) {
            final var frame = frames.peekLast();
            frames.removeLast();
            var restore = true;
            try {
                final var handlerLoader = frame.handler() instanceof Class<?> owner
                        ? owner.getClassLoader()
                        : frame.handler().getClass().getClassLoader();
                return frame.invoke().invoke(frame.handler(),
                        raisedEffect(payload, handlerLoader));
            } catch (final Throwable failure) {
                if (!(failure instanceof EffectCarrier carrier)) {
                    return sneakyThrow(failure);
                }
                if (carrier.payload() != payload) {
                    // A different effect passed through this handler: keep looking outward.
                    restore = false;
                    return perform(carrier.payload());
                }
                restore = false;
            } finally {
                if (restore && !frames.contains(frame)) frames.addLast(frame);
            }
        }
        throw raisedEffect(payload, EffectSupport.class.getClassLoader());
    }

    private static RuntimeException raisedEffect(final Object payload,
                                                 final ClassLoader handlerLoader) {
        try {
            return (RuntimeException) Class.forName("zeron.runtime.RaisedEffect", true, handlerLoader)
                    .getConstructor(Object.class).newInstance(payload);
        } catch (final ReflectiveOperationException failure) {
            try {
                return (RuntimeException) Class.forName("zeron.runtime.RaisedEffect")
                        .getConstructor(Object.class).newInstance(payload);
            } catch (final ReflectiveOperationException fallbackFailure) {
                fallbackFailure.addSuppressed(failure);
                return new IllegalStateException("Cannot create zeron.runtime.RaisedEffect.",
                        fallbackFailure);
            }
        }
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

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(final Throwable failure) throws E {
        throw (E) failure;
    }
}
