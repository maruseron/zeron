package zeron.runtime;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/// One-shot continuation token.
///
/// The delegate owns the actual backend session. This class owns the source-level
/// lifecycle rules: owner-thread confinement, one-shot resume, terminal states, and
/// idempotent cancellation after a terminal state.
public final class Cont<R, T> {
    public interface Delegate<R, T> {
        /// Resumes the body and returns its final result. The delegate must have
        /// completed the computation when this returns normally.
        T resume(R response) throws Throwable;

        void cancel() throws Throwable;

        boolean isDone();

        StackTraceElement[] stackTrace();

        /// Rejects use after the owning `handle` exited. The default allows it; a level-3
        /// delegate overrides this with the per-`handle` generation token.
        default void requireActive() {}
    }

    private enum State {
        SUSPENDED,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED
    }

    /// Marks a syntactic tail resume: the case frame is abandoned and the runtime drives the
    /// continuation from its own loop. Carries the response and the continuation it targets so a
    /// foreign transfer cannot be mistaken for one owned by the dispatching handler.
    static final class Transfer extends RuntimeException {
        private final Cont<?, ?> continuation;
        private final Object response;

        Transfer(final Cont<?, ?> continuation, final Object response) {
            super(null, null, false, false);
            this.continuation = continuation;
            this.response = response;
        }

        Cont<?, ?> continuation() { return continuation; }
        Object response() { return response; }
    }

    private final Delegate<R, T> delegate;
    private final Thread owner;
    private State state = State.SUSPENDED;

    public Cont(final Delegate<R, T> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.owner = Thread.currentThread();
    }

    public T resume(final R response) throws Throwable {
        requireOwner();
        delegate.requireActive();
        if (state != State.SUSPENDED) {
            throw new IllegalStateException("Cannot resume a continuation in state " + state + ".");
        }
        state = State.RUNNING;
        try {
            final var result = delegate.resume(response);
            state = State.DONE;
            return result;
        } catch (final CancellationException cancellation) {
            state = State.CANCELLED;
            throw cancellation;
        } catch (final Throwable failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    /// Cancels a suspended continuation. Cancellation is idempotent after any
    /// terminal state; only the suspended state invokes the delegate.
    public void cancel() throws Throwable {
        requireOwner();
        delegate.requireActive();
        if (state != State.SUSPENDED) return;
        state = State.RUNNING;
        try {
            delegate.cancel();
            state = State.CANCELLED;
        } catch (final Throwable failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    public boolean isDone() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    public StackTraceElement[] stackTrace() {
        return delegate.stackTrace();
    }

    /// Abandons this continuation's case frame and hands the response to the runtime loop. Used
    /// only by the tail-resume lowering; unlike [resume], it never returns.
    Object tailResume(final R response) {
        requireOwner();
        delegate.requireActive();
        if (state != State.SUSPENDED) {
            throw new IllegalStateException("Cannot resume a continuation in state " + state + ".");
        }
        state = State.RUNNING;
        throw new Transfer(this, response);
    }

    /// Completes a tail-resumed continuation once the body finishes.
    void settleTransfer() {
        if (state == State.RUNNING) state = State.DONE;
    }

    /// Fails or cancels a tail-resumed continuation when the body unwinds.
    void failTransfer(final Throwable failure) {
        if (state != State.RUNNING) return;
        state = failure instanceof CancellationException ? State.CANCELLED : State.FAILED;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Continuation resumed off its owning thread.");
        }
    }
}
