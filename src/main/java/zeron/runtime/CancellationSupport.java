package zeron.runtime;

import java.util.concurrent.CancellationException;

/// Private task-cancellation state shared by the structured-concurrency runtime and the
/// continuation driver.
///
/// Cancellation is an internal token, not a carrier: a level-3 case cannot match it, and only
/// `using`/`dispose` cleanup and the runtime observe it. The token is a
/// `CancellationException` subtype with an unforgeable marker, so Java `finally` blocks run while a
/// user-thrown `CancellationException` is not mistaken for task cancellation.
public final class CancellationSupport {
    /// The private signal. Its type is not constructible outside the runtime package.
    public static final class Token extends CancellationException {
        Token() {
            super("The task was cancelled.");
        }
    }

    /// One task's cancellation record. `TaskSupport` owns one, installs it on the task thread, and
    /// sets it from any cancelling thread.
    public static final class Request {
        private final Token token = new Token();
        private volatile boolean cancelled;

        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            cancelled = true;
        }

        Token token() {
            return token;
        }
    }

    private static final ThreadLocal<Request> CURRENT = new ThreadLocal<>();

    private CancellationSupport() {}

    /// Installs the task's cancellation record on the current thread.
    public static void enter(final Request request) {
        CURRENT.set(request);
    }

    public static void exit() {
        CURRENT.remove();
    }

    /// Reports whether the current task thread has a pending cancellation request.
    public static boolean requested() {
        final var request = CURRENT.get();
        return request != null && request.cancelled;
    }

    /// Throws the task's private token when cancellation has been requested.
    public static void checkpoint() {
        final var request = CURRENT.get();
        if (request != null && request.cancelled) throw request.token;
    }

    /// The current task's token, or a detached one when no task is installed.
    static Token token() {
        final var request = CURRENT.get();
        return request == null ? new Token() : request.token;
    }

    public static boolean isToken(final Throwable throwable) {
        return throwable instanceof Token;
    }

    /// Converts the private token to a public `CancellationException`, preserving the cleanup
    /// failures the token collected. A non-token failure is preserved as the suppressed cause.
    public static CancellationException normalize(final Throwable failure) {
        final var cancellation = new CancellationException("The task was cancelled.");
        if (failure == null) return cancellation;
        if (failure instanceof Token) {
            for (final var suppressed : failure.getSuppressed()) {
                cancellation.addSuppressed(suppressed);
            }
        } else {
            cancellation.addSuppressed(failure);
        }
        return cancellation;
    }
}
