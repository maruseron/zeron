package zeron.runtime;

/// Portable fallback selected when the Loom provider cannot be used.
///
/// The real B compiler transform is not part of this provider gate yet. Keeping the
/// fallback explicit makes the launcher contract testable and prevents a missing A
/// provider from being mistaken for a missing backend choice.
public final class FallbackContinuationProvider implements ContinuationProvider {
    public static final String ID = "b";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Session start(final Body body) {
        throw new UnsupportedOperationException(
                "Backend B is selected but its compiler-managed transform is not implemented yet. "
                        + "Launch with --add-exports java.base/jdk.internal.vm=ALL-UNNAMED to use "
                        + "backend A, or use the zeron launcher, which adds the export. A plain "
                        + "java -jar launch is unsupported for programs that use continuations.");
    }
}
