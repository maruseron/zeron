package zeron.runtime;

/// Thread-local marker for host-code regions that a continuation may not cross.
///
/// A host callback is a Java (or native) frame between a `perform` and the handler that must
/// suspend. Loom cannot capture that frame, so the compiler marks the call that hands a Zeron
/// callback to host code. A level-3 frame records the marker depth at which it was installed; the
/// suspension path rejects a handler whose recorded depth differs from the current depth.
///
/// The marker is a runtime backstop, not the primary rule: SAM conversion already rejects a
/// non-empty `performs` row on a converted callback. It catches erased rows, unsafe host calls, and
/// callbacks whose effect was hidden from the type system. `DisposeGuard` is the cleanup boundary
/// and keeps its own, stricter check.
public final class SuspensionGuard {
    /// The marker kind named in diagnostics for a Java or native call that receives a callback.
    public static final String HOST_CALLBACK = "host callback";

    /// A suspension attempt crossed a host frame. This is a program error, not a carrier, so a
    /// handler case cannot catch it; ordinary `finally` and `using` unwinding still run.
    public static final class BoundaryException extends RuntimeException {
        public BoundaryException(final String kind) {
            super("Cannot suspend a continuation across a " + kind + " boundary.");
        }
    }

    private record State(int depth, String kind) {}

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private SuspensionGuard() {}

    /// Marks the start of a host region. Nested regions keep the innermost kind for diagnostics.
    public static void enter(final String kind) {
        final var current = STATE.get();
        STATE.set(current == null ? new State(1, kind)
                : new State(current.depth + 1, kind));
    }

    /// Marks the end of a host region.
    public static void exit() {
        final var current = STATE.get();
        if (current == null) return;
        if (current.depth <= 1) {
            STATE.remove();
            return;
        }
        STATE.set(new State(current.depth - 1, current.kind));
    }

    /// The current marker depth. A level-3 frame records this when it is installed.
    public static int depth() {
        final var current = STATE.get();
        return current == null ? 0 : current.depth;
    }

    /// Rejects a suspension when host regions were entered after the handler frame was installed.
    public static void requireUnchanged(final int frameDepth) {
        final var current = STATE.get();
        final var depth = current == null ? 0 : current.depth;
        if (depth != frameDepth) {
            throw new BoundaryException(current == null ? HOST_CALLBACK : current.kind);
        }
    }
}
