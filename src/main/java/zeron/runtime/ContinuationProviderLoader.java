package zeron.runtime;

import java.util.List;
import java.util.ServiceLoader;

/// Selects the level-3 continuation backend without linking internal JDK types.
///
/// Selection is intentionally process-local. The compiler process and a launched
/// program may be configured differently, so the launcher must pass the same module
/// export to both when A is desired.
public final class ContinuationProviderLoader {
    public static final String BACKEND_PROPERTY = "zeron.continuation.backend";
    public static final String AUTO = "auto";
    public static final String LOOM = "a";
    public static final String STATE_MACHINE = "b";
    public static final String LOOM_PACKAGE = "jdk.internal.vm";
    public static final String LOOM_EXPORT_ARGUMENT =
            "java.base/jdk.internal.vm=ALL-UNNAMED";

    private ContinuationProviderLoader() {}

    public static Selection select() {
        return select(System.getProperty(BACKEND_PROPERTY, AUTO));
    }

    public static Selection select(final String requestedBackend) {
        final var requested = requestedBackend == null || requestedBackend.isBlank()
                ? AUTO : requestedBackend.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (requested) {
            case AUTO -> selectAutomatic();
            case LOOM -> selectLoom();
            case STATE_MACHINE -> new Selection(new FallbackContinuationProvider(), requested,
                    true, "backend B requested explicitly; transform not implemented");
            default -> throw new IllegalArgumentException("Unknown continuation backend '" + requested
                    + "'. Expected one of: auto, a, b.");
        };
    }

    /// Arguments a launcher must add before the main class or `-jar`.
    ///
    /// The package test avoids passing an export for a package that a future JDK may
    /// remove. It does not prove that the current JDK exports the package; that is
    /// [ContinuationProvider#isAvailable()]'s job.
    public static List<String> launcherArguments() {
        return loomPackagePresent()
                ? List.of("--add-exports", LOOM_EXPORT_ARGUMENT)
                : List.of();
    }

    public static boolean loomPackagePresent() {
        return ModuleLayer.boot().findModule("java.base")
                .map(module -> module.getPackages().contains(LOOM_PACKAGE))
                .orElse(false);
    }

    private static Selection selectAutomatic() {
        final var attempt = loadLoom();
        if (attempt.provider() != null) {
            return new Selection(attempt.provider(), AUTO, false, attempt.detail());
        }
        return new Selection(new FallbackContinuationProvider(), AUTO, true, attempt.detail());
    }

    private static Selection selectLoom() {
        final var attempt = loadLoom();
        if (attempt.provider() != null) {
            return new Selection(attempt.provider(), LOOM, false, attempt.detail());
        }
        final var failure = new IllegalStateException(
                "Continuation backend A is unavailable: " + attempt.detail()
                        + " Launch with --add-exports " + LOOM_EXPORT_ARGUMENT
                        + " and use a JDK that supports the internal continuation package.");
        if (attempt.cause() != null) failure.initCause(attempt.cause());
        throw failure;
    }

    private static Attempt loadLoom() {
        try {
            for (final var provider : ServiceLoader.load(ContinuationProvider.class)) {
                if (!LOOM.equals(provider.id())) continue;
                try {
                    if (provider.isAvailable()) {
                        return new Attempt(provider, "Loom provider loaded", null);
                    }
                    return new Attempt(null,
                            "Loom provider is present but the internal package is not exported", null);
                } catch (final Throwable failure) {
                    return new Attempt(null,
                            "Loom provider availability check failed: " + failure.getMessage(), failure);
                }
            }
            return new Attempt(null, "no Loom provider service was found", null);
        } catch (final Throwable failure) {
            return new Attempt(null,
                    "Loom provider could not be loaded: " + failure.getMessage(), failure);
        }
    }

    public record Selection(ContinuationProvider provider,
                            String requested,
                            boolean fallback,
                            String detail) {
        public String id() {
            return provider.id();
        }
    }

    private record Attempt(ContinuationProvider provider, String detail, Throwable cause) {}
}
