package zeron.runtime.loom;

import java.util.concurrent.CancellationException;

import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

import zeron.runtime.ContinuationProvider;
import zeron.runtime.ContinuationProviderLoader;

/// Backend A provider. This is the only project source that names the internal
/// `jdk.internal.vm` types; the core runtime sees only [ContinuationProvider].
///
/// The build compiles this source set with
/// `--add-exports java.base/jdk.internal.vm=ALL-UNNAMED`; launch paths must pass the
/// same export. If the export is absent, [isAvailable()] is false and the loader
/// selects the portable fallback.
public final class LoomContinuationProvider implements ContinuationProvider {
    public static final String ID = "a";
    private static final ContinuationScope SCOPE = new ContinuationScope("zep-0119");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        final var exported = ModuleLayer.boot().findModule("java.base")
                .map(module -> module.isExported(ContinuationProviderLoader.LOOM_PACKAGE,
                        ContinuationProvider.class.getModule()))
                .orElse(false);
        return exported && apiShapePresent();
    }

    /// Checks the internal API this provider compiles against. A JDK that keeps the package but
    /// changes the constructor or method shape must fall back instead of failing at the first
    /// suspension with a linkage error.
    public static boolean apiShapePresent() {
        try {
            Continuation.class.getConstructor(ContinuationScope.class, Runnable.class);
            if (!Continuation.class.getMethod("run").getReturnType().equals(void.class)) return false;
            if (!Continuation.class.getMethod("isDone").getReturnType().equals(boolean.class)) return false;
            if (!Continuation.class.getMethod("getStackTrace").getReturnType()
                    .equals(StackTraceElement[].class)) return false;
            if (!Continuation.class.getMethod("yield", ContinuationScope.class).getReturnType()
                    .equals(boolean.class)) return false;
            ContinuationScope.class.getConstructor(String.class);
            return true;
        } catch (final ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    @Override
    public Session start(final Body body) {
        if (!isAvailable()) {
            throw new IllegalStateException("Backend A is not available in this JVM.");
        }
        return new LoomSession(body);
    }

    private static final class LoomSession implements Session {
        private final Continuation continuation;
        private Throwable failure;
        private Object response;
        private Object payload;
        private boolean cancelled;

        private LoomSession(final Body body) {
            continuation = new Continuation(SCOPE, () -> {
                try {
                    body.run(this::suspend);
                } catch (final CancellationException cancellation) {
                    // Cancellation is delivered as an unwind, not as a body failure. Cleanup that
                    // fails while unwinding is attached to the cancellation as suppressed; keep
                    // it so an explicit cancel can report it instead of swallowing it.
                    captureCleanupFailure(cancellation);
                } catch (final Throwable throwable) {
                    failure = throwable;
                }
            });
        }

        private Object suspend(final Object nextPayload) {
            payload = nextPayload;
            Continuation.yield(SCOPE);
            if (cancelled) throw new CancellationException("The continuation was cancelled.");
            return response;
        }

        private void captureCleanupFailure(final CancellationException cancellation) {
            for (final var suppressed : cancellation.getSuppressed()) {
                if (suppressed instanceof zeron.runtime.EffectCarrier) {
                    failure = suppressed;
                    return;
                }
            }
        }

        @Override
        public Object suspendedPayload() {
            return payload;
        }

        @Override
        public void resume(final Object nextResponse) throws Throwable {
            if (cancelled) throw new CancellationException("The continuation was cancelled.");
            if (failure != null) throw failure;
            if (continuation.isDone()) {
                throw new IllegalStateException("Cannot resume a completed continuation.");
            }
            response = nextResponse;
            continuation.run();
            if (failure != null) throw failure;
        }

        @Override
        public boolean isDone() {
            return cancelled || failure != null || continuation.isDone();
        }

        @Override
        public void cancel() throws Throwable {
            if (isDone()) return;
            cancelled = true;
            if (!continuation.isDone()) continuation.run();
            if (failure != null) throw failure;
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return continuation.getStackTrace();
        }
    }
}
