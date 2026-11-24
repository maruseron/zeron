package com.maruseron.zeron.runtime;

import zeron.runtime.ContinuationProvider;
import zeron.runtime.ContinuationProviderLoader;
import zeron.runtime.DisposeGuard;
import zeron.runtime.EffectCarrier;
import zeron.runtime.EffectSupport;
import zeron.runtime.SuspensionGuard;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/// Host-side pull driver for `zeron.collections.generator`.
///
/// The body runs inside a provider session. A `zeron.lang.Yield` perform suspends the session and
/// is delivered to the consumer's receiver after [advance] returns; the next [advance] resumes the
/// body with `Unit`. Other effects pass through to the enclosing handler because the generator frame
/// rethrows a carrier it does not own. The body is never started by [open], so cancelling before the
/// first pull does not invoke the lambda.
public final class GeneratorSupport {
    private static final String YIELD_EFFECT = "zeron.lang.Yield";
    private static final MethodHandle DISPATCH = dispatchHandle();
    private static final System.Logger LOG = System.getLogger("zeron.generator");

    private final Object body;
    private final ContinuationProvider.Session session;
    private Object value;
    private boolean started;
    private boolean done;

    public static GeneratorSupport open(final Object body) {
        return new GeneratorSupport(body);
    }

    private GeneratorSupport(final Object body) {
        this.body = body;
        this.session = ContinuationProviderLoader.select().provider().start(suspension -> {
            final var frame = new Frame(suspension, SuspensionGuard.depth());
            EffectSupport.push(frame, DISPATCH);
            try {
                invoke(body, new Object[0]);
            } finally {
                EffectSupport.pop();
            }
        });
    }

    /// Resumes or starts the body and passes one yielded value to `receive`.
    ///
    /// Returns false once the body completes normally.
    public boolean advance(final Object receive) {
        if (done) return false;
        started = true;
        try {
            session.resume(unit());
        } catch (final Throwable failure) {
            done = true;
            return sneakyThrow(failure);
        }
        if (session.isDone()) {
            done = true;
            return false;
        }
        value = yieldValue(session.suspendedPayload());
        try {
            invoke(receive, new Object[]{value});
        } catch (final Throwable failure) {
            done = true;
            return sneakyThrow(failure);
        }
        return true;
    }

    public boolean isDone() {
        return done;
    }

    /// Stops a started body, returning its cleanup failure, or null.
    ///
    /// Cancelling before the first pull marks the cursor done without invoking the lambda.
    public Object cancel() {
        if (done) return null;
        done = true;
        if (!started) return null;
        try {
            if (!session.isDone()) session.cancel();
        } catch (final Throwable failure) {
            return failure;
        }
        return null;
    }

    /// Stops a started body and sends any cleanup failure to the runtime diagnostic log.
    public void close() {
        final var failure = cancel();
        if (failure != null) {
            LOG.log(System.Logger.Level.ERROR, "Generator cleanup failed.", failure);
        }
    }

    /// The consumer receives the `Yield` effect's `value` property, not the carrier object.
    private static Object yieldValue(final Object payload) {
        try {
            return payload.getClass().getMethod("$zeron$get$value").invoke(payload);
        } catch (final ReflectiveOperationException failure) {
            throw new IllegalStateException("A Yield payload must expose its value getter.", failure);
        }
    }

    private static Object dispatch(final Object frame, final Object raised) throws Throwable {
        if (!(frame instanceof Frame generator)) {
            throw new IllegalStateException("Generator frame mismatch.");
        }
        if (!(raised instanceof EffectCarrier carrier)) {
            throw (Throwable) raised;
        }
        if (!carrier.payload().getClass().getName().equals(YIELD_EFFECT)) {
            // Another effect passed through this frame: let the search continue outward.
            throw (Throwable) raised;
        }
        DisposeGuard.requireNoSuspension();
        SuspensionGuard.requireUnchanged(generator.boundaryDepth());
        return generator.suspension().suspend(carrier.payload());
    }

    private static Object unit() {
        return UnitHolder.INSTANCE;
    }

    private static final class UnitHolder {
        private static final Object INSTANCE = loadUnit();
    }

    private static Object loadUnit() {
        try {
            return Class.forName("zeron.lang.Unit").getField("INSTANCE").get(null);
        } catch (final ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot resolve zeron.lang.Unit.", failure);
        }
    }

    private static MethodHandle dispatchHandle() {
        try {
            return MethodHandles.lookup().findStatic(GeneratorSupport.class, "dispatch",
                    MethodType.methodType(Object.class, Object.class, Object.class));
        } catch (final ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private record Frame(ContinuationProvider.Suspension suspension, int boundaryDepth) {}

    private static Object invoke(final Object callable, final Object[] arguments) throws Throwable {
        final Method method = findInvoke(callable.getClass(), arguments.length);
        if (method == null) {
            throw new IllegalArgumentException("A generator callback must expose invoke with "
                    + arguments.length + " parameter(s).");
        }
        try {
            return method.invoke(callable, arguments);
        } catch (final InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static Method findInvoke(final Class<?> type, final int parameterCount) {
        Method fallback = null;
        for (final var method : type.getMethods()) {
            if (!method.getName().equals("invoke") || method.getParameterCount() != parameterCount) {
                continue;
            }
            if (parameterCount == 1 && method.getParameterTypes()[0] == Object.class) {
                method.trySetAccessible();
                return method;
            }
            if (fallback == null) {
                method.trySetAccessible();
                fallback = method;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(final Throwable throwable) throws E {
        throw (E) throwable;
    }
}
