package zeron.runtime;

/// Backend-neutral boundary for level-3 continuations.
///
/// The core runtime must not link against `jdk.internal.vm.Continuation`. A backend
/// implements this interface in an isolated provider; [ContinuationProviderLoader]
/// selects it at startup and falls back when the provider cannot be loaded or the
/// runtime does not permit the internal package.
public interface ContinuationProvider {
    /// Stable identifier used by `-Dzeron.continuation.backend`.
    String id();

    /// Reports whether this provider can run in the current JVM without throwing.
    ///
    /// This method must not touch internal API classes that require a module export.
    boolean isAvailable();

    /// Creates a suspended session. The body does not run until [Session#resume()].
    Session start(Body body);

    @FunctionalInterface
    interface Body {
        void run(Suspension suspension) throws Throwable;
    }

    @FunctionalInterface
    interface Suspension {
        /// Suspends the body with a payload until the owner calls
        /// [Session#resume(Object)]. The returned value is the handler response.
        Object suspend(Object payload);

        /// Convenience for a suspension with no payload.
        default Object suspend() {
            return suspend(null);
        }
    }

    interface Session {
        /// Runs or resumes the body with the handler response. Returns normally when
        /// the body suspends again, and throws a captured body failure when the body
        /// completes exceptionally.
        void resume(Object response) throws Throwable;

        /// The payload supplied by the most recent [Suspension#suspend(Object)].
        Object suspendedPayload();

        boolean isDone();

        /// Cancels a suspended session. The provider must not deliver a value after
        /// this call; the body may unwind its own cleanup. A cleanup failure is
        /// rethrown to the cancelling caller.
        void cancel() throws Throwable;

        /// Returns the suspended body stack when the provider exposes one.
        StackTraceElement[] stackTrace();
    }
}
