package zeron.runtime;

/// Thread-local marker for an active `using` cleanup.
///
/// Cleanup must stay synchronous: an `Await` perform would block the unwinding stack, and a
/// level-3 suspension would freeze it. The generated resource support enters the marker around
/// each `dispose` call; `EffectSupport` and `Level3Support` reject forbidden operations while it
/// is active.
public final class DisposeGuard {
    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private DisposeGuard() {}

    public static void enter() {
        final var depth = DEPTH.get();
        DEPTH.set(depth == null ? 1 : depth + 1);
    }

    public static void exit() {
        final var depth = DEPTH.get();
        if (depth == null) return;
        if (depth <= 1) DEPTH.remove();
        else DEPTH.set(depth - 1);
    }

    public static boolean active() {
        final var depth = DEPTH.get();
        return depth != null && depth > 0;
    }

    /// Rejects a blocking `Await` perform while cleanup is running. Other responding level-2
    /// effects such as `Ask` and `Log` remain synchronous and are allowed.
    public static void requireEffectAllowed(final Object payload) {
        if (!active() || payload == null) return;
        if (payload.getClass().getName().equals("zeron.concurrent.Await")) {
            throw new IllegalStateException(
                    "Cannot perform Await while dispose cleanup is running.");
        }
    }

    /// Rejects a level-3 suspension while cleanup is running.
    public static void requireNoSuspension() {
        if (active()) {
            throw new IllegalStateException(
                    "Cannot suspend a continuation while dispose cleanup is running.");
        }
    }
}
