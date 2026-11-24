package zeron.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/// Minimal runtime driver for a level-3 handler.
///
/// This is the bridge between the existing `EffectSupport.perform` path and a
/// provider-backed body. The body runs inside a provider session; when a perform
/// reaches the level-3 frame, the session suspends and the owner loop constructs a
/// [Cont] for the handler case. The case can resume it later; resuming drives the
/// same session and recursively dispatches further suspensions.
///
/// The compiler emits the body and handler as ordinary function objects and passes
/// their `invoke` handles to [run]. Bodies return the handled expression's value so
/// [Cont#resume] can hand it back to the case.
///
/// Each `handle` owns a generation token. A [Cont] created for it rejects use after the
/// handle exits, and task cancellation is delivered before the body is resumed again.
public final class Level3Support {
    private static final System.Logger LOG = System.getLogger("zeron.continuation");

    public interface Body {
        Object run() throws Throwable;
    }

    public interface Handler {
        /// Handles one suspended payload. Calling [Cont#resume] drives the body
        /// forward; returning without resuming aborts the handled computation.
        Object handle(Object payload, Cont<Object, Object> continuation) throws Throwable;
    }

    /// Wraps a throwable raised by a handler case. A case body is outside the handler, so its
    /// abortive effects must escape the `handle` instead of being caught by that handle's
    /// abortive arms. The emitted catch unwraps and rethrows the cause.
    public static final class HandlerEscape extends RuntimeException {
        public HandlerEscape(final Throwable cause) {
            super(cause);
        }
    }

    private static final MethodHandle DISPATCH = dispatchHandle();

    private Level3Support() {}

    /// Tail-resume transfer for a syntactic tail case. The emitted case calls this instead of
    /// [Cont#resume]; it abandons the case frame by throwing a private marker that the runtime
    /// loop catches.
    public static Object transfer(final Cont<Object, Object> continuation, final Object response) {
        return continuation.tailResume(response);
    }

    /// Adapts a compiled function object with `invoke()Object` to a [Body].
    public static Body body(final Object target, final MethodHandle invoke) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(invoke, "invoke");
        return () -> invoke.invoke(target);
    }

    /// Adapts a compiled function object with `invoke(Object, Object)Object` to a [Handler].
    public static Handler handler(final Object target, final MethodHandle invoke) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(invoke, "invoke");
        return (payload, continuation) -> invoke.invoke(target, payload, continuation);
    }

    /// Runs a compiled level-3 handler with the provider selected for this process.
    public static Object run(final Object body, final MethodHandle bodyInvoke,
                             final Object handler, final MethodHandle handlerInvoke) throws Throwable {
        return run(SelectedProvider.PROVIDER, body(body, bodyInvoke), handler(handler, handlerInvoke));
    }

    public static Object run(final ContinuationProvider provider,
                             final Body body,
                             final Handler handler) throws Throwable {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(handler, "handler");

        final var result = new Object[1];
        final var generation = new Generation();
        final var transfers = new ArrayList<Cont<?, ?>>();
        final var session = provider.start(suspension -> {
            final var frame = new Frame(suspension, SuspensionGuard.depth());
            EffectSupport.push(frame, DISPATCH);
            try {
                result[0] = body.run();
            } finally {
                EffectSupport.pop();
            }
        });

        try {
            Object response = null;
            var started = false;
            for (;;) {
                // Do not start the body for an already-cancelled task: a provider cancel would
                // run it up to the first suspension. The task boundary reports cancellation.
                if (started) checkCancellation(session);
                else if (CancellationSupport.requested()) throw CancellationSupport.token();
                session.resume(response);
                started = true;
                settleTransfers(transfers);
                if (session.isDone()) return result[0];
                checkCancellation(session);

                final var payload = session.suspendedPayload();
                final var delegate = new SessionDelegate(session, handler, result, generation, transfers);
                final var continuation = new Cont<Object, Object>(delegate);
                final Object value;
                try {
                    value = handler.handle(payload, continuation);
                } catch (final Cont.Transfer transfer) {
                    // The case body was in syntactic tail position, so its frame is gone. Drive
                    // the body from this loop instead of returning into the case.
                    if (transfer.continuation() != continuation) throw transfer;
                    delegate.resumed = true;
                    transfers.add(continuation);
                    response = transfer.response();
                    continue;
                } catch (final HandlerEscape escape) {
                    throw escape;
                } catch (final Throwable failure) {
                    throw new HandlerEscape(failure);
                }
                if (!delegate.resumed) {
                    // A case that returns without resuming aborts the handled
                    // computation; its value is the handle result. This is an
                    // implicit cancellation: cleanup failure is logged, not
                    // allowed to replace the abort value or change the row.
                    cancelImplicitly(continuation);
                    failTransfers(transfers, new CancellationException(ABORTED));
                    return value;
                }
                if (session.isDone()) {
                    settleTransfers(transfers);
                    return value;
                }
                response = null;
            }
        } catch (final Throwable failure) {
            failTransfers(transfers, failure);
            if (!session.isDone()) {
                try {
                    session.cancel();
                } catch (final Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        } finally {
            generation.invalidate();
        }
    }

    private static final String ABORTED =
            "The handled computation was aborted by a case that did not resume.";

    /// Delivers a pending task cancellation to the suspended continuation: unwinds its cleanup,
    /// then throws the private token so the task boundary can normalize it.
    private static void checkCancellation(final ContinuationProvider.Session session) throws Throwable {
        if (!CancellationSupport.requested()) return;
        final var token = CancellationSupport.token();
        if (!session.isDone()) {
            try {
                session.cancel();
            } catch (final Throwable cleanupFailure) {
                token.addSuppressed(cleanupFailure);
            }
        }
        throw token;
    }

    private static void settleTransfers(final List<Cont<?, ?>> transfers) {
        for (final var continuation : transfers) continuation.settleTransfer();
        transfers.clear();
    }

    private static void failTransfers(final List<Cont<?, ?>> transfers, final Throwable failure) {
        for (final var continuation : transfers) continuation.failTransfer(failure);
        transfers.clear();
    }

    private static Object dispatch(final Object frame, final Object raised) throws Throwable {
        if (!(frame instanceof Frame level3)) {
            throw new IllegalStateException("Level-3 frame mismatch.");
        }
        if (!(raised instanceof EffectCarrier carrier)) {
            throw (Throwable) raised;
        }
        DisposeGuard.requireNoSuspension();
        SuspensionGuard.requireUnchanged(level3.boundaryDepth());
        return level3.suspension().suspend(carrier.payload());
    }

    private static MethodHandle dispatchHandle() {
        try {
            return MethodHandles.lookup().findStatic(Level3Support.class, "dispatch",
                    MethodType.methodType(Object.class, Object.class, Object.class));
        } catch (final ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private record Frame(ContinuationProvider.Suspension suspension, int boundaryDepth) {}

    /// One `handle`'s liveness. A continuation that escapes keeps this token and fails cleanly
    /// once the handle exits instead of reaching a retired provider session.
    private static final class Generation {
        private boolean active = true;

        private void invalidate() {
            active = false;
        }

        private void requireActive() {
            if (!active) {
                throw new IllegalStateException(
                        "Cannot use a continuation after its handler exited.");
            }
        }
    }

    /// A Cont delegate whose resume loop dispatches subsequent suspensions back
    /// through the same handler. A nested case that returns without resuming
    /// aborts: its value becomes the result of the pending resume.
    private static final class SessionDelegate implements Cont.Delegate<Object, Object> {
        private final ContinuationProvider.Session session;
        private final Handler handler;
        private final Object[] bodyResult;
        private final Generation generation;
        private final List<Cont<?, ?>> transfers;
        private boolean resumed;

        private SessionDelegate(final ContinuationProvider.Session session,
                                final Handler handler,
                                final Object[] bodyResult,
                                final Generation generation,
                                final List<Cont<?, ?>> transfers) {
            this.session = session;
            this.handler = handler;
            this.bodyResult = bodyResult;
            this.generation = generation;
            this.transfers = transfers;
        }

        @Override
        public Object resume(final Object response) throws Throwable {
            resumed = true;
            checkCancellation(session);
            session.resume(response);
            settleTransfers(transfers);
            while (!session.isDone()) {
                checkCancellation(session);
                final var payload = session.suspendedPayload();
                final var nested = new SessionDelegate(session, handler, bodyResult,
                        generation, transfers);
                final var continuation = new Cont<Object, Object>(nested);
                final Object value;
                try {
                    value = handler.handle(payload, continuation);
                } catch (final Cont.Transfer transfer) {
                    if (transfer.continuation() != continuation) throw transfer;
                    nested.resumed = true;
                    transfers.add(continuation);
                    checkCancellation(session);
                    session.resume(transfer.response());
                    settleTransfers(transfers);
                    continue;
                }
                if (!nested.resumed) {
                    cancelImplicitly(continuation);
                    failTransfers(transfers, new CancellationException(ABORTED));
                    return value;
                }
                if (session.isDone()) return bodyResult[0];
            }
            return bodyResult[0];
        }

        @Override
        public void cancel() throws Throwable {
            session.cancel();
        }

        @Override
        public boolean isDone() {
            return session.isDone();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return session.stackTrace();
        }

        @Override
        public void requireActive() {
            generation.requireActive();
        }
    }

    private static void cancelImplicitly(final Cont<Object, Object> continuation) {
        try {
            continuation.cancel();
        } catch (final Throwable cleanupFailure) {
            LOG.log(System.Logger.Level.ERROR,
                    "Continuation cleanup failed during implicit cancellation.", cleanupFailure);
        }
    }

    private static final class SelectedProvider {
        private static final ContinuationProvider PROVIDER =
                ContinuationProviderLoader.select().provider();
    }
}
