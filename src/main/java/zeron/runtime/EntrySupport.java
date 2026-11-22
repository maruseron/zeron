package zeron.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;

/// Host-side installation of the program entry's implicit `Await` handler.
///
/// The handler is a normal resuming `Await` case: it waits for the payload's completion and
/// resumes the `perform` with `Unit`. The awaiting caller then reads the finished task through
/// `Task.value()`, so the handler never returns a value or needs an erased join.
///
/// The generated launcher pushes this frame around both the user entry call and the non-`Unit`
/// entry witness. Library code does not get the frame.
public final class EntrySupport {
    private static final String AWAIT_EFFECT = "zeron.concurrent.Await";
    private static final MethodHandle HANDLER = handler();

    private EntrySupport() {}

    /// Installs the blocking `Await` policy on the current thread.
    ///
    /// `owner` is the generated entry class. It supplies the class loader used to create the
    /// runtime carrier, which must be the loader that also contains the compiled program.
    public static void pushAwaitHandler(final Class<?> owner) {
        EffectSupport.push(owner, HANDLER);
    }

    /// Removes the handler installed by [pushAwaitHandler].
    public static void popAwaitHandler() {
        EffectSupport.pop();
    }

    private static MethodHandle handler() {
        try {
            return MethodHandles.lookup().findStatic(EntrySupport.class, "handle",
                    MethodType.methodType(Object.class, Object.class, Object.class));
        } catch (final ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Object handle(final Object ignored, final Object raised) throws Throwable {
        if (!(raised instanceof EffectCarrier carrier)) {
            throw (Throwable) raised;
        }
        final var payload = carrier.payload();
        if (payload == null) throw (Throwable) raised;
        final var loader = payload.getClass().getClassLoader();
        final var awaitType = Class.forName(AWAIT_EFFECT, true, loader);
        if (!awaitType.isInstance(payload)) {
            // A different effect passes through the entry frame unchanged.
            throw (Throwable) raised;
        }
        final var pending = awaitType.getMethod("$zeron$get$pending").invoke(payload);
        try {
            Class.forName("zeron.concurrent.Completion", true, loader)
                    .getMethod("awaitDone").invoke(pending);
        } catch (final InvocationTargetException failure) {
            throw failure.getCause();
        }
        return Class.forName("zeron.lang.Unit", true, loader).getField("INSTANCE").get(null);
    }
}
